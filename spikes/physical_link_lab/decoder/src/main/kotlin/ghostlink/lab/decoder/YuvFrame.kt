package ghostlink.lab.decoder

/**
 * Platform-neutral camera frame in YUV 4:2:0 (the format CameraX ImageAnalysis delivers).
 * Planes are copied once from the platform buffers; strides follow the Android YUV_420_888 layout.
 * The luma plane must hold at least yRowStride * height bytes.
 */
class YuvFrame(
    val width: Int,
    val height: Int,
    val y: ByteArray,
    val yRowStride: Int,
    val u: ByteArray? = null,
    val v: ByteArray? = null,
    val uvRowStride: Int = 0,
    val uvPixelStride: Int = 1,
    /** Sensor timestamp (ns) if known, else 0. */
    val timestampNs: Long = 0,
) {
    init {
        require(width > 0 && height > 0 && yRowStride >= width)
        require(y.size >= yRowStride * height) { "luma plane too small" }
        if (u != null || v != null) {
            require(u != null && v != null && uvPixelStride >= 1 && uvRowStride >= 1)
            val needed = ((height - 1) / 2) * uvRowStride + ((width - 1) / 2) * uvPixelStride + 1
            require(u.size >= needed && v.size >= needed) { "chroma plane too small" }
        }
    }

    val hasChroma: Boolean get() = u != null

    fun luma(x: Int, y: Int): Int = this.y[y * yRowStride + x].toInt() and 0xFF

    /** Bilinear luma with clamping at the borders. */
    fun lumaBilinear(fx: Float, fy: Float): Float {
        val x = fx.coerceIn(0f, (width - 1).toFloat())
        val yy = fy.coerceIn(0f, (height - 1).toFloat())
        val x0 = x.toInt()
        val y0 = yy.toInt()
        val x1 = minOf(x0 + 1, width - 1)
        val y1 = minOf(y0 + 1, height - 1)
        val ax = x - x0
        val ay = yy - y0
        val top = luma(x0, y0) * (1 - ax) + luma(x1, y0) * ax
        val bot = luma(x0, y1) * (1 - ax) + luma(x1, y1) * ax
        return top * (1 - ay) + bot * ay
    }

    /** Packed 0xRRGGBB from BT.601 full-range YUV (camera JFIF convention); grey if no chroma. */
    fun rgb(x: Int, y: Int): Int {
        val l = luma(x, y)
        if (u == null || v == null) return (l shl 16) or (l shl 8) or l
        val ci = (y / 2) * uvRowStride + (x / 2) * uvPixelStride
        val cb = (u[ci].toInt() and 0xFF) - 128
        val cr = (v[ci].toInt() and 0xFF) - 128
        val r = (l + 1.402f * cr).toInt().coerceIn(0, 255)
        val g = (l - 0.344136f * cb - 0.714136f * cr).toInt().coerceIn(0, 255)
        val b = (l + 1.772f * cb).toInt().coerceIn(0, 255)
        return (r shl 16) or (g shl 8) or b
    }

    companion object {
        /** Builds a frame from ARGB pixels (synthetic tests, simulators). Chroma is 4:2:0 averaged. */
        fun fromArgb(width: Int, height: Int, argb: IntArray, withChroma: Boolean = true, timestampNs: Long = 0): YuvFrame {
            require(argb.size == width * height)
            val yPlane = ByteArray(width * height)
            for (i in argb.indices) {
                val c = argb[i]
                val r = (c ushr 16) and 0xFF
                val g = (c ushr 8) and 0xFF
                val b = c and 0xFF
                yPlane[i] = (0.299f * r + 0.587f * g + 0.114f * b + 0.5f).toInt().coerceIn(0, 255).toByte()
            }
            if (!withChroma) return YuvFrame(width, height, yPlane, width, timestampNs = timestampNs)
            val cw = (width + 1) / 2
            val ch = (height + 1) / 2
            val uPlane = ByteArray(cw * ch)
            val vPlane = ByteArray(cw * ch)
            for (cy in 0 until ch) for (cx in 0 until cw) {
                var cb = 0f
                var cr = 0f
                var n = 0
                for (dy in 0..1) for (dx in 0..1) {
                    val px = 2 * cx + dx
                    val py = 2 * cy + dy
                    if (px >= width || py >= height) continue
                    val c = argb[py * width + px]
                    val r = (c ushr 16) and 0xFF
                    val g = (c ushr 8) and 0xFF
                    val b = c and 0xFF
                    cb += -0.168736f * r - 0.331264f * g + 0.5f * b
                    cr += 0.5f * r - 0.418688f * g - 0.081312f * b
                    n++
                }
                uPlane[cy * cw + cx] = (cb / n + 128.5f).toInt().coerceIn(0, 255).toByte()
                vPlane[cy * cw + cx] = (cr / n + 128.5f).toInt().coerceIn(0, 255).toByte()
            }
            return YuvFrame(width, height, yPlane, width, uPlane, vPlane, cw, 1, timestampNs)
        }
    }
}

/** Result of one decoder pass over one camera frame, with per-stage timings for §40. */
class DecodeAttempt(
    val data: ByteArray?,
    val failure: String?,
    /** Nanoseconds spent per stage: binarize/preprocess, detect, sample/classify, error-correction. */
    val preprocessNs: Long = 0,
    val detectNs: Long = 0,
    val sampleNs: Long = 0,
    val eccNs: Long = 0,
    val correctedBytes: Int = 0,
) {
    val totalNs: Long get() = preprocessNs + detectNs + sampleNs + eccNs
    val ok: Boolean get() = data != null

    companion object {
        fun fail(reason: String, preprocessNs: Long = 0, detectNs: Long = 0, sampleNs: Long = 0, eccNs: Long = 0) =
            DecodeAttempt(null, reason, preprocessNs, detectNs, sampleNs, eccNs)
    }
}

/** Image -> frame bytes. Implementations must never throw on bad input. */
interface FrameDecoder {
    val name: String
    fun decode(frame: YuvFrame): DecodeAttempt
}
