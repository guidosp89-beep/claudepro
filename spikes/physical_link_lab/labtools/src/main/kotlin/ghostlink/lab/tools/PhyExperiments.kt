package ghostlink.lab.tools

import ghostlink.lab.benchmark.FrameTiming
import ghostlink.lab.benchmark.PhyChoice
import ghostlink.lab.benchmark.ReceiverSession
import ghostlink.lab.benchmark.ResultStore
import ghostlink.lab.benchmark.SessionMeta
import ghostlink.lab.benchmark.SlotKind
import ghostlink.lab.benchmark.TransmitterSession
import ghostlink.lab.benchmark.TrialSpec
import ghostlink.lab.benchmark.TxInfo
import ghostlink.lab.benchmark.TxSlot
import ghostlink.lab.codecs.FrameType
import ghostlink.lab.codecs.LabFrame
import ghostlink.lab.codecs.SchemeId
import ghostlink.lab.codecs.Stage
import ghostlink.lab.codecs.visual.GridCodec
import ghostlink.lab.codecs.visual.GridSpec
import ghostlink.lab.codecs.visual.QrCodec
import ghostlink.lab.codecs.visual.QrEcc
import ghostlink.lab.codecs.visual.QrSpec
import ghostlink.lab.codecs.visual.Rasterizer
import ghostlink.lab.codecs.visual.VisualSpec
import ghostlink.lab.decoder.FrameDecoder
import ghostlink.lab.decoder.GridImageDecoder
import ghostlink.lab.decoder.ZxingQrDecoder
import ghostlink.lab.generators.AmbientClass
import ghostlink.lab.generators.CameraModel
import ghostlink.lab.generators.CameraSimulator
import ghostlink.lab.generators.Scene
import ghostlink.lab.generators.ScreenModel
import ghostlink.lab.generators.TimingParams
import ghostlink.lab.generators.TimingSimulator
import ghostlink.lab.generators.VeilSimulator
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.random.Random

private val SCREEN = ScreenModel()
private val ASPECT = SCREEN.heightPx.toDouble() / SCREEN.widthPx

/** PHY configurations pre-screened in the simulator (superset of the physical coarse plan). */
fun simConfigs(): List<Pair<String, VisualSpec>> {
    val qr = listOf(6 to QrEcc.M, 8 to QrEcc.M, 10 to QrEcc.M, 15 to QrEcc.L, 20 to QrEcc.L, 20 to QrEcc.M, 20 to QrEcc.Q, 20 to QrEcc.H, 25 to QrEcc.L, 30 to QrEcc.L)
        .map { (v, e) -> QrSpec(v, e) }
    val grids = listOf(
        PhyChoice.Grid(40, 1), PhyChoice.Grid(48, 1), PhyChoice.Grid(64, 1), PhyChoice.Grid(96, 1), PhyChoice.Grid(128, 1),
        PhyChoice.Grid(40, 2), PhyChoice.Grid(48, 2), PhyChoice.Grid(64, 2), PhyChoice.Grid(96, 2),
        PhyChoice.Grid(48, 3, 48), PhyChoice.Grid(64, 3, 48),
        PhyChoice.Grid(64, 1, 32, 2),
    ).map { it.resolve(ASPECT) }
    return (qr + grids).map { it.key() to it }
}

fun renderFrame(spec: VisualSpec, bytes: ByteArray) = when (spec) {
    is QrSpec -> QrCodec.encode(bytes, spec)
    is GridSpec -> GridCodec.encode(bytes, spec)
}

fun decoderFor(spec: VisualSpec): FrameDecoder = when (spec) {
    is QrSpec -> ZxingQrDecoder()
    is GridSpec -> GridImageDecoder(spec)
}

/** Simulated camera pre-screening (brief §70): success vs distance/angle/light/motion/JPEG/occlusion. */
object CamSimSweep {
    data class Cond(val group: String, val scene: Scene, val camera: CameraModel)

