package ghostlink.lab.codecs.erasure

import ghostlink.lab.codecs.SchemeId

/**
 * Reliability layer, independent from the visual PHY (brief §29): the same encoder feeds QR, grid or
 * any other frame encoder, and the same decoder accepts symbols in any order, with loss and duplicates.
 */
interface ErasureEncoder {
    val scheme: SchemeId
    val payloadBytes: Int
    val symbolSize: Int
    val sourceSymbols: Int

    /** Deterministic encoded symbol for [id] (always exactly [symbolSize] bytes). */
    fun symbol(id: Long): ByteArray
}

enum class AddResult { ACCEPTED, DUPLICATE, COMPLETE, REJECTED }

interface ErasureDecoder {
    val scheme: SchemeId
    val payloadBytes: Int
    val symbolSize: Int
    val sourceSymbols: Int
    val isComplete: Boolean
    /** Distinct symbol ids accepted so far. */
    val uniqueSymbols: Int
    val duplicateSymbols: Int

    fun add(id: Long, data: ByteArray): AddResult

    /** Reconstructed payload, or null while incomplete. */
    fun result(): ByteArray?
}

object Erasure {
    fun sourceSymbols(payloadBytes: Int, symbolSize: Int): Int {
        require(payloadBytes > 0 && symbolSize > 0)
        return (payloadBytes + symbolSize - 1) / symbolSize
    }

    fun encoder(scheme: SchemeId, payload: ByteArray, symbolSize: Int): ErasureEncoder = when (scheme) {
        SchemeId.SEQUENTIAL -> SequentialCarousel.Encoder(payload, symbolSize)
        SchemeId.LT -> LtCode.Encoder(payload, symbolSize)
        SchemeId.RAPTORQ -> RaptorQCodec.Encoder(payload, symbolSize)
    }

    fun decoder(scheme: SchemeId, payloadBytes: Int, symbolSize: Int): ErasureDecoder = when (scheme) {
        SchemeId.SEQUENTIAL -> SequentialCarousel.Decoder(payloadBytes, symbolSize)
        SchemeId.LT -> LtCode.Decoder(payloadBytes, symbolSize)
        SchemeId.RAPTORQ -> RaptorQCodec.Decoder(payloadBytes, symbolSize)
    }

    /** Splits [payload] into K zero-padded source blocks of [symbolSize]. */
    internal fun split(payload: ByteArray, symbolSize: Int): Array<ByteArray> {
        val k = sourceSymbols(payload.size, symbolSize)
        return Array(k) { i ->
            val out = ByteArray(symbolSize)
            val off = i * symbolSize
            System.arraycopy(payload, off, out, 0, minOf(symbolSize, payload.size - off))
            out
        }
    }

    internal fun join(blocks: Array<ByteArray?>, payloadBytes: Int, symbolSize: Int): ByteArray {
        val out = ByteArray(payloadBytes)
        for (i in blocks.indices) {
            val b = blocks[i] ?: error("missing block $i")
            val off = i * symbolSize
            System.arraycopy(b, 0, out, off, minOf(symbolSize, payloadBytes - off))
        }
        return out
    }

    internal fun xorInto(dst: ByteArray, src: ByteArray) {
        for (i in dst.indices) dst[i] = (dst[i].toInt() xor src[i].toInt()).toByte()
    }
}
