package ghostlink.lab.codecs.visual

import java.util.concurrent.ConcurrentHashMap

/**
 * Geometry of a GLG grid (shared by encoder and image decoder). Grid coordinates are in cells,
 * origin at the top-left cell of the grid (quiet zone excluded); a cell (x,y) spans [x,x+1)×[y,y+1).
 *
 * ```
 *  ┌────────┬──── key(16) + refs ────┬────────┐  row 0
 *  │ finder │        data            │ finder │
 *  ├────────┘                        └────────┤
 *  │                 data                     │
 *  ├────────┐                        ┌────────┤
 *  │ finder │        data            │ finder │
 *  └────────┴──── key(16) + refs ────┴────────┘  row rows-1
 * ```
 */
class GridLayout private constructor(val spec: GridSpec) {
    val cols = spec.cols
    val rows = spec.rows
    val finderCells = 7 * spec.finderModule
    /** Finder + separator. */
    val reserved = finderCells + spec.finderModule
    val palette: IntArray = Palette.forBits(spec.bitsPerCell)
    val bandLength = cols - 2 * reserved

    val keyTop: List<Pair<Int, Int>>
    val keyBottom: List<Pair<Int, Int>>
    /** Triples (x, y, paletteIndex). */
    val refTop: List<Triple<Int, Int, Int>>
    val refBottom: List<Triple<Int, Int, Int>>
    val dataX: IntArray
    val dataY: IntArray
    val blockLengths: IntArray
    val totalCodewords: Int
    val dataCapacity: Int
    /** LabFrame bytes per grid frame (2-byte length prefix removed). */
    val frameCapacity: Int

    init {
        require(bandLength >= KEY_BITS + 2 * palette.size) {
            "grid ${spec.key()} too narrow for key+references (band $bandLength)"
        }
        require(rows >= 2 * reserved + 4) { "grid too short" }
        keyTop = (0 until KEY_BITS).map { (reserved + it) to 0 }
        keyBottom = (0 until KEY_BITS).map { (reserved + it) to (rows - 1) }
        val refCount = bandLength - KEY_BITS
        refTop = (0 until refCount).map { Triple(reserved + KEY_BITS + it, 0, (it / 2) % palette.size) }
        refBottom = (0 until refCount).map { Triple(reserved + KEY_BITS + it, rows - 1, (it / 2) % palette.size) }

        val xs = ArrayList<Int>()
        val ys = ArrayList<Int>()
        for (y in 0 until rows) for (x in 0 until cols) {
            if (isReservedCorner(x, y)) continue
            if (y == 0 || y == rows - 1) continue // key + reference rows (corners already excluded)
            xs.add(x); ys.add(y)
        }
        dataX = xs.toIntArray()
        dataY = ys.toIntArray()
        totalCodewords = dataX.size * spec.bitsPerCell / 8
        val nb = (totalCodewords + 254) / 255
        val base = totalCodewords / nb
        val extra = totalCodewords % nb
        blockLengths = IntArray(nb) { if (it < extra) base + 1 else base }
        require(blockLengths.all { it > spec.rsParity + 2 }) { "RS parity too large for block size" }
        dataCapacity = blockLengths.sumOf { it - spec.rsParity }
        frameCapacity = dataCapacity - LENGTH_PREFIX
    }

    fun isReservedCorner(x: Int, y: Int): Boolean {
        val left = x < reserved
        val right = x >= cols - reserved
        val top = y < reserved
        val bottom = y >= rows - reserved
        return (left || right) && (top || bottom)
    }

    /** Whether cell (x,y) is dark in the finder pattern of the corner containing it (false outside finders). */
    fun finderDark(x: Int, y: Int): Boolean {
        val fx = if (x < reserved) x else x - (cols - finderCells)
        val fy = if (y < reserved) y else y - (rows - finderCells)
        if (fx !in 0 until finderCells || fy !in 0 until finderCells) return false
        val mx = fx / spec.finderModule
        val my = fy / spec.finderModule
        val ring = minOf(mx, my, 6 - mx, 6 - my)
        return ring != 1
    }

    /** Finder centres in grid coordinates: TL, TR, BR, BL. */
    fun finderCenters(): Array<FloatArray> {
        val h = finderCells / 2f
        return arrayOf(
            floatArrayOf(h, h),
            floatArrayOf(cols - h, h),
            floatArrayOf(cols - h, rows - h),
            floatArrayOf(h, rows - h),
        )
    }

    companion object {
        const val KEY_BITS = 16
        const val LENGTH_PREFIX = 2
        /** Orientation keys (8 ones each); a 180° rotation matches at most ~19/32 cells. */
        val KEY_TOP = intArrayOf(1, 1, 0, 1, 0, 0, 1, 0, 1, 1, 1, 0, 0, 0, 1, 0)
        val KEY_BOTTOM = intArrayOf(0, 0, 1, 1, 0, 1, 1, 0, 0, 1, 0, 1, 1, 0, 0, 1)

        private val cache = ConcurrentHashMap<GridSpec, GridLayout>()
        fun of(spec: GridSpec): GridLayout = cache.getOrPut(spec) { GridLayout(spec) }
    }
}
