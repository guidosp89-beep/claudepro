package ghostlink.lab.tools

import ghostlink.lab.codecs.DeterministicPayload
import ghostlink.lab.codecs.SchemeId
import ghostlink.lab.codecs.erasure.Erasure
import ghostlink.lab.codecs.erasure.ErasureDecoder
import ghostlink.lab.codecs.erasure.ErasureEncoder
import ghostlink.lab.codecs.erasure.LtCode
import java.io.File
import kotlin.random.Random

/** Variants compared in the fountain benchmark (brief §28). */
enum class FecVariant(val label: String) {
    SEQUENTIAL("sequential"), LT_PEELING("lt-peeling"), LT_GAUSS("lt+gauss"), RAPTORQ("raptorq");

    fun encoder(p: ByteArray, t: Int): ErasureEncoder = when (this) {
        SEQUENTIAL -> Erasure.encoder(SchemeId.SEQUENTIAL, p, t)
        LT_PEELING, LT_GAUSS -> LtCode.Encoder(p, t)
        RAPTORQ -> Erasure.encoder(SchemeId.RAPTORQ, p, t)
    }

    fun decoder(size: Int, t: Int): ErasureDecoder = when (this) {
        SEQUENTIAL -> Erasure.decoder(SchemeId.SEQUENTIAL, size, t)
        LT_PEELING -> LtCode.Decoder(size, t, gaussianFallback = false)
        LT_GAUSS -> LtCode.Decoder(size, t, gaussianFallback = true)
        RAPTORQ -> Erasure.decoder(SchemeId.RAPTORQ, size, t)
    }
}

/**
 * Encode/decode throughput, memory and reception overhead (symbols needed / K) under 20% random
 * erasure with shuffled order. Symbol size 1000 B ≈ one QR v25-L frame minus lab header.
 */
object FountainBench {
    fun run(out: File, quick: Boolean) {
        val t = 1000
        val sizes = if (quick) listOf(4096, 65536, 262144) else listOf(256, 4096, 65536, 262144, 1 shl 20, 5 shl 20)
        Csv(File(out, "fountain_bench.csv"), listOf(
            "variant", "payload_bytes", "symbol_size", "K", "trials", "encode_MBps", "decode_MBps",
            "overhead_mean", "overhead_p50", "overhead_p90", "overhead_p99", "overhead_max",
            "fail_at_K", "fail_at_K1", "fail_at_K2", "decoder_heap_MB",
        )).use { csv ->
            // JIT warm-up so the first measured configuration is not penalised.
            for (v in FecVariant.entries) {
                val wp = DeterministicPayload.generate(1, 65536)
                val we = v.encoder(wp, t)
                val wd = v.decoder(wp.size, t)
                var i = 0L
                while (!wd.isComplete && i < 2000) { wd.add(i, we.symbol(i)); i++ }
            }
            for (size in sizes) for (v in FecVariant.entries) {
                if (v == FecVariant.SEQUENTIAL && size > (1 shl 20)) continue
                val k = Erasure.sourceSymbols(size, t)
                val trials = when { k <= 70 -> 60; k <= 300 -> 30; k <= 1100 -> 10; else -> 3 }.let { if (quick) minOf(it, 5) else it }
                val payload = DeterministicPayload.generate(size.toLong(), size)
                // encode throughput: K source + 50% repair symbols
                val tEnc0 = System.nanoTime()
                val enc = v.encoder(payload, t)
                val nEnc = (k * 1.5).toInt().coerceAtLeast(2)
                for (i in 0 until nEnc) enc.symbol(i.toLong())
                val encS = (System.nanoTime() - tEnc0) / 1e9
                val overheads = ArrayList<Double>()
                var decNs = 0L
                var heapMb = 0.0
                for (trial in 0 until trials) {
                    val rnd = Random(trial * 7919L + size)
                    // 20% iid erasure over a long enough stream, then shuffle (reordering)
                    val stream = (0 until k * 6 + 40).filter { rnd.nextDouble() >= 0.2 }.shuffled(rnd)
                    val rt = Runtime.getRuntime(); System.gc(); val before = rt.totalMemory() - rt.freeMemory()
                    val dec = v.decoder(size, t)
                    for (id in stream) {
                        val sym = enc.symbol(id.toLong()) // generation is not part of decode time
                        val t0 = System.nanoTime()
                        dec.add(id.toLong(), sym)
                        decNs += System.nanoTime() - t0
                        if (dec.isComplete) break
                    }
                    heapMb = maxOf(heapMb, (rt.totalMemory() - rt.freeMemory() - before) / 1048576.0)
                    check(dec.isComplete && dec.result()!!.contentEquals(payload)) { "${v.label} failed size=$size trial=$trial" }
                    overheads.add(dec.uniqueSymbols.toDouble() / k - 1.0)
                }
                val decMBps = size.toDouble() * trials / 1048576.0 / (decNs / 1e9)
                fun failAt(extra: Int) = overheads.count { (it + 1.0) * k > k + extra + 1e-9 }.toDouble() / overheads.size
                csv.row(v.label, size, t, k, trials, nEnc * t / 1048576.0 / encS, decMBps,
                    overheads.average(), pct(overheads, 50.0), pct(overheads, 90.0), pct(overheads, 99.0), overheads.max(),
                    failAt(0), failAt(1), failAt(2), heapMb)
                println("  ${v.label} size=$size K=$k overhead p50=%.3f p90=%.3f decode %.1f MB/s".format(pct(overheads, 50.0), pct(overheads, 90.0), decMBps))
            }
        }
    }
}