    fun run(out: File, quick: Boolean) {
        val seeds = if (quick) 2 else 5
        val cam1080 = CameraModel()
        val cam720 = CameraModel(width = 1280, height = 720)
        val cam4k = CameraModel(width = 3840, height = 2160, opticsBlurSigmaPx = 1.0)
        val pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
        val csv = Csv(File(out, "camsim_sweep.csv"), listOf(
            "config", "codec", "capacity_bytes", "group", "analysis_res", "distance_cm", "angle_deg", "light", "zoom",
            "motion_blur_px", "jpeg_q", "occlusion", "glare", "mixed", "seeds", "success_rate", "decode_ms_p50_jvm",
            "px_per_module", "predicted_goodput_KBps_at_15fps",
        ))
        val futures = ArrayList<Future<*>>()
        for ((key, spec) in simConfigs()) futures.add(pool.submit {
            val rnd = Random(key.hashCode().toLong())
            val frames = (0 until 2).map {
                val b = LabFrame(FrameType.DATA, 7, 1, SchemeId.RAPTORQ, it.toLong(), 262_144, rnd.nextBytes(spec.frameCapacity() - LabFrame.OVERHEAD_BYTES)).encode()
                b to Rasterizer.toScreen(renderFrame(spec, b), SCREEN.widthPx, SCREEN.heightPx)
            }
            fun eval(c: Cond): Double {
                val dec = decoderFor(spec)
                var ok = 0
                val lat = ArrayList<Double>()
                for (s in 0 until seeds) {
                    val (bytes, screen) = frames[s % frames.size]
                    val r = Random(s * 101L + key.hashCode())
                    val scene = c.scene.copy(seed = s.toLong() + 1, rollDeg = c.scene.rollDeg + r.nextDouble(-3.0, 3.0),
                        offsetMm = r.nextDouble(-8.0, 8.0) to r.nextDouble(-8.0, 8.0))
                    val mixedNext = if (c.scene.mixedFraction > 0) frames[(s + 1) % frames.size].second else null
                    val img = CameraSimulator(SCREEN, c.camera).capture(screen, scene, mixedNext)
                    val a = dec.decode(img)
                    lat.add(a.totalNs / 1e6)
                    if (a.data?.contentEquals(bytes) == true) ok++
                }
                val rate = ok.toDouble() / seeds
                val sc = c.scene
                val shortSidePx = c.camera.focalPx * sc.zoom * SCREEN.widthMm / (sc.distanceCm * 10)
                val modules = when (spec) { is QrSpec -> QrCodec.modules(spec.version) + 8; is GridSpec -> spec.cols + 4 }
                csv.row(key, if (spec is QrSpec) "QR" else "GRID", spec.frameCapacity(), c.group, "${c.camera.width}x${c.camera.height}",
                    sc.distanceCm, sc.angleDeg, sc.ambient.label, sc.zoom, sc.motionBlurPx, sc.jpegQuality, sc.occlusion, sc.glare,
                    sc.mixedFraction, seeds, rate, pct(lat, 50.0), shortSidePx / modules,
                    rate * (spec.frameCapacity() - LabFrame.OVERHEAD_BYTES) * 15 * 0.97 / 1024.0)
                return rate
            }
            for (cam in listOf(cam1080, cam720, cam4k)) {
                for (d in listOf(20.0, 40.0, 60.0, 100.0, 150.0, 200.0, 300.0)) {
                    if (eval(Cond("distance", Scene(distanceCm = d), cam)) == 0.0 && d >= 60) break
                }
            }
            for (a in listOf(15.0, 30.0, 45.0)) eval(Cond("angle", Scene(distanceCm = 40.0, angleDeg = a), cam1080))
            for (l in listOf(AmbientClass.INDOOR_DIM, AmbientClass.INDOOR_BRIGHT, AmbientClass.OUTDOOR_SHADE)) eval(Cond("light", Scene(distanceCm = 40.0, ambient = l), cam1080))
            for (m in listOf(3.0, 6.0)) eval(Cond("motion", Scene(distanceCm = 40.0, motionBlurPx = m), cam1080))
            for (q in listOf(70, 40)) eval(Cond("jpeg", Scene(distanceCm = 40.0, jpegQuality = q), cam1080))
            eval(Cond("occlusion", Scene(distanceCm = 40.0, occlusion = true), cam1080))
            eval(Cond("glare", Scene(distanceCm = 40.0, glare = true), cam1080))
            eval(Cond("wb_error", Scene(distanceCm = 40.0, wbError = 0.15), cam1080))
            eval(Cond("rolling_mixed", Scene(distanceCm = 40.0, mixedFraction = 0.5), cam1080))
            for (d in listOf(100.0, 150.0, 200.0)) eval(Cond("zoom2x", Scene(distanceCm = d, zoom = 2.0), cam1080))
            println("  done $key")
        })
        futures.forEach { it.get() }
        pool.shutdown()
        csv.close()
    }
}

