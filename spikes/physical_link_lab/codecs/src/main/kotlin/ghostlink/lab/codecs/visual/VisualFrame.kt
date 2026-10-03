package ghostlink.lab.codecs.visual

/**
 * One displayable visual symbol at *module/cell resolution* (1 entry = 1 QR module or grid cell),
 * quiet zone included. Renderers scale it with nearest-neighbour to the screen.
 */
class VisualFrame(val width: Int, val height: Int, val argb: IntArray) {
    init { require(width > 0 && height > 0 && argb.size == width * height) }
    operator fun get(x: Int, y: Int): Int = argb[y * width + x]
}

object Palette {
    const val WHITE = 0xFFFFFFFF.toInt()
    const val BLACK = 0xFF000000.toInt()

    /** Index = symbol value. 1 bit: 0 = white, 1 = black (dark = 1, as in QR). */
    val BW = intArrayOf(WHITE, BLACK)

    /** 2 bits: white, red, green, blue — every pair differs in two RGB channels. */
    val C4 = intArrayOf(WHITE, 0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt())

    /** 3 bits: RGB cube, bit2 = R, bit1 = G, bit0 = B (one-channel confusion = one bit error). */
    val C8 = IntArray(8) { v ->
        val r = if (v and 4 != 0) 0xFF else 0
        val g = if (v and 2 != 0) 0xFF else 0
        val b = if (v and 1 != 0) 0xFF else 0
        (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    fun forBits(bitsPerCell: Int): IntArray = when (bitsPerCell) {
        1 -> BW
        2 -> C4
        3 -> C8
        else -> throw IllegalArgumentException("bitsPerCell $bitsPerCell")
    }
}

/** Scales a [VisualFrame] onto a screen-sized pixel buffer (white background, centred, integer scale). */
object Rasterizer {
    /** Quiet zone added around grid codes (QR uses its own 4-module zone). */
    const val QUIET_CELLS = 2

    fun scaleFor(frame: VisualFrame, screenW: Int, screenH: Int): Int =
        minOf(screenW / frame.width, screenH / frame.height).coerceAtLeast(1)

    fun toScreen(frame: VisualFrame, screenW: Int, screenH: Int): IntArray {
        val s = scaleFor(frame, screenW, screenH)
        val out = IntArray(screenW * screenH) { Palette.WHITE }
        val ox = (screenW - frame.width * s) / 2
        val oy = (screenH - frame.height * s) / 2
        for (fy in 0 until frame.height) {
            for (fx in 0 until frame.width) {
                val c = frame[fx, fy]
                if (c == Palette.WHITE) continue
                val x0 = ox + fx * s
                val y0 = oy + fy * s
                for (yy in y0 until minOf(y0 + s, screenH)) {
                    if (yy < 0) continue
                    val row = yy * screenW
                    for (xx in maxOf(x0, 0) until minOf(x0 + s, screenW)) out[row + xx] = c
                }
            }
        }
        return out
    }
}
