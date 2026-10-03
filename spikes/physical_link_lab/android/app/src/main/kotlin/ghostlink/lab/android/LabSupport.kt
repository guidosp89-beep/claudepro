package ghostlink.lab.android

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import ghostlink.lab.benchmark.Json
import ghostlink.lab.benchmark.Plans
import ghostlink.lab.benchmark.TrialSpec
import java.io.File

/** Owner-facing settings, persisted between sessions. */
class LabPrefs(ctx: Context) {
    private val p = ctx.getSharedPreferences("lab", Context.MODE_PRIVATE)

    var distanceCm: Int get() = p.getInt("distance", 40); set(v) = p.edit().putInt("distance", v).apply()
    var angleDeg: Int get() = p.getInt("angle", 0); set(v) = p.edit().putInt("angle", v).apply()
    var lightClass: String get() = p.getString("light", "INDOOR_NORMAL")!!; set(v) = p.edit().putString("light", v).apply()
    var motion: String get() = p.getString("motion", "STATIC")!!; set(v) = p.edit().putString("motion", v).apply()
    var qrDecoder: String get() = p.getString("qrdec", QR_DECODERS[0])!!; set(v) = p.edit().putString("qrdec", v).apply()
    var exposure: String get() = p.getString("exposure", EXPOSURES[0])!!; set(v) = p.edit().putString("exposure", v).apply()
    var resolution: String get() = p.getString("res", RESOLUTIONS[1])!!; set(v) = p.edit().putString("res", v).apply()
    var zoom: Float get() = p.getFloat("zoom", 1f); set(v) = p.edit().putFloat("zoom", v).apply()
    var plan: String get() = p.getString("plan", PLANS[0])!!; set(v) = p.edit().putString("plan", v).apply()
    var notes: String get() = p.getString("notes", "")!!; set(v) = p.edit().putString("notes", v).apply()

    /** Top configs received from the receiver's PLAN QR (Stage 2 output), hex-encoded. */
    var topConfigsHex: String get() = p.getString("top", "")!!; set(v) = p.edit().putString("top", v).apply()

    fun topConfigs(): List<TrialSpec> = try {
        if (topConfigsHex.isEmpty()) Plans.defaultTop() else Plans.decodeTop(hexToBytes(topConfigsHex))
    } catch (e: Exception) {
        Plans.defaultTop()
    }

    companion object {
        val QR_DECODERS = listOf("zxing-cpp", "zxing-java", "mlkit", "compare-all")
        val EXPOSURES = listOf("AUTO", "LOCK", "SHORT")
        val RESOLUTIONS = listOf("1280x720", "1920x1080", "3840x2160")
        val ZOOMS = listOf(1f, 1.5f, 2f, 3f)
        val PLANS = listOf(
            "AUTO 1: coarse sweep + GhostPacket proof",
            "AUTO 2: fine search + confirm (uses receiver plan)",
            "ROBUSTNESS (distance/angle/light/motion)",
            "PAYLOAD LADDER 256 B..1 MB",
            "DECODER COMPARE (receiver: compare-all)",
            "GHOSTPACKET PROOF only",
        )

        fun hexToBytes(s: String) = ByteArray(s.length / 2) { s.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
        fun bytesToHex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}

object LabFiles {
    fun sessionsRoot(ctx: Context): File = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "sessions").also { it.mkdirs() }

    fun newSessionDir(ctx: Context, name: String): File = File(sessionsRoot(ctx), name).also { it.mkdirs() }

    fun writeJson(dir: File, name: String, value: Any?) = File(dir, name).writeText(Json.write(value))

    /**
     * Zips [dir] (plus the device profile) as ghostlink_m1_results_<name>.zip, copies it to Downloads
     * (Android 10+) and opens the share sheet. Returns a human-readable location.
     */
    fun export(ctx: Context, dir: File): String {
        writeJson(dir, "device_profile.json", DeviceProfile.collect(ctx))
        val zipName = "ghostlink_m1_results_${dir.name}.zip"
        val zip = File(sessionsRoot(ctx), zipName)
        ghostlink.lab.benchmark.ResultStore(dir).exportZip(zip)
        var where = zip.absolutePath
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, zipName)
                put(MediaStore.Downloads.MIME_TYPE, "application/zip")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/GhostlinkLab")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                ctx.contentResolver.openOutputStream(uri)?.use { out -> zip.inputStream().use { it.copyTo(out) } }
                where = "Download/GhostlinkLab/$zipName"
            }
        }
        val shareUri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", zip)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, shareUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, "Export $zipName").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return where
    }

    /** Most recent session directory, if any. */
    fun latestSession(ctx: Context): File? = sessionsRoot(ctx).listFiles()?.filter { it.isDirectory }?.maxByOrNull { it.lastModified() }
}