/** Candidates E/F: clean-capture analysis of display refresh vs camera fps vs rolling shutter. */
object TimingSweep {
    fun run(out: File, quick: Boolean) {
        Csv(File(out, "timing_sweep.csv"), listOf("refresh_hz", "hold", "tx_fps", "camera_fps", "exposure_ms", "readout_ms",
            "perpendicular", "response_ms", "tiles", "clean_frame_fraction", "unique_symbols_per_s", "unique_tile_symbols_per_s", "worst_phase_symbols_per_s")).use { csv ->
            for (refresh in listOf(60.0, 90.0, 120.0)) for (hold in listOf(1, 2, 3, 4, 6, 8, 12)) for (camFps in listOf(30.0, 60.0))
                for (exp in listOf(1.0, 4.0, 8.0, 16.0)) for (ro in listOf(10.0, 25.0)) for (perp in listOf(true, false))
                    for (resp in listOf(0.5, 6.0)) for (tiles in listOf(1, 2, 4)) {
                        if (refresh / hold > 60.5 || refresh / hold < 4.9) continue
                        if (quick && (resp > 1 || !perp)) continue
                        val p = TimingParams(refresh, hold, camFps, exp, ro, perpendicular = perp, responseMs = resp, tiles = tiles)
                        val r = TimingSimulator.simulate(p, seconds = if (quick) 4.0 else 12.0, phases = if (quick) 3 else 8)
                        csv.row(refresh, hold, p.txFps, camFps, exp, ro, perp, resp, tiles, r.cleanFrameFraction, r.uniqueSymbolsPerSec, r.uniqueTileSymbolsPerSec, r.worstPhaseSymbolsPerSec)
                    }
        }
    }
}

/** Candidate G feasibility sweep. */
object VeilSweep {
    fun run(out: File, quick: Boolean) {
        val sim = CameraSimulator(SCREEN, CameraModel())
        Csv(File(out, "veil_sweep.csv"), listOf("delta_levels", "block_px", "channel", "distance_cm", "exposure_mix", "ber", "psnr_single_frame_db", "bits_per_frame_pair")).use { csv ->
            val deltas = if (quick) listOf(2, 6) else listOf(1, 2, 3, 4, 6, 8)
            for (ch in VeilSimulator.Channel.entries) for (block in listOf(24, 48)) for (d in listOf(20.0, 40.0)) for (mix in listOf(0.0, 0.25, 0.5))
                for (delta in deltas) {
                    if (quick && (block == 24 || d == 20.0)) continue
                    val r = VeilSimulator.run(sim, delta, block, ch, d, mix)
                    csv.row(delta, block, ch.name, d, mix, r.ber, r.psnrSingleFrameDb, r.bitsPerFramePair)
                    println("  veil ${ch.name} block=$block d=$d mix=$mix Δ=$delta BER=%.3f PSNR=%.1f".format(r.ber, r.psnrSingleFrameDb))
                }
        }
    }
}

/**
 * Whole protocol in simulation: TransmitterSession slots -> display/camera timing -> camera simulator
 * -> ReceiverSession. Produces a runs.csv with source=SIMULATED for the analysis pipeline.
 */
object VirtualLoopRun {
    fun run(out: File, quick: Boolean) {
        val payload = if (quick) 16 * 1024 else 64 * 1024
        val plan = listOf(
            TrialSpec(PhyChoice.Qr(10, QrEcc.M), 10, SchemeId.RAPTORQ, payload / 4, Stage.COARSE, maxDurationMs = 15_000),
            TrialSpec(PhyChoice.Qr(20, QrEcc.L), 15, SchemeId.RAPTORQ, payload, Stage.COARSE, maxDurationMs = 20_000),
            TrialSpec(PhyChoice.Qr(20, QrEcc.L), 15, SchemeId.SEQUENTIAL, payload, Stage.COARSE, maxDurationMs = 20_000),
            TrialSpec(PhyChoice.Qr(20, QrEcc.L), 15, SchemeId.LT, payload, Stage.COARSE, maxDurationMs = 20_000),
            TrialSpec(PhyChoice.Grid(64, 1), 15, SchemeId.RAPTORQ, payload, Stage.COARSE, maxDurationMs = 20_000),
            TrialSpec(PhyChoice.Grid(48, 2), 15, SchemeId.RAPTORQ, payload, Stage.COARSE, maxDurationMs = 20_000),
            TrialSpec(PhyChoice.Grid(96, 1), 15, SchemeId.RAPTORQ, payload, Stage.COARSE, maxDurationMs = 20_000),
            TrialSpec(PhyChoice.Qr(10, QrEcc.M), 10, SchemeId.RAPTORQ, 8 * 1024, Stage.GHOSTPACKET, maxDurationMs = 20_000, ghostPacket = true),
        )
        val conditions = if (quick) listOf(40.0) else listOf(40.0, 60.0, 100.0)
        val pool = Executors.newFixedThreadPool(conditions.size)
        conditions.map { d -> pool.submit { runOne(File(out, "virtual/d${d.toInt()}"), plan, d) } }.forEach { it.get() }
        pool.shutdown()
    }

