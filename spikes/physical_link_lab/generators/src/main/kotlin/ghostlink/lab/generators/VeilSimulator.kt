package ghostlink.lab.generators

import ghostlink.lab.decoder.YuvFrame
import kotlin.math.log10
import kotlin.math.sin
import kotlin.random.Random

/**
 * Candidate G feasibility: complementary-frame modulation (InFrame/HiLight style). Frame A = I + sΔ,
 * frame B = I − sΔ per block (s = ±1 per bit), alternated every refresh, so the eye integrates I.
 * The receiver needs one capture of A and one of B and decodes sign(mean_A − mean_B) per block.
 * Geometry is assumed known (best case: in a product it would need sync markers too).
 */
object VeilSimulator {
    enum class Channel { LUMA, CHROMA }

    data class Result(
        val deltaLevels: Int, val blockPx: Int, val channel: Channel, val distanceCm: Double, val exposureMix: Double,
        val ber: Double, val psnrSingleFrameDb: Double, val bitsPerFramePair: Int,
    )

    /** Procedural "photo": smooth gradients, soft blobs and fine texture (no dataset needed). */
    fun naturalImage(w: Int, h: Int, seed: Long = 7): IntArray {
        val rnd = Random(seed)
        val blobs = List(12) { Triple(rnd.nextDouble() * w, rnd.nextDouble() * h, 40 + rnd.nextDouble() * 200) }
        val cols = List(12) { Triple(rnd.nextInt(40, 230), rnd.nextInt(40, 230), rnd.nextInt(40, 230)) }
        return IntArray(w * h) { i ->
            val x = i % w; val y = i / w
            var r = 90.0 + 60 * x / w; var g = 110.0 + 50 * y / h; var b = 140.0 - 40 * x / w
            for ((k, bl) in blobs.withIndex()) {
                val d2 = ((x - bl.first) * (x - bl.first) + (y - bl.second) * (y - bl.second)) / (bl.third * bl.third)
                val wgt = kotlin.math.exp(-d2)
                r += (cols[k].first - r) * wgt * 0.6; g += (cols[k].second - g) * wgt * 0.6; b += (cols[k].third - b) * wgt * 0.6
            }
            val tex = 6 * sin(x * 0.31) * sin(y * 0.27) + rnd.nextDouble(-3.0, 3.0)
            val R = (r + tex).toInt().coerceIn(0, 255); val G = (g + tex).toInt().coerceIn(0, 255); val B = (b + tex).toInt().coerceIn(0, 255)
            (0xFF shl 24) or (R shl 16) or (G shl 8) or B
        }
    }

    fun run(
        sim: CameraSimulator, deltaLevels: Int, blockPx: Int, channel: Channel, distanceCm: Double,
        /** Fraction of the exposure that integrates the *other* frame (0 = clean capture). */
        exposureMix: Double = 0.0, seed: Long = 3,
    ): Result {
        val sw = sim.screen.widthPx; val sh = sim.screen.heightPx
        val base = naturalImage(sw, sh, seed)
        val bw = sw / blockPx; val bh = sh / blockPx
        val rnd = Random(seed * 31)
        val bits = IntArray(bw * bh) { if (rnd.nextBoolean()) 1 else -1 }
        fun frame(sign: Int): IntArray = IntArray(sw * sh) { i ->
            val x = i % sw; val y = i / sw
            val bx = x / blockPx; val by = y / blockPx
            if (bx >= bw || by >= bh) return@IntArray base[i]
            val s = sign * bits[by * bw + bx] * deltaLevels
            val c = base[i]
            var r = (c ushr 16) and 0xFF; var g = (c ushr 8) and 0xFF; var b = c and 0xFF
            when (channel) {
                Channel.LUMA -> { r += s; g += s; b += s }
                Channel.CHROMA -> { r += s; b -= s } // along Cr/−Cb: little luminance change
            }
            (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
        }
        val a = frame(+1); val b = frame(-1)
        fun mix(p: IntArray, q: IntArray, m: Double) = if (m <= 0) p else IntArray(p.size) { i ->
            fun ch(c: Int, sh: Int) = (((p[i] ushr sh) and 0xFF) * (1 - m) + ((q[i] ushr sh) and 0xFF) * m).toInt()
            (0xFF shl 24) or (ch(p[i], 16) shl 16) or (ch(p[i], 8) shl 8) or ch(p[i], 0)
        }
        val scene = Scene(distanceCm = distanceCm, seed = seed)
        val ca = sim.capture(mix(a, b, exposureMix), scene)
        val cb = sim.capture(mix(b, a, exposureMix), scene.copy(seed = seed + 1))
        // Project block centres with the simulator's own geometry (perfect sync/geometry assumption).
        var errors = 0; var total = 0
        val f = sim.camera.focalPx; val d = distanceCm * 10
        val cx = sim.camera.width / 2.0; val cy = sim.camera.height / 2.0
        for (by in 0 until bh) for (bx in 0 until bw) {
            val sxMm = (by + 0.5) * blockPx * sim.screen.heightMm / sh - sim.screen.heightMm / 2
            val syMm = (bx + 0.5) * blockPx * sim.screen.widthMm / sw - sim.screen.widthMm / 2
            val u = (cx + f * sxMm / d).toInt(); val v = (cy - f * syMm / d).toInt()
            val rad = ((blockPx * sim.screen.widthMm / sw) * f / d / 2.5).toInt().coerceAtLeast(0)
            if (u - rad < 0 || v - rad < 0 || u + rad >= sim.camera.width || v + rad >= sim.camera.height) continue
            fun meas(fr: YuvFrame): Double {
                var acc = 0.0; var n = 0
                for (yy in v - rad..v + rad) for (xx in u - rad..u + rad) {
                    acc += if (channel == Channel.LUMA) fr.luma(xx, yy).toDouble()
                    else { val c = fr.rgb(xx, yy); ((c ushr 16) and 0xFF) - (c and 0xFF).toDouble() }
                    n++
                }
                return acc / n
            }
            val decoded = if (meas(ca) - meas(cb) >= 0) 1 else -1
            total++
            if (decoded != bits[by * bw + bx]) errors++
        }
        // PSNR of a single displayed frame vs the original (what a camera or a slow eye could see).
        var se = 0.0
        for (i in base.indices) for (sh2 in intArrayOf(16, 8, 0)) {
            val e = ((a[i] ushr sh2) and 0xFF) - ((base[i] ushr sh2) and 0xFF); se += e * e
        }
        val mse = se / (base.size * 3)
        val psnr = if (mse == 0.0) 99.0 else 10 * log10(255.0 * 255.0 / mse)
        return Result(deltaLevels, blockPx, channel, distanceCm, exposureMix, if (total == 0) 1.0 else errors.toDouble() / total, psnr, total)
    }
}