/**
 * Frame-loss channel without camera (brief §27): iid loss 0–50%, bursty (Gilbert–Elliott), duplicates,
 * reordering. Metric: frames transmitted until the 256 KB object is complete, and efficiency K/frames.
 */
object LossBench {
    data class Channel(val name: String, val lossRate: Double, val bursty: Boolean = false, val dupRate: Double = 0.0, val reorder: Int = 0)

    fun run(out: File, quick: Boolean) {
        val size = 262_144
        val t = 1000
        val k = Erasure.sourceSymbols(size, t)
        val payload = DeterministicPayload.generate(99, size)
        val channels = listOf(0.0, 0.05, 0.10, 0.20, 0.30, 0.40, 0.50).map { Channel("iid_${(it * 100).toInt()}", it) } + listOf(
            Channel("bursty_20", 0.20, bursty = true),
            Channel("bursty_40", 0.40, bursty = true),
            Channel("dup20_loss10", 0.10, dupRate = 0.20),
            Channel("reorder8_loss10", 0.10, reorder = 8),
            Channel("all_20", 0.20, bursty = true, dupRate = 0.2, reorder = 8),
        )
        val reps = if (quick) 3 else 12
        val encoders = FecVariant.entries.associateWith { it.encoder(payload, t) }
        Csv(File(out, "loss_bench.csv"), listOf("variant", "channel", "loss_rate", "bursty", "dup_rate", "reorder", "K",
            "reps", "frames_p50", "frames_p90", "efficiency_p50", "ideal_efficiency", "time_s_at_15fps_p50", "goodput_KBps_at_15fps_p50")).use { csv ->
            for (ch in channels) for (v in FecVariant.entries) {
                val frames = ArrayList<Double>()
                for (rep in 0 until reps) {
                    val rnd = Random(rep * 31L + ch.name.hashCode())
                    val dec = v.decoder(size, t)
                    val enc = encoders[v]!!
                    var sent = 0
                    var bad = false
                    val buf = ArrayList<Int>()
                    var id = 0
                    val limit = k * 60
                    while (!dec.isComplete && sent < limit) {
                        sent++
                        val lost = if (ch.bursty) {
                            // Gilbert–Elliott with mean burst 5 frames and stationary loss = lossRate
                            val pBg = 0.2
                            val pGb = ch.lossRate * pBg / (1 - ch.lossRate)
                            bad = if (bad) rnd.nextDouble() >= pBg else rnd.nextDouble() < pGb
                            bad
                        } else rnd.nextDouble() < ch.lossRate
                        val cur = id++
                        if (!lost) {
                            buf.add(cur)
                            if (rnd.nextDouble() < ch.dupRate) buf.add(cur)
                        }
                        if (buf.size > ch.reorder) {
                            if (ch.reorder > 0) buf.shuffle(rnd)
                            for (b in buf) dec.add(b.toLong(), enc.symbol(b.toLong()))
                            buf.clear()
                        }
                    }
                    frames.add(if (dec.isComplete) sent.toDouble() else Double.POSITIVE_INFINITY)
                }
                val p50 = pct(frames, 50.0)
                val eff = k / p50
                val seconds = p50 / 15.0
                csv.row(v.label, ch.name, ch.lossRate, ch.bursty, ch.dupRate, ch.reorder, k, reps, p50, pct(frames, 90.0), eff,
                    1 - ch.lossRate, seconds, size / 1024.0 / seconds)
                println("  ${ch.name} ${v.label}: frames p50=%.0f efficiency=%.3f".format(p50, eff))
            }
        }
    }
}