    private fun runOne(dir: File, plan: List<TrialSpec>, distanceCm: Double) {
        dir.deleteRecursively(); dir.mkdirs()
        val sim = CameraSimulator(SCREEN, CameraModel())
        val camFps = 30.0
        var simNs = 0L
        val tx = TransmitterSession("M1_SIM_D${distanceCm.toInt()}", plan, ASPECT, TxInfo("SIMULATED-TX", SCREEN.widthPx, SCREEN.heightPx, 6000))
        val rx = ReceiverSession(
            SessionMeta("SIMULATED-RX", distanceCm.toInt(), 0, "INDOOR_NORMAL", "STATIC", "zxing-java", "AUTO", "1920x1080", 1.0, source = "SIMULATED"),
            ZxingQrDecoder(), store = ResultStore(dir), clockNs = { simNs },
        )
        val rnd = Random(distanceCm.toLong())
        val cache = HashMap<Int, IntArray>()
        fun screenOf(s: TxSlot): IntArray = cache.getOrPut(s.cacheKey) {
            if (cache.size > 64) cache.clear()
            Rasterizer.toScreen(renderFrame(s.spec, s.frameBytes), SCREEN.widthPx, SCREEN.heightPx)
        }
        val it = tx.slots().iterator()
        var cur: TxSlot? = if (it.hasNext()) it.next() else null
        var next: TxSlot? = if (it.hasNext()) it.next() else null
        var slotStartNs = 0L
        var nextCameraNs = 0L
        val pMixed = 1.0 - TimingSimulator.simulate(TimingParams(60.0, 4, camFps, 8.0, 25.0)).cleanFrameFraction
        while (cur != null && !rx.isEnded) {
            val slotNs = 1_000_000_000L / cur.fps
            val st = tx.statsFor(cur.trialIndex)
            if (cur.kind == SlotKind.DATA) {
                if (st.firstDataShownMs == 0L) st.firstDataShownMs = slotStartNs / 1_000_000
                st.lastDataShownMs = slotStartNs / 1_000_000; st.dataSlotsShown++
            }
            // Skip camera work once the receiver finished this trial, or if after 4 s of data it decoded
            // nothing at all (hopeless config): keeps the simulation tractable. Documented in the report.
            val hopeless = cur.kind == SlotKind.DATA && st.dataSlotsShown > 4L * cur.fps && rx.currentTrial == cur.trialIndex && rx.currentDecodedFrames == 0L
            val skip = (cur.kind == SlotKind.DATA && rx.currentTrial == cur.trialIndex && rx.currentTrialComplete) || hopeless
            while (nextCameraNs < slotStartNs + slotNs) {
                if (!skip) {
                    simNs = nextCameraNs
                    val mixed = next != null && rnd.nextDouble() < pMixed
                    val scene = Scene(distanceCm = distanceCm, seed = rnd.nextLong(), rollDeg = rnd.nextDouble(-2.0, 2.0),
                        mixedFraction = if (mixed) rnd.nextDouble(0.2, 0.8) else 0.0)
                    val img = sim.capture(screenOf(cur), scene, if (mixed) screenOf(next!!) else null)
                    rx.noteFrameSize(img.width, img.height)
                    rx.process(img, FrameTiming(nextCameraNs, 0, 0))
                }
                nextCameraNs += (1e9 / camFps).toLong()
            }
            slotStartNs += slotNs
            cur = next
            next = if (it.hasNext()) it.next() else null
        }
        rx.finishCurrent("SIM_END")
        println("  virtual d=$distanceCm: " + rx.records.joinToString { "${it["visual_key"]}/${it["scheme"]}=${it["RESULT"]}" })
    }
}

