package ghostlink.lab.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.PowerManager
import android.os.SystemClock
import android.util.Range
import android.util.Size
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import ghostlink.lab.benchmark.FrameTiming
import ghostlink.lab.benchmark.Ranking
import ghostlink.lab.benchmark.ReceiverSession
import ghostlink.lab.benchmark.ResourceProbe
import ghostlink.lab.benchmark.ResultStore
import ghostlink.lab.benchmark.SessionMeta
import ghostlink.lab.codecs.FrameType
import ghostlink.lab.codecs.LabFrame
import ghostlink.lab.codecs.visual.QrCodec
import ghostlink.lab.codecs.visual.QrEcc
import ghostlink.lab.codecs.visual.QrSpec
import ghostlink.lab.decoder.FrameDecoder
import ghostlink.lab.decoder.ZxingQrDecoder
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * RECEIVER: CameraX ImageAnalysis (YUV_420_888, keep-only-latest) -> ReceiverSession (shared JVM
 * logic) -> runs.csv / raw JSON. Also serves as the TX-side "scan plan" camera (EXTRA_PLAN_SCAN).
 */
@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
class ReceiverActivity : AppCompatActivity(), SensorEventListener {
    private lateinit var prefs: LabPrefs
    private lateinit var previewView: PreviewView
    private lateinit var status: TextView
    private lateinit var planImage: ImageView
    private lateinit var executor: ExecutorService
    private var camera: Camera? = null
    private var session: ReceiverSession? = null
    private var store: ResultStore? = null
    private var sessionDir: File? = null
    private val holder = ImageHolder()
    private val copier = ImageCopier()
    private var mlkit: MlKitDecoder? = null
    private var planScan = false
    @Volatile private var lux: Double? = null
    @Volatile private var realtimeTimestamps = false
    @Volatile private var lastSkewNs: Long? = null
    @Volatile private var lastExposureNs: Long? = null
    @Volatile private var lastIso: Int? = null
    @Volatile private var fpsRange: Range<Int>? = null
    private val skewSamples = ArrayList<Long>()
    private var lastUiNs = 0L
    @Volatile private var ended = false
    private var startedAtMs = 0L

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else { toast("Camera permission is required"); finish() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = LabPrefs(this)
        planScan = intent.getBooleanExtra(EXTRA_PLAN_SCAN, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        executor = Executors.newSingleThreadExecutor()
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }
        previewView = PreviewView(this)
        col.addView(previewView, LinearLayout.LayoutParams(-1, 0, 1f))
        status = TextView(this).apply { setTextColor(Color.WHITE); textSize = 14f; setPadding(24, 16, 24, 16) }
        col.addView(status)
        planImage = ImageView(this).apply { setBackgroundColor(Color.WHITE); visibility = android.view.View.GONE }
        col.addView(planImage, LinearLayout.LayoutParams(-1, 0, 1.2f))
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        buttons.addView(Button(this).apply { text = "STOP"; setOnClickListener { stopSession("USER_STOP") } })
        buttons.addView(Button(this).apply { text = "EXPORT"; setOnClickListener { export() } })
        if (!planScan) col.addView(buttons)
        setContentView(col)
        status.text = if (planScan) "Point this camera at the RECEIVER's PLAN QR…" else "Starting camera…"
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else permission.launch(Manifest.permission.CAMERA)
    }

    private fun meta() = SessionMeta(
        receiverDevice = DeviceProfile.model(),
        distanceCm = prefs.distanceCm, angleDeg = prefs.angleDeg, lightClass = prefs.lightClass, motion = prefs.motion,
        qrDecoder = prefs.qrDecoder, exposureMode = prefs.exposure, analysisResolution = prefs.resolution,
        zoom = prefs.zoom.toDouble(), source = "PHYSICAL", notes = prefs.notes,
    )

