package ghostlink.lab.decoder

import ghostlink.lab.codecs.FrameType
import ghostlink.lab.codecs.LabFrame
import ghostlink.lab.codecs.SchemeId
import ghostlink.lab.codecs.visual.GridCodec
import ghostlink.lab.codecs.visual.GridSpec
import ghostlink.lab.codecs.visual.QrCodec
import ghostlink.lab.codecs.visual.QrEcc
import ghostlink.lab.codecs.visual.QrSpec
import ghostlink.lab.codecs.visual.Rasterizer
import ghostlink.lab.codecs.visual.VisualFrame
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DecoderTest {
    private fun frameBytes(capacity: Int, seed: Int): ByteArray {
        val rnd = Random(seed)
        return LabFrame(FrameType.DATA, 0x1234, 1, SchemeId.RAPTORQ, seed.toLong(), 100_000,
            ByteArray(capacity - LabFrame.OVERHEAD_BYTES) { rnd.nextInt().toByte() }).encode()
    }

    /** Ideal capture: frame centred on a white "camera" image with a margin, optional 90° rotation. */
    private fun capture(frame: VisualFrame, camW: Int, camH: Int, rotate: Int = 0, chroma: Boolean = true): YuvFrame {
        val inner = Rasterizer.toScreen(frame, (camW * 0.8).toInt(), (camH * 0.8).toInt())
        val iw = (camW * 0.8).toInt()
        val ih = (camH * 0.8).toInt()
        val img = IntArray(camW * camH) { -1 }
        val ox = (camW - iw) / 2
        val oy = (camH - ih) / 2
        for (y in 0 until ih) for (x in 0 until iw) img[(y + oy) * camW + x + ox] = inner[y * iw + x]
        var w = camW
        var h = camH
        var px = img
        repeat(rotate) {
            val out = IntArray(w * h)
            for (y in 0 until h) for (x in 0 until w) out[x * h + (h - 1 - y)] = px[y * w + x]
            px = out
            val t = w; w = h; h = t
        }
        return YuvFrame.fromArgb(w, h, px, withChroma = chroma)
    }

    @Test
    fun zxingDecodesBinaryQrFrames() {
        val dec = ZxingQrDecoder()
        for (spec in listOf(QrSpec(10, QrEcc.M), QrSpec(20, QrEcc.L), QrSpec(25, QrEcc.H))) {
            val bytes = frameBytes(spec.frameCapacity(), spec.version)
            val img = capture(QrCodec.encode(bytes, spec), 1280, 720, rotate = spec.version % 2)
            val r = dec.decode(img)
            assertTrue(r.ok, "${spec.key()} failed: ${r.failure}")
            assertContentEquals(bytes, r.data)
            assertEquals(LabFrame.parse(r.data!!).symbolId, spec.version.toLong())
        }
    }

    @Test
    fun gridDecoderReadsAllModesAndRotations() {
        for ((i, spec) in listOf(GridSpec(48, 104, 1), GridSpec(64, 140, 2), GridSpec(64, 140, 3, 48), GridSpec(64, 140, 1, 32, 2)).withIndex()) {
            val bytes = frameBytes(spec.frameCapacity(), 100 + i)
            val frame = GridCodec.encode(bytes, spec)
            for (rot in 0 until 4) {
                val img = capture(frame, 1920, 1080, rotate = rot, chroma = spec.bitsPerCell > 1)
                val dec = GridImageDecoder(spec)
                val r = dec.decode(img)
                assertTrue(r.ok, "${spec.key()} rot=$rot failed: ${r.failure} key=${dec.lastKeyScore} cell=${dec.lastCellPx}")
                assertContentEquals(bytes, r.data)
            }
        }
    }

    @Test
    fun decodersFailCleanlyOnNoise() {
        val rnd = Random(5)
        val noise = IntArray(640 * 480) { 0xFF000000.toInt() or (rnd.nextInt() and 0xFFFFFF) }
        val img = YuvFrame.fromArgb(640, 480, noise)
        assertFalse(ZxingQrDecoder().decode(img).ok)
        assertFalse(GridImageDecoder(GridSpec(64, 140, 2)).decode(img).ok)
        val blank = YuvFrame.fromArgb(64, 48, IntArray(64 * 48) { -1 })
        assertFalse(GridImageDecoder(GridSpec(48, 104, 1)).decode(blank).ok)
    }
}
