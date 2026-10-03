package ghostlink.lab.decoder

import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.common.PerspectiveTransform
import ghostlink.lab.codecs.visual.GridCodec
import ghostlink.lab.codecs.visual.GridLayout
import ghostlink.lab.codecs.visual.GridSpec

/**
 * Camera image -> GLG grid bytes (Candidates C/D):
 * binarize (ZXing HybridBinarizer) -> 4 finders -> homography (ZXing PerspectiveTransform) ->
 * orientation key -> palette references (interpolated top/bottom) -> per-cell classification ->
 * de-whiten / de-interleave / Reed-Solomon ([GridCodec]).
 */
class GridImageDecoder(val spec: GridSpec) : FrameDecoder {
    override val name: String = "glg-${spec.bitsPerCell}b"
    private val layout = GridLayout.of(spec)
    private val detector = FinderDetector()
    private val gridCorners = layout.finderCenters()

    /** Diagnostics of the last call (orientation score, estimated cell size in camera pixels). */
    var lastKeyScore = 0
        private set
    var lastCellPx = 0f
        private set

    override fun decode(frame: YuvFrame): DecodeAttempt {
        val t0 = System.nanoTime()
        val bits = try {
            HybridBinarizer(PlanarYUVLuminanceSource(frame.y, frame.yRowStride, frame.height, 0, 0, frame.width, frame.height, false)).blackMatrix
        } catch (e: NotFoundException) {
            return DecodeAttempt.fail("binarize", System.nanoTime() - t0)
        } catch (e: RuntimeException) {
            return DecodeAttempt.fail("binarize:${e::class.simpleName}", System.nanoTime() - t0)
        }
        val t1 = System.nanoTime()
        val corners = FinderDetector.selectCorners(detector.find(bits))
            ?: return DecodeAttempt.fail("finders", t1 - t0, System.nanoTime() - t1)
        lastCellPx = corners.map { it.moduleSize }.average().toFloat() / spec.finderModule

        var best: PerspectiveTransform? = null
        var bestScore = -1
        for (r in 0 until 4) {
            val p = List(4) { corners[(r + it) % 4] }
            val t = PerspectiveTransform.quadrilateralToQuadrilateral(
                gridCorners[0][0], gridCorners[0][1], gridCorners[1][0], gridCorners[1][1],
                gridCorners[2][0], gridCorners[2][1], gridCorners[3][0], gridCorners[3][1],
                p[0].x, p[0].y, p[1].x, p[1].y, p[2].x, p[2].y, p[3].x, p[3].y,
            )
            val score = keyScore(frame, t)
            if (score > bestScore) { bestScore = score; best = t }
        }
        lastKeyScore = bestScore
        val t2 = System.nanoTime()
        if (best == null || bestScore < KEY_THRESHOLD) return DecodeAttempt.fail("orientation", t1 - t0, t2 - t1)

        val symbols = try {
            classify(frame, best)
        } catch (e: RuntimeException) {
            return DecodeAttempt.fail("sample:${e::class.simpleName}", t1 - t0, t2 - t1, System.nanoTime() - t2)
        }
        val t3 = System.nanoTime()
        val out = GridCodec.decodeSymbols(symbols, layout)
        val t4 = System.nanoTime()
        return if (out.data != null) {
            DecodeAttempt(out.data, null, t1 - t0, t2 - t1, t3 - t2, t4 - t3, out.correctedBytes)
        } else {
            DecodeAttempt.fail(if (out.failedBlocks > 0) "rs:${out.failedBlocks}" else "length", t1 - t0, t2 - t1, t3 - t2, t4 - t3)
        }
    }

    private fun map(t: PerspectiveTransform, gx: Float, gy: Float, buf: FloatArray): FloatArray {
        buf[0] = gx; buf[1] = gy
        t.transformPoints(buf)
        return buf
    }

    private fun inside(frame: YuvFrame, p: FloatArray) = p[0] >= 0 && p[1] >= 0 && p[0] < frame.width && p[1] < frame.height

    private fun keyScore(frame: YuvFrame, t: PerspectiveTransform): Int {
        val buf = FloatArray(2)
        val values = FloatArray(2 * GridLayout.KEY_BITS)
        var i = 0
        for ((x, y) in layout.keyTop + layout.keyBottom) {
            map(t, x + 0.5f, y + 0.5f, buf)
            if (!inside(frame, buf)) return 0
            values[i++] = frame.lumaBilinear(buf[0], buf[1])
        }
        val threshold = values.average().toFloat()
        var score = 0
        for (k in 0 until GridLayout.KEY_BITS) {
            if ((values[k] < threshold) == (GridLayout.KEY_TOP[k] == 1)) score++
            if ((values[GridLayout.KEY_BITS + k] < threshold) == (GridLayout.KEY_BOTTOM[k] == 1)) score++
        }
        return score
    }

