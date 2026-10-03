package ghostlink.lab.codecs.erasure

import ghostlink.lab.codecs.SchemeId

/**
 * RaptorQ (RFC 6330) through the pure-Kotlin `io.github.andreypfau:raptorq-kotlin` library
 * (Apache-2.0, single source block). ESI 0..K-1 are systematic source symbols.
 * Note: RaptorQ IPR declarations exist at the IETF (see docs/research/02 §4.2): legal review before
 * any distribution beyond this lab.
 *
 * Library finding (M1, raptorq-kotlin 1.0.0): `Decoder.decodeFullyToByteArray()` /
 * `decodeFullyIntoByteArray()` throw ArrayIndexOutOfBoundsException for many (K, T) combinations
 * (e.g. K=2..128 with T=512), and `addSymbol()==true` only means "enough symbols to try":
 * `solvedEncoderOrNull()` stays null until `solve()` is called explicitly. Workaround used here:
 * on readiness call `solve()`, then regenerate the K systematic source symbols from the solved encoder.
 */
object RaptorQCodec {
    /** RFC 6330 single-source-block limit. */
    const val MAX_SOURCE_SYMBOLS = 56403

    class Encoder(payload: ByteArray, override val symbolSize: Int) : ErasureEncoder {
        override val scheme = SchemeId.RAPTORQ
        override val payloadBytes = payload.size
        override val sourceSymbols = Erasure.sourceSymbols(payload.size, symbolSize)
        private val impl: io.github.andreypfau.raptorq.Encoder

        init {
            require(sourceSymbols <= MAX_SOURCE_SYMBOLS) { "K=$sourceSymbols exceeds RaptorQ single-block limit" }
            impl = io.github.andreypfau.raptorq.Encoder(symbolSize, payload)
        }

        override fun symbol(id: Long): ByteArray {
            require(id in 0..Int.MAX_VALUE)
            return impl.encodeToByteArray(id.toInt())
        }
    }

    class Decoder(override val payloadBytes: Int, override val symbolSize: Int) : ErasureDecoder {
        override val scheme = SchemeId.RAPTORQ
        override val sourceSymbols = Erasure.sourceSymbols(payloadBytes, symbolSize)
        private val impl = io.github.andreypfau.raptorq.Decoder(payloadBytes, symbolSize)
        private val seen = HashSet<Long>()
        private var decoded: ByteArray? = null
        override var duplicateSymbols = 0
            private set
        override val uniqueSymbols: Int get() = seen.size
        override val isComplete: Boolean get() = decoded != null

        override fun add(id: Long, data: ByteArray): AddResult {
            if (id < 0 || id > Int.MAX_VALUE || data.size != symbolSize) return AddResult.REJECTED
            if (!seen.add(id)) { duplicateSymbols++; return AddResult.DUPLICATE }
            if (decoded != null) return AddResult.COMPLETE
            val ready = try {
                impl.addSymbol(id.toInt(), data)
            } catch (e: RuntimeException) {
                return AddResult.REJECTED
            }
            if (ready) decoded = reconstruct()
            return if (decoded != null) AddResult.COMPLETE else AddResult.ACCEPTED
        }

        private fun reconstruct(): ByteArray? = try {
            impl.solve()
            val solved = impl.solvedEncoderOrNull()
            if (solved == null) null else {
                val out = ByteArray(sourceSymbols * symbolSize)
                for (i in 0 until sourceSymbols) solved.encodeIntoByteArray(i, out, i * symbolSize)
                out.copyOf(payloadBytes)
            }
        } catch (e: RuntimeException) {
            null
        }

        override fun result(): ByteArray? = decoded?.copyOf()
    }
}
