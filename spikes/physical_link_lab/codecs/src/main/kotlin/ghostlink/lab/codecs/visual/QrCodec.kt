package ghostlink.lab.codecs.visual

import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.decoder.Version
import com.google.zxing.qrcode.encoder.Encoder

/** Candidate A/B PHY: binary data in QR byte mode via ZXing's encoder (Apache-2.0). */
object QrCodec {
    /** ISO/IEC 18004 quiet zone. */
    const val QUIET_MODULES = 4

    fun zxingLevel(ecc: QrEcc): ErrorCorrectionLevel = when (ecc) {
        QrEcc.L -> ErrorCorrectionLevel.L
        QrEcc.M -> ErrorCorrectionLevel.M
        QrEcc.Q -> ErrorCorrectionLevel.Q
        QrEcc.H -> ErrorCorrectionLevel.H
    }

    /** Max bytes in a single byte-mode segment (no ECI) for the given version/ECC. */
    fun byteCapacity(version: Int, ecc: QrEcc): Int {
        val v = Version.getVersionForNumber(version)
        val ecBlocks = v.getECBlocksForLevel(zxingLevel(ecc))
        val dataCodewords = v.totalCodewords - ecBlocks.totalECCodewords
        val countBits = if (version <= 9) 8 else 16
        return (dataCodewords * 8 - 4 - countBits) / 8
    }

    fun modules(version: Int): Int = 17 + 4 * version

    /**
     * Encodes raw bytes as a QR symbol of exactly [spec].version. Bytes are mapped 1:1 to
     * ISO-8859-1 chars (ZXing's default byte-mode charset, so no ECI segment is added).
     * The lab frame magic byte (0xA7) guarantees byte mode.
     */
    fun encode(data: ByteArray, spec: QrSpec, maskPattern: Int? = null): VisualFrame {
        require(data.size <= spec.frameCapacity()) { "data ${data.size} B exceeds ${spec.key()} capacity ${spec.frameCapacity()} B" }
        val hints = HashMap<EncodeHintType, Any>()
        hints[EncodeHintType.QR_VERSION] = spec.version
        if (maskPattern != null) hints[EncodeHintType.QR_MASK_PATTERN] = maskPattern
        val qr = Encoder.encode(String(data, Charsets.ISO_8859_1), zxingLevel(spec.ecc), hints)
        val m = qr.matrix
        val size = m.width + 2 * QUIET_MODULES
        val argb = IntArray(size * size) { Palette.WHITE }
        for (y in 0 until m.height) for (x in 0 until m.width) {
            if (m.get(x, y).toInt() == 1) argb[(y + QUIET_MODULES) * size + x + QUIET_MODULES] = Palette.BLACK
        }
        return VisualFrame(size, size, argb)
    }
}
