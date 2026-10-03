package ghostlink.lab.decoder

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.ChecksumException
import com.google.zxing.DecodeHintType
import com.google.zxing.FormatException
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ResultMetadataType
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/**
 * QR decoder backed by ZXing Java (Apache-2.0). Pure JVM: identical code runs in the cloud harness
 * and on Android, which makes it the reference decoder for comparisons with zxing-cpp and ML Kit.
 */
class ZxingQrDecoder(private val tryHarder: Boolean = false) : FrameDecoder {
    override val name: String = if (tryHarder) "zxing-java-hard" else "zxing-java"
    private val reader = QRCodeReader()
    private val hints = HashMap<DecodeHintType, Any>().apply {
        put(DecodeHintType.POSSIBLE_FORMATS, listOf(BarcodeFormat.QR_CODE))
        if (tryHarder) put(DecodeHintType.TRY_HARDER, true)
    }

    override fun decode(frame: YuvFrame): DecodeAttempt {
        val t0 = System.nanoTime()
        val bitmap = try {
            val src = PlanarYUVLuminanceSource(frame.y, frame.yRowStride, frame.height, 0, 0, frame.width, frame.height, false)
            BinaryBitmap(HybridBinarizer(src)).also { it.blackMatrix } // force binarization into the preprocess stage
        } catch (e: NotFoundException) {
            return DecodeAttempt.fail("binarize", System.nanoTime() - t0)
        } catch (e: RuntimeException) {
            return DecodeAttempt.fail("binarize:${e::class.simpleName}", System.nanoTime() - t0)
        }
        val t1 = System.nanoTime()
        return try {
            val result = reader.decode(bitmap, hints)
            val t2 = System.nanoTime()
            @Suppress("UNCHECKED_CAST")
            val segments = result.resultMetadata?.get(ResultMetadataType.BYTE_SEGMENTS) as? List<ByteArray>
            val bytes = when {
                segments != null && segments.isNotEmpty() -> concat(segments)
                else -> result.text.toByteArray(Charsets.ISO_8859_1)
            }
            DecodeAttempt(bytes, null, preprocessNs = t1 - t0, detectNs = t2 - t1)
        } catch (e: NotFoundException) {
            DecodeAttempt.fail("not_found", t1 - t0, System.nanoTime() - t1)
        } catch (e: ChecksumException) {
            DecodeAttempt.fail("checksum", t1 - t0, System.nanoTime() - t1)
        } catch (e: FormatException) {
            DecodeAttempt.fail("format", t1 - t0, System.nanoTime() - t1)
        } catch (e: RuntimeException) {
            DecodeAttempt.fail("error:${e::class.simpleName}", t1 - t0, System.nanoTime() - t1)
        } finally {
            reader.reset()
        }
    }

    private fun concat(parts: List<ByteArray>): ByteArray {
        if (parts.size == 1) return parts[0]
        val out = ByteArray(parts.sumOf { it.size })
        var p = 0
        for (b in parts) { System.arraycopy(b, 0, out, p, b.size); p += b.size }
        return out
    }
}