    private fun buildSession(): ReceiverSession {
        val dir = LabFiles.newSessionDir(this, "RX_" + java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.ROOT).format(java.util.Date()))
        sessionDir = dir
        val st = ResultStore(dir)
        store = st
        val zxCpp = ZxingCppDecoder(holder)
        val zxJava = ZxingQrDecoder()
        val (primary, extras) = when (prefs.qrDecoder) {
            "zxing-java" -> zxJava to emptyList()
            "mlkit" -> MlKitDecoder(holder).also { mlkit = it } to emptyList<FrameDecoder>()
            "compare-all" -> zxCpp to listOf(zxJava, MlKitDecoder(holder).also { mlkit = it })
            else -> zxCpp to emptyList()
        }
        val probe = object : ResourceProbe {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            override fun cpuTimeMs() = android.os.Process.getElapsedCpuTime()
            override fun memoryMb(): Double {
                val rt = Runtime.getRuntime()
                return (rt.totalMemory() - rt.freeMemory() + Debug.getNativeHeapAllocatedSize()) / 1048576.0
            }
            override fun thermalStatus() = if (Build.VERSION.SDK_INT >= 29) pm.currentThermalStatus else -1
            override fun ambientLux() = lux
        }
        startedAtMs = System.currentTimeMillis()
        return ReceiverSession(meta(), primary, extras, st, probe)
    }

    private fun startCamera() {
        if (!planScan) session = buildSession()
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({ bind(future.get()) }, ContextCompat.getMainExecutor(this))
        (getSystemService(Context.SENSOR_SERVICE) as SensorManager).let { sm ->
            sm.getDefaultSensor(Sensor.TYPE_LIGHT)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        }
    }

    private fun bind(provider: ProcessCameraProvider) {
        val (w, h) = prefs.resolution.split("x").map { it.toInt() }
        val selector = ResolutionSelector.Builder()
            .setResolutionStrategy(ResolutionStrategy(Size(w, h), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
            .build()
        val builder = ImageAnalysis.Builder()
            .setResolutionSelector(selector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
        val ext = Camera2Interop.Extender(builder)
        ext.setSessionCaptureCallback(object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, result: TotalCaptureResult) {
                lastSkewNs = result.get(CaptureResult.SENSOR_ROLLING_SHUTTER_SKEW)
                lastExposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                lastIso = result.get(CaptureResult.SENSOR_SENSITIVITY)
            }
        })
        val analysis = builder.build()
        analysis.setAnalyzer(executor) { img -> analyze(img) }
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        provider.unbindAll()
        val cam = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        camera = cam
        cam.cameraControl.setZoomRatio(prefs.zoom)
        val info = Camera2CameraInfo.from(cam.cameraInfo)
        realtimeTimestamps = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
            CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
        val manual = info.getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true
        // Frame-rate range: only ranges the device advertises; prefer a fixed 30 fps (timing model §E).
        val ranges = info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList() ?: emptyList()
        fpsRange = ranges.firstOrNull { it.lower == 30 && it.upper == 30 }
            ?: ranges.filter { it.upper <= 30 }.maxWithOrNull(compareBy({ it.upper }, { it.lower }))
            ?: ranges.maxWithOrNull(compareBy({ it.upper }, { it.lower }))
        fun options(aeLock: Boolean): CaptureRequestOptions {
            val b = CaptureRequestOptions.Builder()
            fpsRange?.let { b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
            if (prefs.exposure == "SHORT" && manual) {
                b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                b.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, SHORT_EXPOSURE_NS)
                b.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, SHORT_EXPOSURE_ISO)
            }
            if (aeLock) b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, true)
            return b.build()
        }
        val c2 = Camera2CameraControl.from(cam.cameraControl)
        c2.setCaptureRequestOptions(options(aeLock = false))
        if (prefs.exposure == "SHORT" && !manual) toast("MANUAL_SENSOR not supported: SHORT exposure not applied")
        if (prefs.exposure == "LOCK") previewView.postDelayed({ c2.setCaptureRequestOptions(options(aeLock = true)) }, 2500)
        status.text = if (planScan) status.text else "Waiting for transmitter announce…"
    }

    private fun analyze(img: ImageProxy) {
        try {
            val arrival = SystemClock.elapsedRealtimeNanos()
            val ts = img.imageInfo.timestamp
            if (planScan) { scanPlan(img); return }
            val s = session ?: return
            if (ended) return
            val t0 = System.nanoTime()
            val frame = copier.copy(img, ts)
            val copyNs = System.nanoTime() - t0
            holder.current = img
            s.noteFrameSize(img.width, img.height)
            val event = s.process(frame, FrameTiming(ts, if (realtimeTimestamps && ts > 0) arrival - ts else 0L, copyNs))
            holder.current = null
            lastSkewNs?.let { if (skewSamples.size < 2000) skewSamples.add(it) }
            val now = System.nanoTime()
            if (event != null || now - lastUiNs > 300_000_000L) {
                lastUiNs = now
                val text = s.status() + (event?.let { "\nlast event: $it" } ?: "") +
                    "\n${img.width}x${img.height} exp=${lastExposureNs?.let { it / 1000 }}µs iso=$lastIso lux=${lux?.toInt()}"
                runOnUiThread { status.text = text }
            }
            if (s.isEnded) onSessionEnded()
        } catch (e: Exception) {
            runOnUiThread { status.text = "Analyzer error: ${e::class.java.simpleName}: ${e.message}" }
        } finally {
            holder.current = null
            img.close()
        }
    }

    private val planDecoder = ZxingQrDecoder()

    private fun scanPlan(img: ImageProxy) {
        val f = LabFrame.parseOrNull(planDecoder.decode(copier.copy(img, 0)).data) ?: return
        if (f.type != FrameType.PLAN) return
        val top = try { ghostlink.lab.benchmark.Plans.decodeTop(f.body) } catch (e: Exception) { return }
        prefs.topConfigsHex = LabPrefs.bytesToHex(f.body)
        runOnUiThread {
            toast("Plan loaded: ${top.size} top configs")
            finish()
        }
    }

    /** Runs on the analysis thread (single writer of the session); UI updates are posted. */
    private fun onSessionEnded() {
        if (ended) return
        ended = true
        val s = session ?: return
        finalizeFiles("END")
        val top = Ranking.top(s.records)
        val bitmap = if (top.isNotEmpty()) {
            val sid = s.currentSessionId ?: "x"
            val bytes = Ranking.planFrame(LabFrame.sessionTagOf(sid), top)
            TransmitterActivity.toBitmap(QrCodec.encode(bytes, QrSpec(6, QrEcc.M))).let {
                android.graphics.Bitmap.createScaledBitmap(it, it.width * 8, it.height * 8, false)
            }
        } else null
        val passes = s.records.count { it["RESULT"] == "PASS" }
        val text = "SESSION COMPLETE: ${s.records.size} runs, $passes PASS.\n" +
            (if (bitmap != null) "Optional: on the TRANSMITTER choose 'SCAN PLAN FROM RECEIVER' and point it at this QR.\n" else "") +
            "Press EXPORT to save results."
        runOnUiThread {
            if (bitmap != null) { planImage.setImageBitmap(bitmap); planImage.visibility = android.view.View.VISIBLE }
            status.text = text
        }
    }

    private fun stopSession(reason: String, then: (() -> Unit)? = null) {
        executor.execute {
            val s = session
            if (s != null && !ended) {
                s.finishCurrent(reason)
                ended = true
                finalizeFiles(reason)
                val n = s.records.size
                runOnUiThread { status.text = "Stopped: $n runs recorded. Press EXPORT." }
            }
            then?.let { runOnUiThread(it) }
        }
    }

    /** receiver.json + rename the directory with the transmitter session id (correlates both phones). */
    private fun finalizeFiles(reason: String) {
        val s = session ?: return
        var dir = sessionDir ?: return
        store?.rewriteCsv(s.records)
        LabFiles.writeJson(dir, "receiver.json", linkedMapOf(
            "role" to "RECEIVER",
            "session_id" to s.currentSessionId,
            "end_reason" to reason,
            "device" to DeviceProfile.model(),
            "meta" to meta().let { m -> linkedMapOf("distance_cm" to m.distanceCm, "angle_deg" to m.angleDeg, "light" to m.lightClass,
                "motion" to m.motion, "qr_decoder" to m.qrDecoder, "exposure" to m.exposureMode, "resolution" to m.analysisResolution,
                "zoom" to m.zoom, "notes" to m.notes) },
            "frames_total" to s.framesTotal,
            "runs" to s.records.size,
            "passes" to s.records.count { it["RESULT"] == "PASS" },
            "started_at_ms" to startedAtMs,
            "ended_at_ms" to System.currentTimeMillis(),
            "rolling_shutter_skew_ns_samples" to skewSamples.take(500),
            "realtime_timestamps" to realtimeTimestamps,
            "ae_target_fps_range" to fpsRange?.toString(),
            "ranking" to Ranking.rank(s.records).map { mapOf("config" to it.spec.configKey(), "score" to it.score, "runs" to it.runs, "passes" to it.passes, "median_goodput" to it.medianGoodput) },
        ))
        LabFiles.writeJson(dir, "device_profile.json", DeviceProfile.collect(this))
        val sid = s.currentSessionId
        if (sid != null && !dir.name.startsWith(sid)) {
            val target = File(dir.parentFile, "${sid}_RX_${DeviceProfile.deviceId()}")
            if (dir.renameTo(target)) { dir = target; sessionDir = target }
        }
    }

    private fun export() {
        if (sessionDir == null) return toast("Nothing to export")
        stopSession("EXPORT") {
            val dir = sessionDir ?: return@stopSession
            try { toast("Exported to ${LabFiles.export(this, dir)}") } catch (e: Exception) { toast("Export failed: ${e.message}") }
        }
    }

    override fun onSensorChanged(event: SensorEvent) { lux = event.values.firstOrNull()?.toDouble() }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDestroy() {
        (getSystemService(Context.SENSOR_SERVICE) as SensorManager).unregisterListener(this)
        if (!planScan && !ended) stopSession("CLOSED")
        executor.shutdown()
        mlkit?.close()
        super.onDestroy()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    companion object {
        const val EXTRA_PLAN_SCAN = "plan_scan"
        const val SHORT_EXPOSURE_NS = 1_000_000L
        const val SHORT_EXPOSURE_ISO = 800
    }
}
