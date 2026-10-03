package ghostlink.lab.android

import androidx.camera.core.ImageProxy
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import ghostlink.lab.decoder.DecodeAttempt
import ghostlink.lab.decoder.FrameDecoder
import ghostlink.lab.decoder.YuvFrame
import zxingcpp.BarcodeReader
import java.util.concurrent.TimeUnit

/**
 * Decoders that need the platform image. Single analysis thread: [current] is set right before
 * ReceiverSession.process() and cleared after (spike simplification, documented).
 */
class ImageHolder { @Volatile var current: ImageProxy? = null }

class ZxingCppDecoder(private val holder: ImageHolder) : FrameDecoder {
    override val name = "zxing-cpp"
    private val reader = BarcodeReader(
        BarcodeReader.Options(formats = setOf(BarcodeReader.Format.QR_CODE), tryHarder = false, tryRotate = false),
    )

    override fun decode(frame: YuvFrame): DecodeAttempt {
        val img = holder.current ?: return DecodeAttempt.fail("no_image")
        val t0 = System.nanoTime()
        return try {
            val results = reader.read(img)
            val t1 = System.nanoTime()
            val bytes = results.firstOrNull { it.format == BarcodeReader.Format.QR_CODE && it.bytes != null }?.bytes
            if (bytes != null) DecodeAttempt(bytes, null, detectNs = t1 - t0) else DecodeAttempt.fail("not_found", detectNs = t1 - t0)
        } catch (e: Exception) {
            DecodeAttempt.fail("error:${e::class.java.simpleName}", detectNs = System.nanoTime() - t0)
        }
    }
}

/**
 * Google ML Kit bundled barcode model (closed source, on-device). Binary QR payloads are read from
 * Barcode.rawBytes; if ML Kit alters byte-mode data the lab frame CRC rejects it, which the CSV shows.
 */
class MlKitDecoder(private val holder: ImageHolder) : FrameDecoder {
    override val name = "mlkit"
    private val scanner: BarcodeScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build(),
    )

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    override fun decode(frame: YuvFrame): DecodeAttempt {
        val img = holder.current ?: return DecodeAttempt.fail("no_image")
        val media = img.image ?: return DecodeAttempt.fail("no_media_image")
        val t0 = System.nanoTime()
        return try {
            val input = InputImage.fromMediaImage(media, img.imageInfo.rotationDegrees)
            val barcodes = Tasks.await(scanner.process(input), 2, TimeUnit.SECONDS)
            val t1 = System.nanoTime()
            val bytes = barcodes.firstOrNull { it.rawBytes != null }?.rawBytes
            if (bytes != null) DecodeAttempt(bytes, null, detectNs = t1 - t0) else DecodeAttempt.fail("not_found", detectNs = t1 - t0)
        } catch (e: Exception) {
            DecodeAttempt.fail("error:${e::class.java.simpleName}", detectNs = System.nanoTime() - t0)
        }
    }

    fun close() = scanner.close()
}

/** Copies ImageProxy planes into reusable arrays (one copy per frame; timing reported as copy_ms). */
class ImageCopier {
    private var y = ByteArray(0)
    private var u = ByteArray(0)
    private var v = ByteArray(0)

    fun copy(img: ImageProxy, sensorTimestampNs: Long): YuvFrame {
        val w = img.width
        val h = img.height
        val yp = img.planes[0]
        val up = img.planes[1]
        val vp = img.planes[2]
        val yNeed = yp.rowStride * h
        if (y.size < yNeed) y = ByteArray(yNeed)
        val yb = yp.buffer.duplicate().also { it.rewind() }
        yb.get(y, 0, minOf(yb.remaining(), yNeed))
        val ub = up.buffer.duplicate().also { it.rewind() }
        val vb = vp.buffer.duplicate().also { it.rewind() }
        if (u.size < ub.remaining()) u = ByteArray(ub.remaining())
        if (v.size < vb.remaining()) v = ByteArray(vb.remaining())
        ub.get(u, 0, ub.remaining())
        vb.get(v, 0, vb.remaining())
        return YuvFrame(w, h, y, yp.rowStride, u, v, up.rowStride, up.pixelStride, sensorTimestampNs)
    }
}
