package ghostlink.lab.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Bundle
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import ghostlink.lab.benchmark.Plans
import ghostlink.lab.benchmark.SessionIds
import ghostlink.lab.benchmark.SlotKind
import ghostlink.lab.benchmark.TransmitterSession
import ghostlink.lab.benchmark.TrialSpec
import ghostlink.lab.benchmark.TxInfo
import ghostlink.lab.benchmark.TxSlot
import ghostlink.lab.codecs.Stage
import ghostlink.lab.codecs.visual.GridCodec
import ghostlink.lab.codecs.visual.GridSpec
import ghostlink.lab.codecs.visual.QrCodec
import ghostlink.lab.codecs.visual.QrSpec
import ghostlink.lab.codecs.visual.VisualFrame
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * TRANSMITTER: full-screen, max brightness, highest refresh mode; frames are produced on a background
 * thread (ahead of time) and swapped on vsync via Choreographer, each held round(refresh/fps) vsyncs.
 */
class TransmitterActivity : AppCompatActivity(), Choreographer.FrameCallback {
    private lateinit var view: SlotView
    private lateinit var overlay: TextView
    private lateinit var prefs: LabPrefs
    private var session: TransmitterSession? = null
    private var producer: Thread? = null
    private val queue = ArrayBlockingQueue<Rendered>(6)
    @Volatile private var producerDone = false
    private var current: Rendered? = null
    private var vsyncsOnCurrent = 0
    private var lastFrameNs = 0L
    private var refreshHz = 60f
    private var running = false
    private var finished = false
    private var stalls = 0L
    private var startedAtMs = 0L
    private var sessionDir: File? = null

