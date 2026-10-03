package ghostlink.lab.codecs.visual

import com.google.zxing.common.reedsolomon.GenericGF
import com.google.zxing.common.reedsolomon.ReedSolomonDecoder
import com.google.zxing.common.reedsolomon.ReedSolomonEncoder
import com.google.zxing.common.reedsolomon.ReedSolomonException
import ghostlink.lab.codecs.SplitMix64

/**
 * Bytes <-> cell symbols for GLG grids (Candidates C and D). Image detection/sampling lives in the
 * decoder module; this object handles framing, Reed-Solomon (ZXing GF(256)), interleaving and whitening.
 */
object GridCodec {
    private val field = GenericGF.QR_CODE_FIELD_256
    private const val WHITEN_SEED = 0x474C475748L

    private val whitenCache = java.util.concurrent.ConcurrentHashMap<Int, ByteArray>()
    private fun whitening(n: Int): ByteArray = whitenCache.getOrPut(n) {
        val rng = SplitMix64(WHITEN_SEED)
        ByteArray(n) { rng.next().toByte() }
    }

    /** Encodes up to [GridLayout.frameCapacity] bytes into a renderable frame (quiet zone included). */
    fun encode(data: ByteArray, spec: GridSpec): VisualFrame {
        val layout = GridLayout.of(spec)
        return render(layout, encodeSymbols(data, layout))
    }

    /** Data bytes -> one palette index per data cell (layout.dataX order). */
    fun encodeSymbols(data: ByteArray, layout: GridLayout): IntArray {
        require(data.size <= layout.frameCapacity) { "data ${data.size} B exceeds grid capacity ${layout.frameCapacity} B" }
        val padded = ByteArray(layout.dataCapacity)
        padded[0] = (data.size and 0xFF).toByte()
        padded[1] = (data.size ushr 8).toByte()
        System.arraycopy(data, 0, padded, GridLayout.LENGTH_PREFIX, data.size)

        val parity = layout.spec.rsParity
        val encoder = ReedSolomonEncoder(field)
        val blocks = ArrayList<IntArray>(layout.blockLengths.size)
        var off = 0
        for (len in layout.blockLengths) {
            val k = len - parity
            val block = IntArray(len)
            for (i in 0 until k) block[i] = padded[off + i].toInt() and 0xFF
            encoder.encode(block, parity)
            blocks.add(block)
            off += k
        }
        val stream = interleave(blocks, layout.totalCodewords)
        val w = whitening(stream.size)
        for (i in stream.indices) stream[i] = (stream[i].toInt() xor w[i].toInt()).toByte()
        return bytesToSymbols(stream, layout.dataX.size, layout.spec.bitsPerCell)
    }

    class DecodeOutcome(val data: ByteArray?, val correctedBytes: Int, val failedBlocks: Int)

    /** Cell symbols -> data bytes, or null data if any RS block is uncorrectable / length invalid. */
    fun decodeSymbols(symbols: IntArray, layout: GridLayout): DecodeOutcome {
        require(symbols.size == layout.dataX.size)
        val stream = symbolsToBytes(symbols, layout.totalCodewords, layout.spec.bitsPerCell)
        val w = whitening(stream.size)
        for (i in stream.indices) stream[i] = (stream[i].toInt() xor w[i].toInt()).toByte()
        val blocks = deinterleave(stream, layout.blockLengths)
        val parity = layout.spec.rsParity
        val decoder = ReedSolomonDecoder(field)
        val out = ByteArray(layout.dataCapacity)
        var off = 0
        var corrected = 0
        var failed = 0
        for (block in blocks) {
            try {
                corrected += decoder.decodeWithECCount(block, parity)
            } catch (e: ReedSolomonException) {
                failed++
            }
            val k = block.size - parity
            for (i in 0 until k) out[off + i] = block[i].toByte()
            off += k
        }
        if (failed > 0) return DecodeOutcome(null, corrected, failed)
        val len = (out[0].toInt() and 0xFF) or ((out[1].toInt() and 0xFF) shl 8)
        if (len > layout.frameCapacity) return DecodeOutcome(null, corrected, 0)
        return DecodeOutcome(out.copyOfRange(GridLayout.LENGTH_PREFIX, GridLayout.LENGTH_PREFIX + len), corrected, 0)
    }

    fun render(layout: GridLayout, symbols: IntArray): VisualFrame {
        val q = Rasterizer.QUIET_CELLS
        val w = layout.cols + 2 * q
        val h = layout.rows + 2 * q
        val argb = IntArray(w * h) { Palette.WHITE }
        fun set(x: Int, y: Int, c: Int) { argb[(y + q) * w + x + q] = c }
        for (y in 0 until layout.rows) for (x in 0 until layout.cols) {
            if (layout.isReservedCorner(x, y) && layout.finderDark(x, y)) set(x, y, Palette.BLACK)
        }
        layout.keyTop.forEachIndexed { i, (x, y) -> set(x, y, if (GridLayout.KEY_TOP[i] == 1) Palette.BLACK else Palette.WHITE) }
        layout.keyBottom.forEachIndexed { i, (x, y) -> set(x, y, if (GridLayout.KEY_BOTTOM[i] == 1) Palette.BLACK else Palette.WHITE) }
        for ((x, y, c) in layout.refTop) set(x, y, layout.palette[c])
        for ((x, y, c) in layout.refBottom) set(x, y, layout.palette[c])
        for (i in symbols.indices) set(layout.dataX[i], layout.dataY[i], layout.palette[symbols[i]])
        return VisualFrame(w, h, argb)
    }

    internal fun interleave(blocks: List<IntArray>, total: Int): ByteArray {
        val out = ByteArray(total)
        var p = 0
        val maxLen = blocks.maxOf { it.size }
        for (i in 0 until maxLen) for (b in blocks) if (i < b.size) out[p++] = b[i].toByte()
        return out
    }

    internal fun deinterleave(stream: ByteArray, lengths: IntArray): List<IntArray> {
        val blocks = lengths.map { IntArray(it) }
        var p = 0
        val maxLen = lengths.max()
        for (i in 0 until maxLen) for (b in blocks) if (i < b.size) b[i] = stream[p++].toInt() and 0xFF
        return blocks
    }

    internal fun bytesToSymbols(bytes: ByteArray, cells: Int, bpc: Int): IntArray {
        val out = IntArray(cells)
        var bitPos = 0
        val totalBits = bytes.size * 8
        for (c in 0 until cells) {
            var v = 0
            for (b in 0 until bpc) {
                val bit = if (bitPos < totalBits) (bytes[bitPos ushr 3].toInt() ushr (7 - (bitPos and 7))) and 1 else 0
                v = (v shl 1) or bit
                bitPos++
            }
            out[c] = v
        }
        return out
    }

    internal fun symbolsToBytes(symbols: IntArray, nBytes: Int, bpc: Int): ByteArray {
        val out = ByteArray(nBytes)
        var bitPos = 0
        val totalBits = nBytes * 8
        for (s in symbols) {
            for (b in bpc - 1 downTo 0) {
                if (bitPos >= totalBits) return out
                if ((s ushr b) and 1 == 1) {
                    val idx = bitPos ushr 3
                    out[idx] = (out[idx].toInt() or (1 shl (7 - (bitPos and 7)))).toByte()
                }
                bitPos++
            }
        }
        return out
    }
}
