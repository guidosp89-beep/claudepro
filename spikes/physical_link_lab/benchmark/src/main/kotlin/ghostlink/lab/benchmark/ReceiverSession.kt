package ghostlink.lab.benchmark

import ghostlink.lab.codecs.DeterministicPayload
import ghostlink.lab.codecs.FrameFormatException
import ghostlink.lab.codecs.FrameType
import ghostlink.lab.codecs.LabFrame
import ghostlink.lab.codecs.TrialConfig
import ghostlink.lab.codecs.erasure.AddResult
import ghostlink.lab.codecs.erasure.Erasure
import ghostlink.lab.codecs.erasure.ErasureDecoder
import ghostlink.lab.codecs.ghost.GhostFrameV0
import ghostlink.lab.codecs.ghost.ObjSymbolPacket
import ghostlink.lab.codecs.visual.GridSpec
import ghostlink.lab.codecs.visual.QrSpec
import ghostlink.lab.decoder.DecodeAttempt
import ghostlink.lab.decoder.FrameDecoder
import ghostlink.lab.decoder.GridImageDecoder
import ghostlink.lab.decoder.YuvFrame

/** Per camera frame timing provided by the platform (ns; 0 = unknown). */
class FrameTiming(val sensorTimestampNs: Long, val captureToAnalysisNs: Long, val copyNs: Long)

/**
 * Receiver-side trial tracking and metrics (brief §15–§17, §40). Platform-neutral: the Android
 * analyzer and the cloud virtual loop both call [process] for every analysed camera frame.
 *
 * Decoding policy per frame: if the current trial uses a grid PHY the grid decoder runs first and the
 * QR decoder only every [announceProbeEvery] failed frames (to catch announces); otherwise the QR decoder
 * runs. Extra QR decoders (decoder comparison) run on the same frame and are only measured.
 */