/** Small set of simulated camera frames for inspection (datasets/generated, git-ignored). */
object Datasets {
    fun run(out: File, quick: Boolean) {
        val dir = File("datasets/generated").also { it.mkdirs() }
        val rnd = Random(5)
        val sim = CameraSimulator(SCREEN, CameraModel())
        val picks = simConfigs().filter { it.first in setOf("QR-v20-L", "GRID-64x140-b1-p32", "GRID-48x104-b2-p32", "GRID-48x104-b3-p48") }
        for ((key, spec) in picks) for ((name, scene) in listOf(
            "d40" to Scene(distanceCm = 40.0), "d100" to Scene(distanceCm = 100.0), "a30" to Scene(distanceCm = 40.0, angleDeg = 30.0),
            "dim" to Scene(distanceCm = 40.0, ambient = AmbientClass.INDOOR_DIM), "motion6" to Scene(distanceCm = 40.0, motionBlurPx = 6.0),
        )) {
            val b = LabFrame(FrameType.DATA, 1, 1, SchemeId.RAPTORQ, 0, 1, rnd.nextBytes(spec.frameCapacity() - LabFrame.OVERHEAD_BYTES)).encode()
            val img = sim.capture(Rasterizer.toScreen(renderFrame(spec, b), SCREEN.widthPx, SCREEN.heightPx), scene)
            CameraSimulator.savePng(img, File(dir, "${key}_$name.png"))
        }
        println("  wrote ${dir.listFiles()?.size} PNGs to ${dir.path}")
    }
}

/**
 * Camera-pipeline stage timing on the JVM (brief §40): binarize/preprocess, detect, sample/classify,
 * error correction, plus erasure-decoder time per symbol. Desktop JVM numbers: phones are expected
 * to be several times slower; the Android receiver records the same stages on device.
 */
object PipelineBench {
    fun run(out: File, quick: Boolean) {
        val n = if (quick) 5 else 25
        val picks = simConfigs().filter { it.first in setOf("QR-v10-M", "QR-v20-L", "GRID-48x108-b1-p32", "GRID-48x108-b2-p32", "GRID-64x144-b1-p32", "GRID-48x108-b3-p48") }
        Csv(File(out, "pipeline_bench.csv"), listOf("config", "analysis_res", "frames", "ok_frames", "preprocess_ms", "detect_ms", "sample_ms", "ecc_ms", "total_ms_p50", "total_ms_p90", "fec_add_ms_per_symbol")).use { csv ->
            for ((key, spec) in picks) for (cam in listOf(CameraModel(), CameraModel(width = 1280, height = 720))) {
                val sim = CameraSimulator(SCREEN, cam)
                val rnd = Random(9)
                val bytes = LabFrame(FrameType.DATA, 1, 1, SchemeId.RAPTORQ, 0, 1, rnd.nextBytes(spec.frameCapacity() - LabFrame.OVERHEAD_BYTES)).encode()
                val screen = Rasterizer.toScreen(renderFrame(spec, bytes), SCREEN.widthPx, SCREEN.heightPx)
                val dec = decoderFor(spec)
                val frames = (0 until 4).map { sim.capture(screen, Scene(distanceCm = 25.0, seed = it.toLong())) }
                repeat(5) { dec.decode(frames[it % frames.size]) } // warm-up
                val pre = ArrayList<Double>(); val det = ArrayList<Double>(); val sam = ArrayList<Double>(); val ecc = ArrayList<Double>(); val tot = ArrayList<Double>()
                var ok = 0
                for (i in 0 until n) {
                    val a = dec.decode(frames[i % frames.size])
                    if (a.ok) ok++
                    pre.add(a.preprocessNs / 1e6); det.add(a.detectNs / 1e6); sam.add(a.sampleNs / 1e6); ecc.add(a.eccNs / 1e6); tot.add(a.totalNs / 1e6)
                }
                // Erasure layer cost per received symbol (RaptorQ, 256 KB object, this PHY's symbol size).
                val t = spec.frameCapacity() - LabFrame.OVERHEAD_BYTES
                val payload = ghostlink.lab.codecs.DeterministicPayload.generate(3, 262_144)
                val enc = ghostlink.lab.codecs.erasure.Erasure.encoder(SchemeId.RAPTORQ, payload, t)
                val ed = ghostlink.lab.codecs.erasure.Erasure.decoder(SchemeId.RAPTORQ, payload.size, t)
                val syms = (0 until enc.sourceSymbols + 5).map { (it * 3).toLong() to enc.symbol((it * 3).toLong()) }
                val t0 = System.nanoTime()
                var used = 0
                for ((id, s) in syms) { ed.add(id, s); used++; if (ed.isComplete) break }
                val fecMs = (System.nanoTime() - t0) / 1e6 / used
                csv.row(key, "${cam.width}x${cam.height}", n, ok, pre.average(), det.average(), sam.average(), ecc.average(), pct(tot, 50.0), pct(tot, 90.0), fecMs)
                println("  pipeline $key ${cam.width}x${cam.height}: total p50=%.1f ms ok=$ok/$n".format(pct(tot, 50.0)))
            }
        }
    }
}
