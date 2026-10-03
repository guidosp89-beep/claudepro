package ghostlink.lab.codecs.visual

import ghostlink.lab.codecs.ByteReader
import ghostlink.lab.codecs.ByteWriter
import ghostlink.lab.codecs.FrameFormatException

/** Parameters of a visual PHY (brief §29: the PHY is independent from the reliability layer). */
sealed interface VisualSpec {
    /** Bytes of LabFrame that fit in one visual frame. */
    fun frameCapacity(): Int
    fun key(): String
    fun encode(): ByteArray

    companion object {
        const val KIND_QR = 1
        const val KIND_GRID = 2

        fun decode(r: ByteReader): VisualSpec = when (val kind = r.u8()) {
            KIND_QR -> {
                val version = r.u8()
                val ecc = r.u8()
                if (version !in 1..40) throw FrameFormatException("bad QR version $version")
                QrSpec(version, QrEcc.fromCode(ecc))
            }
            KIND_GRID -> {
                val cols = r.u16()
                val rows = r.u16()
                val bpc = r.u8()
                val parity = r.u8()
                val fm = r.u8()
                if (cols !in GridSpec.MIN_DIM..GridSpec.MAX_DIM || rows !in GridSpec.MIN_DIM..GridSpec.MAX_DIM) throw FrameFormatException("bad grid dims")
                if (bpc !in 1..3) throw FrameFormatException("bad bits per cell")
                if (parity !in 2..128 || parity % 2 != 0) throw FrameFormatException("bad RS parity")
                if (fm !in 1..4) throw FrameFormatException("bad finder module")
                GridSpec(cols, rows, bpc, parity, fm)
            }
            else -> throw FrameFormatException("unknown visual kind $kind")
        }
    }
}

enum class QrEcc(val code: Int) {
    L(0), M(1), Q(2), H(3);

    companion object {
        fun fromCode(c: Int): QrEcc = entries.firstOrNull { it.code == c } ?: throw FrameFormatException("bad ECC $c")
    }
}

data class QrSpec(val version: Int, val ecc: QrEcc) : VisualSpec {
    init { require(version in 1..40) }
    override fun frameCapacity(): Int = QrCodec.byteCapacity(version, ecc)
    override fun key(): String = "QR-v$version-${ecc.name}"
    override fun encode(): ByteArray = ByteWriter(4).u8(VisualSpec.KIND_QR).u8(version).u8(ecc.code).toByteArray()
}

/**
 * GLG ("GhostLink Grid") custom 2D code: rectangular cell grid with four QR-style finders,
 * black/white orientation keys and palette reference cells, Reed-Solomon over GF(256) per block.
 * bitsPerCell: 1 = black/white, 2 = 4 colours (W,R,G,B), 3 = 8 colours (RGB cube).
 */
data class GridSpec(
    val cols: Int,
    val rows: Int,
    val bitsPerCell: Int,
    val rsParity: Int = 32,
    val finderModule: Int = 1,
) : VisualSpec {
    init {
        require(cols in MIN_DIM..MAX_DIM && rows in MIN_DIM..MAX_DIM) { "grid dims out of range" }
        require(bitsPerCell in 1..3) { "bitsPerCell" }
        require(rsParity in 2..128 && rsParity % 2 == 0) { "rsParity" }
        require(finderModule in 1..4) { "finderModule" }
    }

    override fun frameCapacity(): Int = GridLayout.of(this).frameCapacity
    override fun key(): String = "GRID-${cols}x$rows-b$bitsPerCell-p$rsParity"
    override fun encode(): ByteArray = ByteWriter(8).u8(VisualSpec.KIND_GRID).u16(cols).u16(rows).u8(bitsPerCell)
        .u8(rsParity).u8(finderModule).toByteArray()

    companion object {
        const val MIN_DIM = 24
        const val MAX_DIM = 1024

        /** Rows that fill a screen of the given aspect (height/width), multiple of 4. */
        fun rowsForAspect(cols: Int, aspect: Double, quietCells: Int = Rasterizer.QUIET_CELLS): Int {
            val total = ((cols + 2 * quietCells) * aspect).toInt() - 2 * quietCells
            return ((total / 4) * 4).coerceIn(MIN_DIM, MAX_DIM)
        }
    }
}
