package ghostlink.lab.codecs.erasure

import ghostlink.lab.codecs.SchemeId
import ghostlink.lab.codecs.SplitMix64
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Systematic Luby Transform code (spike implementation, the scheme used by TXQR/Decimen-style tools).
 * ids < K are the source blocks; ids >= K are LT repair symbols with robust-soliton degrees.
 * Decoder: belief-propagation peeling, plus an optional GF(2) Gaussian-elimination fallback when
 * peeling stalls (approximates inactivation decoding; lowers the reception overhead).
 */
object LtCode {
    const val DEFAULT_C = 0.05
    const val DEFAULT_DELTA = 0.5

    /** Robust soliton distribution (Luby 2002), as a CDF over degrees 1..k. */
    class Distribution(val k: Int, c: Double = DEFAULT_C, delta: Double = DEFAULT_DELTA) {
        private val cdf: DoubleArray

        init {
            require(k >= 1)
            val mu = DoubleArray(k + 1)
            if (k == 1) {
                mu[1] = 1.0
            } else {
                val r = c * ln(k / delta) * sqrt(k.toDouble())
                val spike = (k / r).toInt().coerceIn(1, k)
                for (d in 1..k) {
                    val rho = if (d == 1) 1.0 / k else 1.0 / (d.toDouble() * (d - 1))
                    val tau = when {
                        d < spike -> r / (d.toDouble() * k)
                        d == spike -> r * ln(r / delta) / k
                        else -> 0.0
                    }
                    mu[d] = rho + maxOf(tau, 0.0)
                }
            }
            val sum = mu.sum()
            cdf = DoubleArray(k)
            var acc = 0.0
            for (d in 1..k) { acc += mu[d] / sum; cdf[d - 1] = acc }
            cdf[k - 1] = 1.0
        }

        fun sample(u: Double): Int {
            var lo = 0
            var hi = k - 1
            while (lo < hi) { val mid = (lo + hi) ushr 1; if (cdf[mid] < u) lo = mid + 1 else hi = mid }
            return lo + 1
        }
    }

    /** Source-block neighbours of symbol [id] (deterministic: encoder and decoder agree). */
    fun neighbors(id: Long, k: Int, dist: Distribution): IntArray {
        if (id < k) return intArrayOf(id.toInt())
        val rng = SplitMix64(SplitMix64.mix(id * 0x2545F4914F6CDD1DL + k))
        val d = dist.sample(rng.nextDouble()).coerceAtMost(k)
        if (d * 2 > k) {
            val all = IntArray(k) { it }
            for (i in 0 until d) { val j = i + rng.nextInt(k - i); val t = all[i]; all[i] = all[j]; all[j] = t }
            return all.copyOf(d)
        }
        val out = IntArray(d)
        val used = HashSet<Int>(d * 2)
        var n = 0
        while (n < d) { val v = rng.nextInt(k); if (used.add(v)) out[n++] = v }
        return out
    }

    class Encoder(payload: ByteArray, override val symbolSize: Int) : ErasureEncoder {
        override val scheme = SchemeId.LT
        override val payloadBytes = payload.size
        private val blocks = Erasure.split(payload, symbolSize)
        override val sourceSymbols = blocks.size
        private val dist = Distribution(sourceSymbols)

        override fun symbol(id: Long): ByteArray {
            require(id >= 0)
            val out = ByteArray(symbolSize)
            for (n in neighbors(id, sourceSymbols, dist)) Erasure.xorInto(out, blocks[n])
            return out
        }
    }

