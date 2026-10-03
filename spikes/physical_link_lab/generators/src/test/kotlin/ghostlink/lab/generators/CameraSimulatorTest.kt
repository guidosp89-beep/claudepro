package ghostlink.lab.generators

import ghostlink.lab.codecs.FrameType
import ghostlink.lab.codecs.LabFrame
import ghostlink.lab.codecs.SchemeId
import ghostlink.lab.codecs.visual.GridCodec
import ghostlink.lab.codecs.visual.GridSpec
import ghostlink.lab.codecs.visual.QrCodec
import ghostlink.lab.codecs.visual.QrEcc
import ghostlink.lab.codecs.visual.QrSpec
import ghostlink.lab.codecs.visual.Rasterizer
import ghostlink.lab.decoder.GridImageDecoder
import ghostlink.lab.decoder.ZxingQrDecoder
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CameraSimulatorTest {
    private val sim = CameraSimulator()
    private fun bytes(cap: Int) = LabFrame(FrameType.DATA, 1, 1, SchemeId.RAPTORQ, 5, 1000, Random(3).nextBytes(cap - LabFrame.OVERHEAD_BYTES)).encode()

    @Test
    fun qrDecodesAtShortRangeAndFailsWhenTooSmall() {
        val spec = QrSpec(10, QrEcc.M)
        val data = bytes(spec.frameCapacity())
        val screen = Rasterizer.toScreen(QrCodec.encode(data, spec), 1080, 2400)
        val near = ZxingQrDecoder().decode(sim.capture(screen, Scene(distanceCm = 30.0)))
        assertTrue(near.ok, "QR v10 at 30 cm: ${near.failure}")
        assertContentEquals(data, near.data)
        val far = ZxingQrDecoder().decode(sim.capture(screen, Scene(distanceCm = 400.0)))
        assertFalse(far.ok, "QR v10 should not decode at 4 m with a phone screen")
    }

    @Test
    fun gridDecodesWithPerspectiveAndRoll() {
        val spec = GridSpec(48, GridSpec.rowsForAspect(48, 2400.0 / 1080), 1)
        val data = bytes(spec.frameCapacity())
        val screen = Rasterizer.toScreen(GridCodec.encode(data, spec), 1080, 2400)
        val r = GridImageDecoder(spec).decode(sim.capture(screen, Scene(distanceCm = 30.0, angleDeg = 20.0, rollDeg = 7.0)))
        assertTrue(r.ok, "grid: ${r.failure}")
        assertContentEquals(data, r.data)
    }
}