    private class Rendered(val slot: TxSlot, val bitmap: Bitmap)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = LabPrefs(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.attributes = window.attributes.also { lp ->
            lp.screenBrightness = 1f
            bestDisplayModeId()?.let { lp.preferredDisplayModeId = it }
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        view = SlotView(this)
        overlay = TextView(this).apply {
            setBackgroundColor(Color.argb(220, 0, 0, 0)); setTextColor(Color.WHITE); textSize = 16f; setPadding(32, 32, 32, 32)
            gravity = Gravity.CENTER
        }
        val root = FrameLayout(this)
        root.addView(view, FrameLayout.LayoutParams(-1, -1))
        root.addView(overlay, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
        setContentView(root)
        val plan = buildPlan(prefs)
        overlay.text = "TRANSMITTER\n\n${prefs.plan}\n${TransmitterSession.describe(plan)} — ${plan.size} trials\n\n" +
            "Point the RECEIVER camera at this screen, start the receiver, then TAP HERE to start.\n" +
            "Keep both phones still. Tap again during the run to abort."
        overlay.setOnClickListener { if (!running && !finished) start() else if (running) abort() }
        view.setOnClickListener { if (running) abort() }
    }

    @Suppress("DEPRECATION")
    private fun bestDisplayModeId(): Int? {
        val d = windowManager.defaultDisplay
        val cur = d.mode
        return d.supportedModes.filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
            .maxByOrNull { it.refreshRate }?.modeId
    }

    @Suppress("DEPRECATION")
    private fun start() {
        if (view.width == 0) return
        refreshHz = windowManager.defaultDisplay.refreshRate
        val id = SessionIds.create()
        val aspect = view.height.toDouble() / view.width
        val tx = TxInfo(DeviceProfile.model(), view.width, view.height, (refreshHz * 100).roundToInt())
        val s = TransmitterSession(id, buildPlan(prefs), aspect, tx)
        session = s
        sessionDir = LabFiles.newSessionDir(this, "${id}_TX")
        producerDone = false
        producer = Thread({ produce(s) }, "tx-producer").also { it.start() }
        running = true
        startedAtMs = System.currentTimeMillis()
        overlay.visibility = View.GONE
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun produce(s: TransmitterSession) {
        var lastKey = 0
        var lastBitmap: Bitmap? = null
        try {
            for (slot in s.slots()) {
                val bmp = if (slot.cacheKey == lastKey && lastBitmap != null) lastBitmap else toBitmap(render(slot)).also { lastKey = slot.cacheKey; lastBitmap = it }
                while (!queue.offer(Rendered(slot, bmp), 200, TimeUnit.MILLISECONDS)) {
                    if (Thread.currentThread().isInterrupted) return
                }
            }
        } catch (e: InterruptedException) {
            return
        } finally {
            producerDone = true
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        val period = 1e9 / refreshHz
        if (lastFrameNs != 0L && frameTimeNanos - lastFrameNs > 1.5 * period) {
            val missed = ((frameTimeNanos - lastFrameNs) / period).roundToInt() - 1
            current?.let { session?.statsFor(it.slot.trialIndex)?.let { st -> st.missedVsyncs += missed } }
        }
        lastFrameNs = frameTimeNanos
        val cur = current
        val hold = cur?.let { maxOf(1, (refreshHz / it.slot.fps).roundToInt()) } ?: 1
        if (cur == null || ++vsyncsOnCurrent >= hold) {
            val next = queue.poll()
            if (next != null) {
                current = next
                vsyncsOnCurrent = 0
                view.show(next.bitmap)
                if (next.slot.kind == SlotKind.DATA) {
                    val st = session!!.statsFor(next.slot.trialIndex)
                    val now = System.currentTimeMillis()
                    if (st.firstDataShownMs == 0L) st.firstDataShownMs = now
                    st.lastDataShownMs = now
                    st.dataSlotsShown++
                }
            } else if (producerDone) {
                endSession(completed = true); return
            } else {
                stalls++
            }
        }
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun abort() = endSession(completed = false)

    private fun endSession(completed: Boolean) {
        if (!running) return
        running = false
        finished = true
        producer?.interrupt()
        Choreographer.getInstance().removeFrameCallback(this)
        val s = session ?: return
        val dir = sessionDir ?: return
        val trials = s.allStats().map { st ->
            val cfg = s.configs.getOrNull(st.trialIndex)
            linkedMapOf(
                "trial_index" to st.trialIndex,
                "visual_key" to cfg?.visual?.key(),
                "config_key" to cfg?.configKey(),
                "stage" to cfg?.stage?.name,
                "payload_bytes" to cfg?.payloadBytes,
                "target_fps" to cfg?.targetFps,
                "frames_generated" to st.dataSlotsShown,
                "elapsed_ms" to st.report().elapsedMs,
                "actual_visual_fps" to st.report().actualFps,
                "missed_vsyncs" to st.missedVsyncs,
            )
        }
        LabFiles.writeJson(dir, "sender.json", linkedMapOf(
            "role" to "TRANSMITTER",
            "session_id" to s.sessionId,
            "completed" to completed,
            "device" to DeviceProfile.model(),
            "display_refresh_hz" to refreshHz,
            "view_px" to listOf(view.width, view.height),
            "plan_name" to prefs.plan,
            "started_at_ms" to startedAtMs,
            "ended_at_ms" to System.currentTimeMillis(),
            "producer_stalls" to stalls,
            "trials" to trials,
        ))
        LabFiles.writeJson(dir, "device_profile.json", DeviceProfile.collect(this))
        overlay.visibility = View.VISIBLE
        overlay.text = (if (completed) "SESSION COMPLETE" else "ABORTED") + "\n${s.sessionId}\n${trials.size} trials\n\n" +
            "Results saved. Use EXPORT RESULTS on the main screen."
        overlay.setOnClickListener { finish() }
    }

    override fun onDestroy() {
        running = false
        producer?.interrupt()
        Choreographer.getInstance().removeFrameCallback(this)
        super.onDestroy()
    }

    companion object {
        fun buildPlan(prefs: LabPrefs): List<TrialSpec> {
            val top = prefs.topConfigs()
            return when (LabPrefs.PLANS.indexOf(prefs.plan).coerceAtLeast(0)) {
                0 -> Plans.coarse() + Plans.ghostPacketProof()
                1 -> Plans.fine(top) + Plans.confirm(top)
                2 -> Plans.robustness(top, Stage.DISTANCE)
                3 -> Plans.payloadLadder(top.first())
                4 -> Plans.decoderCompare()
                else -> Plans.ghostPacketProof()
            }
        }

        fun render(slot: TxSlot): VisualFrame = when (val spec = slot.spec) {
            is QrSpec -> QrCodec.encode(slot.frameBytes, spec)
            is GridSpec -> GridCodec.encode(slot.frameBytes, spec)
        }

        fun toBitmap(f: VisualFrame): Bitmap = Bitmap.createBitmap(f.argb, f.width, f.height, Bitmap.Config.ARGB_8888)
    }
}

/** Draws the current symbol bitmap scaled to fit, nearest-neighbour (no filtering), on white. */
class SlotView(ctx: Context) : View(ctx) {
    private var bmp: Bitmap? = null
    private val paint = Paint().apply { isFilterBitmap = false; isAntiAlias = false; isDither = false }
    private val dst = Rect()

    fun show(b: Bitmap) { bmp = b; invalidate() }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.WHITE)
        val b = bmp ?: return
        val scale = minOf(width.toFloat() / b.width, height.toFloat() / b.height)
        val w = (b.width * scale).toInt()
        val h = (b.height * scale).toInt()
        dst.set((width - w) / 2, (height - h) / 2, (width - w) / 2 + w, (height - h) / 2 + h)
        canvas.drawBitmap(b, null, dst, paint)
    }
}
