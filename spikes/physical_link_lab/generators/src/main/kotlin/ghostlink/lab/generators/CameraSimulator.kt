package ghostlink.lab.generators

import ghostlink.lab.decoder.YuvFrame
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.random.Random

/**
 * Synthetic "phone screen -> phone camera" channel for cloud pre-screening (brief §70).
 * It is a *model*, not a substitute for physical tests: every number derived from it is labelled
 * SIMULATED. Geometry is a pinhole projection with real phone dimensions, so distance effects
 * (pixels per cell) are physically grounded; photometry/noise parameters are plausible guesses.
 */
data class ScreenModel(
    val widthPx: Int = 1080,
    val heightPx: Int = 2400,
    val widthMm: Double = 68.0,
    val heightMm: Double = 151.0,
)

data class CameraModel(
    /** Analysis buffer (landscape sensor orientation). */
    val width: Int = 1920,
    val height: Int = 1080,
    /** Horizontal field of view of the main camera along the long axis (≈ 26 mm-equivalent lens). */
    val hfovDeg: Double = 69.0,
    val opticsBlurSigmaPx: Double = 0.6,
    val readNoise: Double = 1.5,
    val shotNoiseGain: Double = 0.06,
    /** 3x3 colour leakage left by imperfect colour correction (fraction to each other channel). */
    val colourLeak: Double = 0.06,
) {
    val focalPx: Double get() = (width / 2.0) / tan(hfovDeg / 2 * PI / 180)
}

enum class AmbientClass(val reflect: Double, val whiteLevel: Double, val label: String) {
    /** AE meters a dark room: the screen is overexposed (whites clip, colours desaturate). */
    INDOOR_DIM(0.01, 1.35, "INDOOR_DIM"),
    INDOOR_NORMAL(0.04, 0.95, "INDOOR_NORMAL"),
    INDOOR_BRIGHT(0.10, 0.85, "INDOOR_BRIGHT"),
    /** Diffuse daylight reflected by the glass lifts blacks substantially. */
    OUTDOOR_SHADE(0.30, 0.80, "OUTDOOR_SHADE"),
}

data class Scene(
    val distanceCm: Double = 40.0,
    /** Viewing angle: screen rotated about its short axis (perspective along the long axis). */
    val angleDeg: Double = 0.0,
    /** In-plane rotation of the screen in the image. */
    val rollDeg: Double = 0.0,
    /** Lateral offset of the screen centre from the optical axis (mm). */
    val offsetMm: Pair<Double, Double> = 0.0 to 0.0,
    val ambient: AmbientClass = AmbientClass.INDOOR_NORMAL,
    val zoom: Double = 1.0,
    val motionBlurPx: Double = 0.0,
    val extraBlurSigmaPx: Double = 0.0,
    val jpegQuality: Int? = null,
    /** Fraction of camera rows (from top) that see the *next* frame: rolling-shutter mixing. */
    val mixedFraction: Double = 0.0,
    val occlusion: Boolean = false,
    val glare: Boolean = false,
    /** White-balance error, multiplies R and B. */
    val wbError: Double = 0.0,
    val seed: Long = 1,
)

class CameraSimulator(val screen: ScreenModel = ScreenModel(), val camera: CameraModel = CameraModel()) {