    class Decoder(
        override val payloadBytes: Int,
        override val symbolSize: Int,
        private val gaussianFallback: Boolean = true,
    ) : ErasureDecoder {
        override val scheme = SchemeId.LT
        override val sourceSymbols = Erasure.sourceSymbols(payloadBytes, symbolSize)
        private val k = sourceSymbols
        private val dist = Distribution(k)
        private val resolved = arrayOfNulls<ByteArray>(k)
        private var resolvedCount = 0
        private val seen = HashSet<Long>()
        private val equations = ArrayList<Equation>()
        private val adjacency = Array(k) { ArrayList<Int>(4) }
        private var lastGaussianAt = 0
        override var duplicateSymbols = 0
            private set
        override val uniqueSymbols: Int get() = seen.size
        override val isComplete: Boolean get() = resolvedCount == k
        /** Number of Gaussian-elimination passes run (diagnostics). */
        var gaussianRuns = 0
            private set

        private class Equation(val neighbors: IntArray, var alive: Int, val data: ByteArray, var done: Boolean = false)

        override fun add(id: Long, data: ByteArray): AddResult {
            if (id < 0 || data.size != symbolSize) return AddResult.REJECTED
            if (!seen.add(id)) { duplicateSymbols++; return AddResult.DUPLICATE }
            if (isComplete) return AddResult.COMPLETE
            val acc = data.copyOf()
            val unresolved = ArrayList<Int>()
            for (n in neighbors(id, k, dist)) {
                val r = resolved[n]
                if (r != null) Erasure.xorInto(acc, r) else unresolved.add(n)
            }
            when (unresolved.size) {
                0 -> Unit // redundant symbol
                1 -> resolve(unresolved[0], acc)
                else -> {
                    val idx = equations.size
                    equations.add(Equation(unresolved.toIntArray(), unresolved.size, acc))
                    for (n in unresolved) adjacency[n].add(idx)
                }
            }
            if (gaussianFallback && !isComplete && seen.size >= k) {
                val step = maxOf(1, k / 64)
                if (seen.size - lastGaussianAt >= step || lastGaussianAt == 0) {
                    lastGaussianAt = seen.size
                    gaussianSolve()
                }
            }
            return if (isComplete) AddResult.COMPLETE else AddResult.ACCEPTED
        }

        private fun resolve(source: Int, value: ByteArray) {
            val queue = ArrayDeque<Pair<Int, ByteArray>>()
            queue.addLast(source to value)
            while (queue.isNotEmpty()) {
                val (s, v) = queue.removeFirst()
                if (resolved[s] != null) continue
                resolved[s] = v
                resolvedCount++
                for (eqIdx in adjacency[s]) {
                    val eq = equations[eqIdx]
                    if (eq.done) continue
                    Erasure.xorInto(eq.data, v)
                    eq.alive--
                    if (eq.alive == 1) {
                        eq.done = true
                        val last = eq.neighbors.first { resolved[it] == null }
                        queue.addLast(last to eq.data)
                    } else if (eq.alive <= 0) {
                        eq.done = true
                    }
                }
                adjacency[s].clear()
            }
        }

        /** Gauss-Jordan over GF(2) on the still-open equations; resolves everything if full rank. */
        private fun gaussianSolve() {
            gaussianRuns++
            val colOf = IntArray(k) { -1 }
            val cols = ArrayList<Int>()
            for (s in 0 until k) if (resolved[s] == null) { colOf[s] = cols.size; cols.add(s) }
            val u = cols.size
            if (u == 0) return
            val open = equations.filter { !it.done }
            if (open.size < u) return
            val words = (u + 63) / 64
            val rowsBits = Array(open.size) { LongArray(words) }
            val rowsData = Array(open.size) { open[it].data.copyOf() }
            for ((ri, eq) in open.withIndex()) {
                for (n in eq.neighbors) {
                    val c = colOf[n]
                    if (c >= 0 && resolved[n] == null) rowsBits[ri][c ushr 6] = rowsBits[ri][c ushr 6] xor (1L shl (c and 63))
                }
            }
            var rank = 0
            val pivotRowOfCol = IntArray(u) { -1 }
            for (c in 0 until u) {
                val w = c ushr 6
                val bit = 1L shl (c and 63)
                var p = -1
                for (r in rank until open.size) if (rowsBits[r][w] and bit != 0L) { p = r; break }
                if (p < 0) return // rank deficient: wait for more symbols
                if (p != rank) {
                    val tb = rowsBits[p]; rowsBits[p] = rowsBits[rank]; rowsBits[rank] = tb
                    val td = rowsData[p]; rowsData[p] = rowsData[rank]; rowsData[rank] = td
                }
                for (r in open.indices) {
                    if (r != rank && rowsBits[r][w] and bit != 0L) {
                        val rb = rowsBits[r]; val pb = rowsBits[rank]
                        for (i in 0 until words) rb[i] = rb[i] xor pb[i]
                        Erasure.xorInto(rowsData[r], rowsData[rank])
                    }
                }
                pivotRowOfCol[c] = rank
                rank++
            }
            for (c in 0 until u) {
                val s = cols[c]
                if (resolved[s] == null) { resolved[s] = rowsData[pivotRowOfCol[c]]; resolvedCount++ }
            }
            for (eq in equations) eq.done = true
        }

        override fun result(): ByteArray? = if (isComplete) Erasure.join(resolved, payloadBytes, symbolSize) else null
    }
}