    /** Average of a small pixel neighbourhood when cells are large enough, else the centre pixel. */
    private fun sampleLuma(frame: YuvFrame, p: FloatArray): Float {
        if (lastCellPx < 3f) return frame.lumaBilinear(p[0], p[1])
        val cx = p[0].toInt().coerceIn(1, frame.width - 2)
        val cy = p[1].toInt().coerceIn(1, frame.height - 2)
        var s = 0
        for (dy in -1..1) for (dx in -1..1) s += frame.luma(cx + dx, cy + dy)
        return s / 9f
    }

    private fun sampleRgb(frame: YuvFrame, p: FloatArray, out: FloatArray) {
        val cx = p[0].toInt().coerceIn(1, frame.width - 2)
        val cy = p[1].toInt().coerceIn(1, frame.height - 2)
        val r = if (lastCellPx >= 3f) 1 else 0
        var sr = 0; var sg = 0; var sb = 0; var n = 0
        for (dy in -r..r) for (dx in -r..r) {
            val c = frame.rgb(cx + dx, cy + dy)
            sr += (c ushr 16) and 0xFF; sg += (c ushr 8) and 0xFF; sb += c and 0xFF; n++
        }
        out[0] = sr.toFloat() / n; out[1] = sg.toFloat() / n; out[2] = sb.toFloat() / n
    }

    private fun classify(frame: YuvFrame, t: PerspectiveTransform): IntArray {
        val buf = FloatArray(2)
        val nColors = layout.palette.size
        val bw = spec.bitsPerCell == 1
        val channels = if (bw) 1 else 3
        // Reference means per colour, top and bottom rows.
        val top = Array(nColors) { FloatArray(channels) }
        val bottom = Array(nColors) { FloatArray(channels) }
        val topN = IntArray(nColors)
        val bottomN = IntArray(nColors)
        val rgb = FloatArray(3)
        fun accumulate(refs: List<Triple<Int, Int, Int>>, acc: Array<FloatArray>, cnt: IntArray) {
            for ((x, y, c) in refs) {
                map(t, x + 0.5f, y + 0.5f, buf)
                if (!inside(frame, buf)) continue
                if (bw) acc[c][0] += sampleLuma(frame, buf) else { sampleRgb(frame, buf, rgb); for (k in 0..2) acc[c][k] += rgb[k] }
                cnt[c]++
            }
            for (c in 0 until nColors) if (cnt[c] > 0) for (k in 0 until channels) acc[c][k] /= cnt[c]
        }
        accumulate(layout.refTop, top, topN)
        accumulate(layout.refBottom, bottom, bottomN)
        require(topN.all { it > 0 } && bottomN.all { it > 0 }) { "references outside image" }

        val symbols = IntArray(layout.dataX.size)
        val ref = Array(nColors) { FloatArray(channels) }
        var lastRow = -1
        val span = (layout.rows - 1).toFloat()
        for (i in symbols.indices) {
            val gx = layout.dataX[i]
            val gy = layout.dataY[i]
            if (gy != lastRow) {
                val w = gy / span
                for (c in 0 until nColors) for (k in 0 until channels) ref[c][k] = top[c][k] * (1 - w) + bottom[c][k] * w
                lastRow = gy
            }
            map(t, gx + 0.5f, gy + 0.5f, buf)
            if (bw) {
                val l = sampleLuma(frame, buf)
                // palette index 1 = black
                symbols[i] = if (l < (ref[0][0] + ref[1][0]) / 2f) 1 else 0
            } else {
                sampleRgb(frame, buf, rgb)
                var bestC = 0
                var bestD = Float.MAX_VALUE
                for (c in 0 until nColors) {
                    val dr = rgb[0] - ref[c][0]; val dg = rgb[1] - ref[c][1]; val db = rgb[2] - ref[c][2]
                    val d = dr * dr + dg * dg + db * db
                    if (d < bestD) { bestD = d; bestC = c }
                }
                symbols[i] = bestC
            }
        }
        return symbols
    }

    companion object {
        /** Minimum orientation-key agreement out of 32 cells. */
        const val KEY_THRESHOLD = 27
    }
}