    /**
     * Renders what the camera sees. [screenA] (and [screenB] for rolling-shutter mixing) are full
     * screen ARGB buffers (screen.widthPx × screen.heightPx).
     */
    fun capture(screenA: IntArray, scene: Scene, screenB: IntArray? = null): YuvFrame {
        require(screenA.size == screen.widthPx * screen.heightPx)
        val rnd = Random(scene.seed)
        val w = camera.width
        val h = camera.height
        val f = camera.focalPx * scene.zoom
        val cx = w / 2.0 + rnd.nextDouble(-2.0, 2.0)
        val cy = h / 2.0 + rnd.nextDouble(-2.0, 2.0)
        val d = scene.distanceCm * 10.0
        val th = scene.angleDeg * PI / 180
        val cosT = cos(th)
        val sinT = sin(th)
        val roll = scene.rollDeg * PI / 180
        val cr = cos(roll)
        val sr = sin(roll)
        val mmPerPxX = screen.widthMm / screen.widthPx
        val mmPerPxY = screen.heightMm / screen.heightPx
        val linA = FloatArray(screenA.size * 3).also { toLinear(screenA, it) }
        val linB = screenB?.let { FloatArray(it.size * 3).also { a -> toLinear(it, a) } }
        val splitRow = (h * scene.mixedFraction).roundToInt()

        // Linear radiance image (R,G,B planes), environment = dim grey texture.
        val img = Array(3) { FloatArray(w * h) }
        val env = 0.06f + scene.ambient.reflect.toFloat() * 0.5f
        for (i in 0 until w * h) { img[0][i] = env; img[1][i] = env; img[2][i] = env }

        // Bounding box of the screen in the image (project corners), plus a bezel margin.
        val corners = listOf(-1.0 to -1.0, 1.0 to -1.0, 1.0 to 1.0, -1.0 to 1.0).map { (a, b) ->
            val x = a * (screen.heightMm / 2 + 4) + scene.offsetMm.first
            val y = b * (screen.widthMm / 2 + 4) + scene.offsetMm.second
            val z = d + x * sinT
            val u0 = f * x * cosT / z
            val v0 = f * y / z
            (cx + u0 * cr - v0 * sr) to (cy + u0 * sr + v0 * cr)
        }
        val u0 = max(0, corners.minOf { it.first }.toInt() - 2)
        val u1 = min(w - 1, corners.maxOf { it.first }.toInt() + 2)
        val v0 = max(0, corners.minOf { it.second }.toInt() - 2)
        val v1 = min(h - 1, corners.maxOf { it.second }.toInt() + 2)

        val ss = SUPERSAMPLE
        val reflect = scene.ambient.reflect.toFloat()
        for (v in v0..v1) for (u in u0..u1) {
            val src = if (linB != null && v < splitRow) linB else linA
            var r = 0f; var g = 0f; var b = 0f; var n = 0; var onScreen = false
            for (sy in 0 until ss) for (sx in 0 until ss) {
                val pu = u + (sx + 0.5) / ss - 0.5 - cx
                val pv = v + (sy + 0.5) / ss - 0.5 - cy
                // undo roll
                val qu = pu * cr + pv * sr
                val qv = -pu * sr + pv * cr
                val a = qu / f
                val den = cosT - a * sinT
                if (den <= 1e-6) continue
                val xMm = a * d / den
                val z = d + xMm * sinT
                val yMm = qv / f * z
                val sxMm = xMm - scene.offsetMm.first + screen.heightMm / 2   // along screen height
                // minus sign: the screen appears rotated by 90° in the landscape sensor buffer, never mirrored
                val syMm = -(yMm - scene.offsetMm.second) + screen.widthMm / 2   // along screen width
                n++
                if (sxMm < -4 || sxMm > screen.heightMm + 4 || syMm < -4 || syMm > screen.widthMm + 4) {
                    r += env; g += env; b += env; continue
                }
                onScreen = true
                val px = (syMm / mmPerPxX).toInt()
                val py = (sxMm / mmPerPxY).toInt()
                if (px < 0 || py < 0 || px >= screen.widthPx || py >= screen.heightPx) {
                    r += 0.01f + reflect; g += 0.01f + reflect; b += 0.01f + reflect // black bezel
                    continue
                }
                // Lambertian-ish fall-off with viewing angle for the emissive panel.
                val k = 3 * (py * screen.widthPx + px)
                val angular = cosT.toFloat()
                r += src[k] * angular + reflect; g += src[k + 1] * angular + reflect; b += src[k + 2] * angular + reflect
            }
            if (!onScreen || n == 0) continue
            val idx = v * w + u
            img[0][idx] = r / n; img[1][idx] = g / n; img[2][idx] = b / n
        }

        if (scene.glare) addGlare(img, w, h, (u0 + u1) / 2, (v0 + v1) / 2, (u1 - u0) / 6, rnd)
        if (scene.occlusion) addOcclusion(img, w, h, u0, v0, u1, v1, rnd)

        val sigma = sqrt(camera.opticsBlurSigmaPx.pow(2) + scene.extraBlurSigmaPx.pow(2))
        for (c in 0..2) {
            if (sigma > 0.05) gaussianBlur(img[c], w, h, sigma, u0, v0, u1, v1)
            if (scene.motionBlurPx >= 1) motionBlur(img[c], w, h, scene.motionBlurPx, rnd.nextDouble(0.0, PI), u0, v0, u1, v1)
        }

        // Exposure, colour correction leak, white balance error, tone curve, noise, 8-bit.
        val gain = scene.ambient.whiteLevel.toFloat()
        val leak = camera.colourLeak.toFloat()
        val wbR = (1 + scene.wbError).toFloat()
        val wbB = (1 - scene.wbError).toFloat()
        val out = IntArray(w * h)
        for (i in 0 until w * h) {
            val r0 = img[0][i]; val g0 = img[1][i]; val b0 = img[2][i]
            val r = (r0 * (1 - 2 * leak) + (g0 + b0) * leak) * gain * wbR
            val g = (g0 * (1 - 2 * leak) + (r0 + b0) * leak) * gain
            val b = (b0 * (1 - 2 * leak) + (r0 + g0) * leak) * gain * wbB
            out[i] = (0xFF shl 24) or (encode(r, rnd) shl 16) or (encode(g, rnd) shl 8) or encode(b, rnd)
        }
        val argb = scene.jpegQuality?.let { jpegRoundTrip(out, w, h, it) } ?: out
        return YuvFrame.fromArgb(w, h, argb)
    }

