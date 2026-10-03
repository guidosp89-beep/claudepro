package ghostlink.lab.decoder

import com.google.zxing.common.BitMatrix
import kotlin.math.abs
import kotlin.math.hypot

/** A 1:1:3:1:1 finder pattern centre candidate (image coordinates). */
class FinderCandidate(var x: Float, var y: Float, var moduleSize: Float, var count: Int = 1)

/**
 * Locates QR-style finder patterns (dark:light:dark:light:dark = 1:1:3:1:1) in a binarized image.
 * Same principle as ZXing's FinderPatternFinder, re-implemented because the ZXing class only exposes
 * the 3 best patterns and is package-private; GLG grids need all 4 corners.
 */
class FinderDetector(private val rowStep: Int = 2) {

    fun find(img: BitMatrix): List<FinderCandidate> {
        val w = img.width
        val h = img.height
        val found = ArrayList<FinderCandidate>()
        val sc = IntArray(5)
        var y = 0
        while (y < h) {
            sc.fill(0)
            var state = 0
            for (x in 0 until w) {
                if (img.get(x, y)) {
                    if (state and 1 == 1) state++
                    if (state > 4) { shiftTwo(sc); state = 3 }
                    sc[state]++
                } else {
                    if (state and 1 == 0) {
                        if (state == 4) {
                            if (ratioOk(sc)) handle(img, sc, x, y, found)
                            shiftTwo(sc)
                            state = 3
                            sc[3] = 1
                        } else {
                            state++
                            sc[state]++
                        }
                    } else {
                        sc[state]++
                    }
                }
            }
            if (state == 4 && ratioOk(sc)) handle(img, sc, w, y, found)
            y += rowStep
        }
        return found
    }

    private fun shiftTwo(sc: IntArray) {
        sc[0] = sc[2]; sc[1] = sc[3]; sc[2] = sc[4]; sc[3] = 0; sc[4] = 0
    }

    private fun ratioOk(sc: IntArray): Boolean {
        val total = sc.sum()
        if (total < 7) return false
        if (sc.any { it == 0 }) return false
        val m = total / 7f
        val v = m / 2f
        return abs(m - sc[0]) < v && abs(m - sc[1]) < v && abs(3 * m - sc[2]) < 3 * v &&
            abs(m - sc[3]) < v && abs(m - sc[4]) < v
    }

    private fun handle(img: BitMatrix, sc: IntArray, endX: Int, y: Int, found: MutableList<FinderCandidate>) {
        val total = sc.sum()
        val cx = endX - sc[4] - sc[3] - sc[2] / 2f
        val cy = crossCheck(img, cx.toInt(), y, sc[2], total, vertical = true) ?: return
        val cx2 = crossCheck(img, cx.toInt(), cy.toInt(), sc[2], total, vertical = false) ?: return
        val module = total / 7f
        for (c in found) {
            if (abs(c.x - cx2) <= c.moduleSize * 1.5f && abs(c.y - cy) <= c.moduleSize * 1.5f &&
                abs(c.moduleSize - module) <= maxOf(1f, c.moduleSize * 0.5f)
            ) {
                val n = c.count + 1
                c.x = (c.x * c.count + cx2) / n
                c.y = (c.y * c.count + cy) / n
                c.moduleSize = (c.moduleSize * c.count + module) / n
                c.count = n
                return
            }
        }
        found.add(FinderCandidate(cx2, cy, module))
    }

    /** Scans along a column (vertical) or row through (x, y); returns the refined centre coordinate. */
    private fun crossCheck(img: BitMatrix, x: Int, y: Int, centreCount: Int, originalTotal: Int, vertical: Boolean): Float? {
        val limit = if (vertical) img.height else img.width
        val pos0 = if (vertical) y else x
        if (pos0 !in 0 until limit) return null
        fun px(p: Int): Boolean = if (vertical) img.get(x, p) else img.get(p, y)
        val sc = IntArray(5)
        var p = pos0
        while (p >= 0 && px(p)) { sc[2]++; p-- }
        if (p < 0) return null
        while (p >= 0 && !px(p) && sc[1] <= centreCount) { sc[1]++; p-- }
        if (p < 0 || sc[1] > centreCount) return null
        while (p >= 0 && px(p) && sc[0] <= centreCount) { sc[0]++; p-- }
        if (sc[0] > centreCount) return null
        p = pos0 + 1
        while (p < limit && px(p)) { sc[2]++; p++ }
        if (p == limit) return null
        while (p < limit && !px(p) && sc[3] < centreCount) { sc[3]++; p++ }
        if (p == limit || sc[3] >= centreCount) return null
        while (p < limit && px(p) && sc[4] < centreCount) { sc[4]++; p++ }
        if (sc[4] >= centreCount) return null
        val total = sc.sum()
        if (5 * abs(total - originalTotal) >= 2 * originalTotal) return null
        return if (ratioOk(sc)) p - sc[4] - sc[3] - sc[2] / 2f else null
    }

    companion object {
        /**
         * Picks the 4 candidates most likely to be the grid corners: similar module sizes, convex,
         * maximal area. Returns them in clockwise image order (y axis pointing down), or null.
         */
        fun selectCorners(cands: List<FinderCandidate>, minCount: Int = 2): List<FinderCandidate>? {
            val pool = cands.filter { it.count >= minCount }.sortedByDescending { it.count }.take(12)
            if (pool.size < 4) return null
            var best: List<FinderCandidate>? = null
            var bestScore = 0.0
            val n = pool.size
            for (a in 0 until n) for (b in a + 1 until n) for (c in b + 1 until n) for (d in c + 1 until n) {
                val q = listOf(pool[a], pool[b], pool[c], pool[d])
                val ms = q.map { it.moduleSize }
                if (ms.max() > 1.6f * ms.min()) continue
                val ordered = clockwise(q)
                val area = convexArea(ordered) ?: continue
                val side = (0 until 4).minOf { i -> dist(ordered[i], ordered[(i + 1) % 4]) }
                if (side < 10 * ms.average()) continue
                if (area > bestScore) { bestScore = area; best = ordered }
            }
            return best
        }

        fun clockwise(q: List<FinderCandidate>): List<FinderCandidate> {
            val cx = q.map { it.x }.average()
            val cy = q.map { it.y }.average()
            return q.sortedBy { kotlin.math.atan2(it.y - cy, it.x - cx) }
        }

        private fun dist(a: FinderCandidate, b: FinderCandidate): Double = hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble())

        /** Area of the quadrilateral if strictly convex, else null. */
        private fun convexArea(p: List<FinderCandidate>): Double? {
            var sign = 0
            var area = 0.0
            for (i in 0 until 4) {
                val a = p[i]; val b = p[(i + 1) % 4]; val c = p[(i + 2) % 4]
                val cross = (b.x - a.x).toDouble() * (c.y - b.y) - (b.y - a.y).toDouble() * (c.x - b.x)
                val s = if (cross > 0) 1 else if (cross < 0) -1 else 0
                if (s == 0) return null
                if (sign == 0) sign = s else if (s != sign) return null
                area += a.x.toDouble() * b.y - b.x.toDouble() * a.y
            }
            return abs(area) / 2
        }
    }
}
