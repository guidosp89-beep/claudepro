package ghostlink.lab.benchmark

import ghostlink.lab.codecs.SchemeId
import ghostlink.lab.codecs.Stage
import ghostlink.lab.codecs.visual.GridCodec
import ghostlink.lab.codecs.visual.GridSpec
import ghostlink.lab.codecs.visual.QrCodec
import ghostlink.lab.codecs.visual.QrEcc
import ghostlink.lab.codecs.visual.QrSpec
import ghostlink.lab.codecs.visual.Rasterizer
import ghostlink.lab.codecs.visual.VisualFrame
import ghostlink.lab.decoder.YuvFrame
import ghostlink.lab.decoder.ZxingQrDecoder
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** End-to-end TX schedule -> ideal "camera" -> RX session, without any image degradation. */
class VirtualLoopTest {
    private fun render(slot: TxSlot, w: Int, h: Int): YuvFrame {
        val vf: VisualFrame = when (val s = slot.spec) {
            is QrSpec -> QrCodec.encode(slot.frameBytes, s)
            is GridSpec -> GridCodec.encode(slot.frameBytes, s)
        }
        return YuvFrame.fromArgb(w, h, Rasterizer.toScreen(vf, w, h))
    }

    private fun run(plan: List<TrialSpec>, lossRate: Double = 0.0, seed: Int = 1): ReceiverSession {
        var simNs = 0L
        val dir = Files.createTempDirectory("vl").toFile()
        val tx = TransmitterSession("M1_TEST_${seed}", plan, 2.2, TxInfo("SimTX", 1080, 2400, 6000))
        val rx = ReceiverSession(
            SessionMeta("SimRX", 40, 0, "INDOOR_NORMAL", "STATIC", "zxing-java", "AUTO", "1280x720", 1.0, source = "SIMULATED"),
            ZxingQrDecoder(), store = ResultStore(dir), clockNs = { simNs },
        )
        val rnd = Random(seed)
        var lastKey = 0
        var lastFrame: YuvFrame? = null
        for (slot in tx.slots()) {
            simNs += 1_000_000_000L / slot.fps
            val st = tx.statsFor(slot.trialIndex)
            if (slot.kind == SlotKind.DATA) {
                if (st.firstDataShownMs == 0L) st.firstDataShownMs = simNs / 1_000_000
                st.lastDataShownMs = simNs / 1_000_000; st.dataSlotsShown++
            }
            if (rnd.nextDouble() < lossRate) continue
            val frame = if (slot.cacheKey == lastKey && lastFrame != null) lastFrame else render(slot, 540, 1200).also { lastFrame = it; lastKey = slot.cacheKey }
            rx.process(frame, FrameTiming(simNs, 0, 0))
            if (rx.isEnded) break
        }
        assertTrue(dir.resolve("runs.csv").readLines().size == rx.records.size + 1)
        return rx
    }