    private fun encode(lin: Float, rnd: Random): Int {
        val v = 255.0 * lin.coerceIn(0f, 1f).toDouble().pow(1 / 2.2)
        val sigma = sqrt(camera.readNoise * camera.readNoise + camera.shotNoiseGain * v)
        return (v + gaussian(rnd) * sigma).roundToInt().coerceIn(0, 255)
    }

    companion object {
        const val SUPERSAMPLE = 3

        fun toLinear(argb: IntArray, out: FloatArray) {
            val lut = FloatArray(256) { (it / 255.0).pow(2.2).toFloat() }
            for (i in argb.indices) {
                val c = argb[i]
                out[3 * i] = lut[(c ushr 16) and 0xFF]; out[3 * i + 1] = lut[(c ushr 8) and 0xFF]; out[3 * i + 2] = lut[c and 0xFF]
            }
        }

        fun gaussian(rnd: Random): Double {
            var u = 0.0
            while (u == 0.0) u = rnd.nextDouble()
            return sqrt(-2 * kotlin.math.ln(u)) * cos(2 * PI * rnd.nextDouble())
        }

        fun gaussianBlur(p: FloatArray, w: Int, h: Int, sigma: Double, u0: Int, v0: Int, u1: Int, v1: Int) {
            val r = (3 * sigma).toInt().coerceAtLeast(1)
            val k = FloatArray(2 * r + 1) { exp(-((it - r) * (it - r)) / (2 * sigma * sigma)).toFloat() }
            val s = k.sum(); for (i in k.indices) k[i] /= s
            val tmp = p.copyOf()
            val ua = max(0, u0 - r); val ub = min(w - 1, u1 + r); val va = max(0, v0 - r); val vb = min(h - 1, v1 + r)
            for (y in va..vb) for (x in ua..ub) {
                var acc = 0f
                for (i in -r..r) acc += p[y * w + (x + i).coerceIn(0, w - 1)] * k[i + r]
                tmp[y * w + x] = acc
            }
            for (y in va..vb) for (x in ua..ub) {
                var acc = 0f
                for (i in -r..r) acc += tmp[(y + i).coerceIn(0, h - 1) * w + x] * k[i + r]
                p[y * w + x] = acc
            }
        }

        fun motionBlur(p: FloatArray, w: Int, h: Int, len: Double, angle: Double, u0: Int, v0: Int, u1: Int, v1: Int) {
            val n = len.roundToInt().coerceAtLeast(2)
            val dx = cos(angle); val dy = sin(angle)
            val src = p.copyOf()
            for (y in max(0, v0 - n)..min(h - 1, v1 + n)) for (x in max(0, u0 - n)..min(w - 1, u1 + n)) {
                var acc = 0f
                for (i in 0 until n) {
                    val t = i - (n - 1) / 2.0
                    val sx = (x + t * dx).roundToInt().coerceIn(0, w - 1)
                    val sy = (y + t * dy).roundToInt().coerceIn(0, h - 1)
                    acc += src[sy * w + sx]
                }
                p[y * w + x] = acc / n
            }
        }

        private fun addGlare(img: Array<FloatArray>, w: Int, h: Int, cu: Int, cv: Int, radius: Int, rnd: Random) {
            val ou = cu + rnd.nextInt(-radius, radius + 1)
            val ov = cv + rnd.nextInt(-radius, radius + 1)
            val r = max(4, radius)
            for (y in max(0, ov - 2 * r)..min(h - 1, ov + 2 * r)) for (x in max(0, ou - 2 * r)..min(w - 1, ou + 2 * r)) {
                val dd = ((x - ou) * (x - ou) + (y - ov) * (y - ov)).toDouble() / (r * r)
                val add = (0.8 * exp(-dd)).toFloat()
                for (c in 0..2) img[c][y * w + x] += add
            }
        }

        private fun addOcclusion(img: Array<FloatArray>, w: Int, h: Int, u0: Int, v0: Int, u1: Int, v1: Int, rnd: Random) {
            // A fingertip-like dark ellipse entering from one edge of the screen.
            val ru = (u1 - u0) / 8 + 1
            val rv = (v1 - v0) / 5 + 1
            val cu = u0 + rnd.nextInt(max(1, u1 - u0))
            val cv = v1
            for (y in max(0, cv - 2 * rv)..min(h - 1, cv + rv)) for (x in max(0, cu - ru)..min(w - 1, cu + ru)) {
                val du = (x - cu).toDouble() / ru; val dv = (y - cv).toDouble() / (2 * rv)
                if (du * du + dv * dv <= 1) for (c in 0..2) img[c][y * w + x] = 0.08f
            }
        }

        fun jpegRoundTrip(argb: IntArray, w: Int, h: Int, quality: Int): IntArray {
            val bi = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            bi.setRGB(0, 0, w, h, argb, 0, w)
            val bos = ByteArrayOutputStream()
            val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
            val params = writer.defaultWriteParam.apply { compressionMode = ImageWriteParam.MODE_EXPLICIT; compressionQuality = quality / 100f }
            ImageIO.createImageOutputStream(bos).use { ios -> writer.output = ios; writer.write(null, IIOImage(bi, null, null), params) }
            writer.dispose()
            val back = ImageIO.read(ByteArrayInputStream(bos.toByteArray()))
            return back.getRGB(0, 0, w, h, null, 0, w)
        }

        /** Saves a camera frame as PNG (datasets / debugging). */
        fun savePng(frame: YuvFrame, file: java.io.File) {
            val bi = BufferedImage(frame.width, frame.height, BufferedImage.TYPE_INT_RGB)
            for (y in 0 until frame.height) for (x in 0 until frame.width) bi.setRGB(x, y, frame.rgb(x, y))
            file.parentFile?.mkdirs()
            ImageIO.write(bi, "png", file)
        }
    }
}
