package ghostlink.lab.benchmark

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Labels the owner sets per physical session (brief §23–§26); "SIMULATED" source for cloud runs. */
data class SessionMeta(
    val receiverDevice: String,
    val distanceCm: Int,
    val angleDeg: Int,
    val lightClass: String,
    val motion: String,
    val qrDecoder: String,
    val exposureMode: String,
    val analysisResolution: String,
    val zoom: Double,
    val source: String = "PHYSICAL",
    val notes: String = "",
) {
    companion object {
        val LIGHT_CLASSES = listOf("INDOOR_DIM", "INDOOR_NORMAL", "INDOOR_BRIGHT", "OUTDOOR_SHADE", "OUTDOOR_SUN")
        val MOTION_CLASSES = listOf("STATIC", "HANDHELD_TX", "HANDHELD_RX", "BOTH_HANDHELD")
        val DISTANCES_CM = listOf(20, 40, 60, 100, 150, 200)
        val ANGLES_DEG = listOf(0, 15, 30, 45)
    }
}

/** Platform resource sampling (Android implementation in the app; optional elsewhere). */
interface ResourceProbe {
    fun cpuTimeMs(): Long
    fun memoryMb(): Double
    fun thermalStatus(): Int
    fun ambientLux(): Double?
}

/** Percentile helper on a copy (nearest-rank). */
fun percentile(values: List<Double>, p: Double): Double? {
    if (values.isEmpty()) return null
    val s = values.sorted()
    val idx = ((p / 100.0) * (s.size - 1)).let { kotlin.math.round(it).toInt() }.coerceIn(0, s.size - 1)
    return s[idx]
}

/** One row of runs.csv = one physical (or simulated) trial, as observed by the receiver. */
class RunRecord(val fields: LinkedHashMap<String, Any?>) {
    operator fun get(k: String): Any? = fields[k]

    fun csvRow(): String = COLUMNS.joinToString(",") { csvCell(fields[it]) }

    companion object {
        /** Stable column order (brief §17 plus pipeline/decoder diagnostics). */
        val COLUMNS = listOf(
            "run_id", "session_id", "trial_index", "stage", "repetition", "timestamp", "source",
            "sender_device", "receiver_device",
            "codec", "codec_version", "visual_key", "config_key", "qr_version", "qr_ecc",
            "grid_cols", "grid_rows", "grid_bits_per_cell", "grid_rs_parity",
            "scheme", "symbol_size", "source_symbols", "payload_bytes", "encoded_bytes",
            "display_width", "display_height", "display_refresh_rate",
            "camera_width", "camera_height", "camera_fps", "analysis_fps",
            "target_visual_fps", "actual_visual_fps",
            "distance_cm", "angle_deg", "ambient_light_class", "ambient_lux", "motion",
            "qr_decoder", "exposure_mode", "analysis_resolution", "zoom",
            "frames_generated", "frames_seen", "frames_decoded", "frames_failed", "frames_other", "frames_dropped",
            "symbols_unique", "symbols_duplicate", "fountain_overhead",
            "transfer_seconds", "latency_first_frame_ms",
            "gross_bitrate", "net_bitrate", "goodput_bytes_sec",
            "decode_latency_ms", "decode_latency_p90_ms",
            "capture_to_analysis_ms", "copy_ms", "preprocess_ms", "detect_ms", "sample_ms", "ecc_ms", "fec_ms",
            "cpu_pct", "memory_mb", "thermal_start", "thermal_end", "battery_delta",
            "announce_missed", "ghost_packets_identical", "ghost_packets_total",
            "failure_top", "SHA256_PASS", "RESULT", "notes",
        )

        fun csvHeader(): String = COLUMNS.joinToString(",")

        fun csvCell(v: Any?): String {
            val s = when (v) {
                null -> ""
                is Double -> if (v.isNaN() || v.isInfinite()) "" else "%.4f".format(java.util.Locale.ROOT, v)
                is Float -> if (v.isNaN() || v.isInfinite()) "" else "%.4f".format(java.util.Locale.ROOT, v)
                else -> v.toString()
            }
            return if (s.contains(',') || s.contains('"') || s.contains('\n')) "\"" + s.replace("\"", "\"\"") + "\"" else s
        }
    }
}

/** Minimal JSON writer (no dependencies, Android-safe). */
object Json {
    fun write(v: Any?): String = StringBuilder().also { append(it, v) }.toString()

    private fun append(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> quote(sb, v)
            is Boolean -> sb.append(v)
            is Double -> if (v.isNaN() || v.isInfinite()) sb.append("null") else sb.append(v)
            is Float -> if (v.isNaN() || v.isInfinite()) sb.append("null") else sb.append(v.toDouble())
            is Number -> sb.append(v)
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(',')
                    first = false
                    quote(sb, k.toString()); sb.append(':'); append(sb, value)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (e in v) { if (!first) sb.append(','); first = false; append(sb, e) }
                sb.append(']')
            }
            is DoubleArray -> append(sb, v.toList())
            is LongArray -> append(sb, v.toList())
            is IntArray -> append(sb, v.toList())
            else -> quote(sb, v.toString())
        }
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) when {
            c == '"' -> sb.append("\\\"")
            c == '\\' -> sb.append("\\\\")
            c == '\n' -> sb.append("\\n")
            c == '\r' -> sb.append("\\r")
            c == '\t' -> sb.append("\\t")
            c < ' ' -> sb.append("\\u%04x".format(c.code))
            else -> sb.append(c)
        }
        sb.append('"')
    }
}

/** Session directory layout shared by the Android app and the cloud harness. */
class ResultStore(val root: File) {
    val runsCsv = File(root, "runs.csv")
    val rawDir = File(root, "raw")
    val logsDir = File(root, "logs")

    init { rawDir.mkdirs(); logsDir.mkdirs() }

    @Synchronized fun append(record: RunRecord, raw: Map<String, Any?>) {
        if (!runsCsv.exists()) runsCsv.writeText(RunRecord.csvHeader() + "\n")
        runsCsv.appendText(record.csvRow() + "\n")
        File(rawDir, "${record["run_id"]}.json").writeText(Json.write(raw))
    }

    /** Rewrites runs.csv from [records] (sender-side fields may arrive after a row was first written). */
    @Synchronized fun rewriteCsv(records: List<RunRecord>) {
        runsCsv.writeText(RunRecord.csvHeader() + "\n" + records.joinToString("") { it.csvRow() + "\n" })
    }

    fun writeJson(name: String, value: Any?) = File(root, name).writeText(Json.write(value))

    /** ghostlink_m1_results_<session>.zip containing everything under [root] (brief §69). */
    fun exportZip(target: File): File {
        target.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(target)).use { zip ->
            root.walkTopDown().filter { it.isFile && it != target }.forEach { f ->
                zip.putNextEntry(ZipEntry(f.relativeTo(root).path.replace(File.separatorChar, '/')))
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return target
    }
}

object SessionIds {
    /** e.g. M1_20261003_143012_7F (UTC). */
    fun create(nowMs: Long = System.currentTimeMillis(), suffix: Int = (nowMs and 0xFF).toInt()): String {
        val fmt = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.ROOT).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        return "M1_${fmt.format(java.util.Date(nowMs))}_%02X".format(suffix and 0xFF)
    }
}