class ReceiverSession(
    private val meta: SessionMeta,
    private val qrPrimary: FrameDecoder,
    private val qrExtras: List<FrameDecoder> = emptyList(),
    private val store: ResultStore? = null,
    private val probe: ResourceProbe? = null,
    private val clockNs: () -> Long = System::nanoTime,
    private val announceProbeEvery: Int = 3,
    /** A trial is closed if nothing for it was decoded for this long after its planned end. */
    private val trialGraceMs: Long = 4_000,
) {
    val records = ArrayList<RunRecord>()
    private var state: TrialState? = null
    private var sessionId: String? = null
    private var ended = false
    private val gridDecoders = HashMap<GridSpec, GridImageDecoder>()
    /** TX reports keyed by trial index, possibly arriving after the trial closed. */
    private val txReports = HashMap<Int, TxTrialReport>()
    private var lastTx: TxInfo? = null
    var framesTotal = 0L
        private set
    val isEnded: Boolean get() = ended
    val currentTrial: Int? get() = state?.index
    val currentSessionId: String? get() = sessionId

    /** Summary line for on-screen status. */
    fun status(): String {
        val s = state ?: return if (ended) "session ended (${records.size} runs)" else "waiting for announce… (${records.size} runs)"
        val k = s.decoder?.sourceSymbols ?: 0
        return "trial ${s.index + 1}/${s.config?.trialCount ?: "?"} ${s.config?.visual?.key() ?: "?"} " +
            "symbols ${s.decoder?.uniqueSymbols ?: 0}/$k ${if (s.complete) "DONE" else ""} runs=${records.size}"
    }

    fun process(frame: YuvFrame, timing: FrameTiming): String? {
        framesTotal++
        val now = clockNs()
        val s = state
        s?.onCameraFrame(timing)
        // 1. Primary decoder for the current trial PHY.
        val grid = (s?.config?.visual as? GridSpec)
        val attempts = ArrayList<Pair<String, DecodeAttempt>>(2)
        var parsed: LabFrame? = null
        if (grid != null) {
            val a = gridDecoders.getOrPut(grid) { GridImageDecoder(grid) }.decode(frame)
            attempts.add("grid" to a)
            parsed = LabFrame.parseOrNull(a.data)
            if (parsed == null && s != null && (s.failedSinceProbe++ % announceProbeEvery == 0)) {
                val q = qrPrimary.decode(frame)
                attempts.add(qrPrimary.name to q)
                parsed = LabFrame.parseOrNull(q.data)
            }
        } else {
            val q = qrPrimary.decode(frame)
            attempts.add(qrPrimary.name to q)
            parsed = LabFrame.parseOrNull(q.data)
        }
        val extras = if (qrExtras.isNotEmpty() && grid == null) qrExtras.map { it.name to it.decode(frame) } else emptyList()
        val outcome = handleFrame(parsed, now)
        state?.recordAttempts(attempts, extras, parsed, timing)
        closeIfExpired(now)
        return outcome
    }

    private fun handleFrame(f: LabFrame?, now: Long): String? {
        if (f == null) return null
        if (sessionId != null && f.sessionTag != LabFrame.sessionTagOf(sessionId!!)) {
            // A different session started: close everything from the old one.
            finishCurrent("NEW_SESSION")
            sessionId = null
        }
        return when (f.type) {
            FrameType.ANNOUNCE -> onAnnounce(f, now)
            FrameType.DATA, FrameType.GHOST -> onData(f, now)
            FrameType.END -> {
                val (tx, prev) = try { Announce.parseEnd(f.body) } catch (e: FrameFormatException) { null to null }
                if (tx != null) lastTx = tx
                if (prev != null) txReports[prev.trialIndex] = prev
                finishCurrent("END")
                patchReportedTrials()
                store?.rewriteCsv(records)
                ended = true
                "END"
            }
            FrameType.PLAN -> null
        }
    }

    private fun onAnnounce(f: LabFrame, now: Long): String? {
        val a = try { Announce.parse(f.body) } catch (e: FrameFormatException) { return null }
        if (sessionId == null) sessionId = a.config.sessionId
        lastTx = a.tx
        a.previous?.let { txReports[it.trialIndex] = it }
        val s = state
        if (s != null && s.index == a.config.trialIndex) {
            if (s.config == null) s.adoptConfig(a.config)
            s.announceFrames++
            return null
        }
        if (s != null && a.config.trialIndex < s.index) return null // stale re-announce
        finishCurrent(if (s?.complete == true) "COMPLETE" else "NEXT_ANNOUNCE")
        state = TrialState(a.config.trialIndex, now).also { it.adoptConfig(a.config); it.announceFrames = 1; it.firstAnnounceNs = now }
        patchReportedTrials()
        return "ANNOUNCE ${a.config.trialIndex}"
    }

    private fun onData(f: LabFrame, now: Long): String? {
        var s = state
        if (s == null || s.index != f.trial) {
            if (s != null && f.trial < s.index) { s.framesOther++; return null }
            finishCurrent(if (s?.complete == true) "COMPLETE" else "NEXT_DATA")
            if (sessionId == null) return null // cannot verify payload without the session id (announce)
            s = TrialState(f.trial, now).also { it.announceMissed = true }
            state = s
        }
        return s.onSymbol(f, now)
    }

    private fun closeIfExpired(now: Long) {
        val s = state ?: return
        val cfg = s.config ?: return
        val deadline = s.startNs + (cfg.durationMs + 2_000 + trialGraceMs) * 1_000_000L
        val idle = s.lastDecodedNs > 0 && now - s.lastDecodedNs > trialGraceMs * 1_000_000L * 2
        if (now > deadline || (s.complete && idle)) finishCurrent(if (s.complete) "COMPLETE" else "TIMEOUT")
    }

    fun finishCurrent(reason: String) {
        val s = state ?: return
        state = null
        val rec = s.toRecord(reason)
        records.add(rec)
        store?.append(rec, s.raw(rec))
    }

    /** Fill sender-side numbers that arrived after a trial was closed (rows already written keep blanks). */
    private fun patchReportedTrials() {
        for (r in records) {
            val idx = (r["trial_index"] as? Int) ?: continue
            val rep = txReports[idx] ?: continue
            if (r["frames_generated"] == null) {
                r.fields["frames_generated"] = rep.framesGenerated
                r.fields["actual_visual_fps"] = rep.actualFps
            }
        }
    }

    // ------------------------------------------------------------------------------------------

    private inner class TrialState(val index: Int, val startNs: Long) {
        var config: TrialConfig? = null
        var decoder: ErasureDecoder? = null
        var expectedSha: ByteArray? = null
        var ghost: GhostTrial? = null
        var ghostOk = 0
        var ghostTotal = 0
        var announceMissed = false
        var announceFrames = 0
        var firstAnnounceNs = 0L
        var firstDataNs = 0L
        var completeNs = 0L
        var lastDecodedNs = 0L
        var complete = false
        var shaPass: Boolean? = null
        var framesSeen = 0L
        var framesDecoded = 0L
        var framesFailed = 0L
        var framesOther = 0L
        var framesDuplicate = 0L
        var bytesDecoded = 0L
        var failedSinceProbe = 0
        val failures = HashMap<String, Int>()
        val decodeMs = ArrayList<Double>()
        val captureMs = ArrayList<Double>()
        val copyMs = ArrayList<Double>()
        val preMs = ArrayList<Double>()
        val detMs = ArrayList<Double>()
        val samMs = ArrayList<Double>()
        val eccMs = ArrayList<Double>()
        val fecMs = ArrayList<Double>()
        val sensorTs = ArrayList<Long>()
        val extraOk = HashMap<String, Int>()
        val extraMs = HashMap<String, ArrayList<Double>>()
        val cpuStart = probe?.cpuTimeMs()
        val thermalStart = probe?.thermalStatus()
        val wallStartNs = clockNs()

        fun adoptConfig(c: TrialConfig) {
            config = c
            val tag = c.sessionTag
            val payload = DeterministicPayload.forTrial(tag, c.trialIndex, c.payloadBytes)
            expectedSha = DeterministicPayload.sha256(payload)
            if (c.stage == ghostlink.lab.codecs.Stage.GHOSTPACKET) {
                ghost = GhostTrial(tag, c.trialIndex, payload, c.visual.frameCapacity() - LabFrame.OVERHEAD_BYTES)
            }
            decoder = Erasure.decoder(c.scheme, c.payloadBytes, c.symbolSize)
        }

        fun onCameraFrame(t: FrameTiming) {
            framesSeen++
            if (t.sensorTimestampNs > 0) sensorTs.add(t.sensorTimestampNs)
            if (t.captureToAnalysisNs > 0) captureMs.add(t.captureToAnalysisNs / 1e6)
            if (t.copyNs > 0) copyMs.add(t.copyNs / 1e6)
        }

        fun recordAttempts(attempts: List<Pair<String, DecodeAttempt>>, extras: List<Pair<String, DecodeAttempt>>, parsed: LabFrame?, t: FrameTiming) {
            val primary = attempts.first().second
            decodeMs.add(attempts.sumOf { it.second.totalNs } / 1e6)
            preMs.add(primary.preprocessNs / 1e6); detMs.add(primary.detectNs / 1e6)
            samMs.add(primary.sampleNs / 1e6); eccMs.add(primary.eccNs / 1e6)
            if (parsed == null) {
                framesFailed++
                val reason = attempts.firstOrNull { it.second.data != null }?.let { "crc_or_format" } ?: (primary.failure ?: "unknown")
                failures[reason] = (failures[reason] ?: 0) + 1
            }
            for ((name, a) in extras) {
                extraMs.getOrPut(name) { ArrayList() }.add(a.totalNs / 1e6)
                if (LabFrame.parseOrNull(a.data) != null) extraOk[name] = (extraOk[name] ?: 0) + 1
            }
        }

        fun onSymbol(f: LabFrame, now: Long): String? {
            if (config == null && decoder == null) {
                // Joined mid-stream without any announce: build the erasure decoder from the frame header.
                val symbolSize = if (f.type == FrameType.GHOST) return null else f.body.size
                decoder = Erasure.decoder(f.scheme, f.payloadBytes.toInt(), symbolSize)
                val tag = LabFrame.sessionTagOf(sessionId!!)
                expectedSha = DeterministicPayload.sha256(DeterministicPayload.forTrial(tag, f.trial, f.payloadBytes.toInt()))
            }
            framesDecoded++
            bytesDecoded += f.body.size + LabFrame.OVERHEAD_BYTES
            lastDecodedNs = now
            if (firstDataNs == 0L) firstDataNs = now
            if (complete) { framesDuplicate++; return null }
            val dec = decoder ?: return null
            val t0 = clockNs()
            val result = if (f.type == FrameType.GHOST) addGhost(f, dec) else dec.add(f.symbolId, f.body)
            fecMs.add((clockNs() - t0) / 1e6)
            if (result == AddResult.DUPLICATE) framesDuplicate++
            if (result == AddResult.COMPLETE || dec.isComplete) {
                complete = true
                completeNs = now
                val out = dec.result()
                shaPass = out != null && expectedSha != null && DeterministicPayload.sha256(out).contentEquals(expectedSha)
                return "COMPLETE ${index}"
            }
            return null
        }

        private fun addGhost(f: LabFrame, dec: ErasureDecoder): AddResult {
            val g = ghost ?: return AddResult.REJECTED
            ghostTotal++
            val pkt = try { ObjSymbolPacket.parse(GhostFrameV0.unwrap(f.body)) } catch (e: FrameFormatException) { return AddResult.REJECTED }
            val expected = g.packet(f.symbolId).encode()
            if (pkt.encode().contentEquals(expected) && f.body.contentEquals(g.ghostFrame(f.symbolId))) ghostOk++
            var last = AddResult.DUPLICATE
            for ((_, esi, sym) in pkt.symbols) {
                val r = dec.add(esi.toLong(), sym)
                if (r == AddResult.COMPLETE) return r
                if (r == AddResult.ACCEPTED) last = r
            }
            return last
        }

        private fun fps(): Double? {
            if (sensorTs.size < 3) return null
            val span = (sensorTs.last() - sensorTs.first()) / 1e9
            return if (span > 0) (sensorTs.size - 1) / span else null
        }

        private fun dropped(): Long {
            if (sensorTs.size < 3) return 0
            val gaps = sensorTs.zipWithNext { a, b -> (b - a).toDouble() }.filter { it > 0 }
            val median = percentile(gaps, 50.0) ?: return 0
            return gaps.sumOf { g -> if (g > 1.5 * median) (kotlin.math.round(g / median) - 1).toLong() else 0L }
        }

        fun toRecord(reason: String): RunRecord {
            val c = config
            val dec = decoder
            val sid = c?.sessionId ?: sessionId ?: "unknown"
            val visual = c?.visual
            val transferNs = if (complete && firstDataNs > 0) (completeNs - firstDataNs) + (1e9 / (c?.targetFps ?: 10)).toLong() else 0L
            val transferS = transferNs / 1e9
            val payload = c?.payloadBytes ?: 0
            val result = when {
                complete && shaPass == true -> "PASS"
                complete -> "FAIL_HASH"
                framesDecoded == 0L -> "FAIL_NO_DATA"
                else -> "FAIL_INCOMPLETE"
            }
            val wallS = (clockNs() - wallStartNs) / 1e9
            val cpuPct = if (probe != null && cpuStart != null && wallS > 0) (probe.cpuTimeMs() - cpuStart) / 10.0 / wallS else null
            val rep = txReports[index]
            val f = LinkedHashMap<String, Any?>()
            f["run_id"] = "${sid}_T%03d".format(index)
            f["session_id"] = sid
            f["trial_index"] = index
            f["stage"] = c?.stage?.name
            f["repetition"] = c?.repetition
            f["timestamp"] = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.ROOT)
                .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }.format(java.util.Date())
            f["source"] = meta.source
            f["sender_device"] = lastTx?.model
            f["receiver_device"] = meta.receiverDevice
            f["codec"] = when (visual) { is QrSpec -> "QR"; is GridSpec -> "GRID"; null -> null }
            f["codec_version"] = "lab1"
            f["visual_key"] = visual?.key()
            f["config_key"] = c?.configKey()
            f["qr_version"] = (visual as? QrSpec)?.version
            f["qr_ecc"] = (visual as? QrSpec)?.ecc?.name
            f["grid_cols"] = (visual as? GridSpec)?.cols
            f["grid_rows"] = (visual as? GridSpec)?.rows
            f["grid_bits_per_cell"] = (visual as? GridSpec)?.bitsPerCell
            f["grid_rs_parity"] = (visual as? GridSpec)?.rsParity
            f["grid_finder_module"] = (visual as? GridSpec)?.finderModule
            f["scheme"] = c?.scheme?.name ?: dec?.scheme?.name
            f["symbol_size"] = c?.symbolSize ?: dec?.symbolSize
            f["source_symbols"] = dec?.sourceSymbols
            f["payload_bytes"] = payload
            f["encoded_bytes"] = dec?.let { it.sourceSymbols.toLong() * (it.symbolSize + LabFrame.OVERHEAD_BYTES) }
            f["display_width"] = lastTx?.displayWidth
            f["display_height"] = lastTx?.displayHeight
            f["display_refresh_rate"] = lastTx?.refreshCentiHz?.let { it / 100.0 }
            f["camera_width"] = lastFrameW
            f["camera_height"] = lastFrameH
            f["camera_fps"] = fps()
            f["analysis_fps"] = if (wallS > 0) framesSeen / wallS else null
            f["target_visual_fps"] = c?.targetFps
            f["actual_visual_fps"] = rep?.actualFps
            f["distance_cm"] = meta.distanceCm
            f["angle_deg"] = meta.angleDeg
            f["ambient_light_class"] = meta.lightClass
            f["ambient_lux"] = probe?.ambientLux()
            f["motion"] = meta.motion
            f["qr_decoder"] = meta.qrDecoder
            f["exposure_mode"] = meta.exposureMode
            f["analysis_resolution"] = meta.analysisResolution
            f["zoom"] = meta.zoom
            f["frames_generated"] = rep?.framesGenerated
            f["frames_seen"] = framesSeen
            f["frames_decoded"] = framesDecoded
            f["frames_failed"] = framesFailed
            f["frames_other"] = framesOther
            f["frames_dropped"] = dropped()
            f["symbols_unique"] = dec?.uniqueSymbols
            f["symbols_duplicate"] = framesDuplicate
            f["fountain_overhead"] = if (complete && dec != null) dec.uniqueSymbols.toDouble() / dec.sourceSymbols - 1.0 else null
            f["transfer_seconds"] = if (complete) transferS else null
            f["latency_first_frame_ms"] = if (firstAnnounceNs > 0 && firstDataNs > 0) (firstDataNs - firstAnnounceNs) / 1e6 else null
            f["gross_bitrate"] = if (complete && transferS > 0) bytesDecoded * 8 / transferS else null
            f["net_bitrate"] = if (complete && transferS > 0) payload * 8 / transferS else null
            f["goodput_bytes_sec"] = if (result == "PASS" && transferS > 0) payload / transferS else 0.0
            f["decode_latency_ms"] = percentile(decodeMs, 50.0)
            f["decode_latency_p90_ms"] = percentile(decodeMs, 90.0)
            f["capture_to_analysis_ms"] = percentile(captureMs, 50.0)
            f["copy_ms"] = percentile(copyMs, 50.0)
            f["preprocess_ms"] = percentile(preMs, 50.0)
            f["detect_ms"] = percentile(detMs, 50.0)
            f["sample_ms"] = percentile(samMs, 50.0)
            f["ecc_ms"] = percentile(eccMs, 50.0)
            f["fec_ms"] = percentile(fecMs, 50.0)
            f["cpu_pct"] = cpuPct
            f["memory_mb"] = probe?.memoryMb()
            f["thermal_start"] = thermalStart
            f["thermal_end"] = probe?.thermalStatus()
            f["battery_delta"] = null // session-level only (brief §54)
            f["announce_missed"] = announceMissed
            f["ghost_packets_identical"] = if (ghost != null) ghostOk else null
            f["ghost_packets_total"] = if (ghost != null) ghostTotal else null
            f["failure_top"] = failures.maxByOrNull { it.value }?.let { "${it.key}:${it.value}" } ?: if (!complete) reason else null
            f["SHA256_PASS"] = shaPass ?: false
            f["RESULT"] = result
            f["notes"] = meta.notes.ifEmpty { null }
            return RunRecord(f)
        }

        fun raw(rec: RunRecord): Map<String, Any?> = linkedMapOf(
            "record" to rec.fields,
            "close_reason" to rec["failure_top"],
            "trial_config" to config?.let { mapOf("visual" to it.visual.key(), "fps" to it.targetFps, "scheme" to it.scheme.name, "symbol_size" to it.symbolSize, "K" to it.sourceSymbols, "duration_ms" to it.durationMs) },
            "decode_ms" to decodeMs,
            "capture_to_analysis_ms" to captureMs,
            "fec_ms" to fecMs,
            "failures" to failures,
            "decoder_compare" to extraMs.keys.associateWith { k -> mapOf("ok_frames" to (extraOk[k] ?: 0), "latency_ms_p50" to percentile(extraMs[k]!!, 50.0), "latency_ms_p90" to percentile(extraMs[k]!!, 90.0), "frames" to extraMs[k]!!.size) },
            "sensor_timestamps_ns" to sensorTs.take(4000),
            "meta" to mapOf("distance_cm" to meta.distanceCm, "angle_deg" to meta.angleDeg, "light" to meta.lightClass, "motion" to meta.motion),
        )
    }

    private var lastFrameW: Int? = null
    private var lastFrameH: Int? = null

    /** Platforms call this once per frame before [process] if they know the analysis resolution. */
    fun noteFrameSize(w: Int, h: Int) { lastFrameW = w; lastFrameH = h }
}
