package ghostlink.lab.codecs.erasure

import ghostlink.lab.codecs.SchemeId

/** Baseline without cross-frame FEC (brief Candidate A): chunk = id mod K, repeated in a carousel. */
object SequentialCarousel {
    class Encoder(payload: ByteArray, override val symbolSize: Int) : ErasureEncoder {
        override val scheme = SchemeId.SEQUENTIAL
        override val payloadBytes = payload.size
        private val blocks = Erasure.split(payload, symbolSize)
        override val sourceSymbols = blocks.size

        override fun symbol(id: Long): ByteArray {
            require(id >= 0)
            return blocks[(id % sourceSymbols).toInt()].copyOf()
        }
    }

    class Decoder(override val payloadBytes: Int, override val symbolSize: Int) : ErasureDecoder {
        override val scheme = SchemeId.SEQUENTIAL
        override val sourceSymbols = Erasure.sourceSymbols(payloadBytes, symbolSize)
        private val blocks = arrayOfNulls<ByteArray>(sourceSymbols)
        private var have = 0
        override var duplicateSymbols = 0
            private set
        override val uniqueSymbols: Int get() = have
        override val isComplete: Boolean get() = have == sourceSymbols

        override fun add(id: Long, data: ByteArray): AddResult {
            if (id < 0 || data.size != symbolSize) return AddResult.REJECTED
            val idx = (id % sourceSymbols).toInt()
            if (blocks[idx] != null) { duplicateSymbols++; return AddResult.DUPLICATE }
            blocks[idx] = data.copyOf()
            have++
            return if (isComplete) AddResult.COMPLETE else AddResult.ACCEPTED
        }

        override fun result(): ByteArray? = if (isComplete) Erasure.join(blocks, payloadBytes, symbolSize) else null
    }
}