    @Test
    fun allSchemesAndPhysPassOnIdealChannel() {
        val plan = listOf(
            TrialSpec(PhyChoice.Qr(10, QrEcc.M), 10, SchemeId.RAPTORQ, 2048, Stage.MANUAL, maxDurationMs = 4000),
            TrialSpec(PhyChoice.Qr(15, QrEcc.L), 15, SchemeId.SEQUENTIAL, 3000, Stage.MANUAL, maxDurationMs = 4000),
            TrialSpec(PhyChoice.Qr(15, QrEcc.L), 15, SchemeId.LT, 3000, Stage.MANUAL, maxDurationMs = 4000),
            TrialSpec(PhyChoice.Grid(48, 1), 15, SchemeId.RAPTORQ, 4000, Stage.MANUAL, maxDurationMs = 4000),
            TrialSpec(PhyChoice.Grid(48, 2), 15, SchemeId.RAPTORQ, 4000, Stage.MANUAL, maxDurationMs = 4000),
            TrialSpec(PhyChoice.Qr(20, QrEcc.M), 10, SchemeId.RAPTORQ, 4096, Stage.GHOSTPACKET, maxDurationMs = 4000, ghostPacket = true),
        )
        val rx = run(plan)
        assertEquals(plan.size, rx.records.size, rx.records.joinToString { "${it["trial_index"]}:${it["RESULT"]}" })
        for (r in rx.records) {
            assertEquals("PASS", r["RESULT"], "trial ${r["trial_index"]} ${r["visual_key"]} ${r["failure_top"]}")
            assertEquals(true, r["SHA256_PASS"])
            assertTrue((r["goodput_bytes_sec"] as Double) > 0)
        }
        val ghost = rx.records.last()
        assertTrue((ghost["ghost_packets_total"] as Int) > 0)
        assertEquals(ghost["ghost_packets_total"], ghost["ghost_packets_identical"])
        // TX report arrives in later announces / END and is patched into the rows.
        assertTrue(rx.records.all { it["frames_generated"] != null }, "missing tx reports")
    }

    @Test
    fun fountainSurvivesFrameLossWhereSequentialIsSlower() {
        val plan = listOf(
            TrialSpec(PhyChoice.Qr(10, QrEcc.L), 15, SchemeId.RAPTORQ, 6000, Stage.MANUAL, maxDurationMs = 6000),
            TrialSpec(PhyChoice.Qr(10, QrEcc.L), 15, SchemeId.SEQUENTIAL, 6000, Stage.MANUAL, maxDurationMs = 6000),
        )
        val rx = run(plan, lossRate = 0.3, seed = 7)
        val rq = rx.records[0]
        assertEquals("PASS", rq["RESULT"], "${rq["failure_top"]}")
        val seq = rx.records[1]
        if (seq["RESULT"] == "PASS") assertTrue((seq["transfer_seconds"] as Double) >= (rq["transfer_seconds"] as Double))
    }

    @Test
    fun planTopEncodingRoundTrips() {
        val top = listOf(
            TrialSpec(PhyChoice.Qr(20, QrEcc.L), 15, SchemeId.RAPTORQ, 1, Stage.FINE),
            TrialSpec(PhyChoice.Grid(96, 2, 48, 2), 20, SchemeId.LT, 1, Stage.FINE),
        )
        val back = Plans.decodeTop(Plans.encodeTop(top))
        assertEquals(top.map { it.configKey() }, back.map { it.configKey() })
        assertTrue(Plans.coarse().size in 15..25)
        assertTrue(Plans.fine(top).isNotEmpty())
    }
}

class RankingTest {
    @Test
    fun rankingPrefersFastReliableConfigs() {
        fun rec(key: String, v: Int, ok: Boolean, gp: Double) = RunRecord(linkedMapOf(
            "stage" to "COARSE", "config_key" to key, "codec" to "QR", "qr_version" to v, "qr_ecc" to "L",
            "target_visual_fps" to 15, "scheme" to "RAPTORQ", "RESULT" to if (ok) "PASS" else "FAIL_INCOMPLETE",
            "goodput_bytes_sec" to gp,
        ))
        val records = listOf(
            rec("a", 20, true, 9000.0), rec("a", 20, true, 11000.0),
            rec("b", 25, true, 20000.0), rec("b", 25, false, 0.0), rec("b", 25, false, 0.0),
            rec("c", 10, true, 3000.0),
        )
        val ranked = Ranking.rank(records)
        assertEquals(listOf("a", "b", "c"), ranked.map { r -> records.first { Ranking.specOf(it) == r.spec }["config_key"] })
        val top = Ranking.top(records, 2)
        assertEquals(2, top.size)
        val plan = ghostlink.lab.codecs.LabFrame.parse(Ranking.planFrame(1, top))
        assertEquals(top.map { it.configKey() }, Plans.decodeTop(plan.body).map { it.configKey() })
    }
}
