package ghostlink.lab.benchmark

import ghostlink.lab.codecs.ByteReader
import ghostlink.lab.codecs.ByteWriter
import ghostlink.lab.codecs.FrameFormatException
import ghostlink.lab.codecs.SchemeId
import ghostlink.lab.codecs.Stage
import ghostlink.lab.codecs.visual.GridSpec
import ghostlink.lab.codecs.visual.QrEcc
import ghostlink.lab.codecs.visual.QrSpec
import ghostlink.lab.codecs.visual.VisualSpec

/** Visual PHY choice in a plan (grid rows are resolved on the transmitter from its screen aspect). */
sealed interface PhyChoice {
    fun resolve(screenAspect: Double): VisualSpec
    fun key(): String

    data class Qr(val version: Int, val ecc: QrEcc) : PhyChoice {
        override fun resolve(screenAspect: Double) = QrSpec(version, ecc)
        override fun key() = "QR-v$version-${ecc.name}"
    }

    data class Grid(val cols: Int, val bitsPerCell: Int, val rsParity: Int = 32, val finderModule: Int = 1) : PhyChoice {
        override fun resolve(screenAspect: Double) =
            GridSpec(cols, GridSpec.rowsForAspect(cols, screenAspect), bitsPerCell, rsParity, finderModule)
        override fun key() = "GRID-c$cols-b$bitsPerCell-p$rsParity"
    }
}

/** One planned trial. */
data class TrialSpec(
    val phy: PhyChoice,
    val fps: Int,
    val scheme: SchemeId,
    val payloadBytes: Int,
    val stage: Stage,
    val repetition: Int = 0,
    val maxDurationMs: Int = 30_000,
    /** GhostPacket transport proof trial instead of raw deterministic payload. */
    val ghostPacket: Boolean = false,
) {
    fun configKey(): String = "${phy.key()}|fps$fps|${scheme.name}"
}

/**
 * Automated parameter search (brief §21–§27): Stage 1 coarse grid, Stage 2 ranking/elimination on the
 * receiver, Stage 3 fine search around the best, then confirmation and robustness plans
 * (distance/angle/light/motion) built from the same "top configs" list.
 */
object Plans {
    const val COARSE_PAYLOAD = 64 * 1024
    const val FINE_PAYLOAD = 256 * 1024
    const val ROBUSTNESS_PAYLOAD = 256 * 1024

    fun durationFor(payloadBytes: Int, floorBytesPerSec: Int = 3_000, minMs: Int = 10_000, maxMs: Int = 90_000): Int =
        ((payloadBytes.toLong() * 1000 / floorBytesPerSec).toInt()).coerceIn(minMs, maxMs)

    /** Stage 1: ~19 trials covering QR version/ECC/fps, fountain vs sequential, and grid candidates. */
    fun coarse(): List<TrialSpec> {
        val p = COARSE_PAYLOAD
        val d = durationFor(p, minMs = 12_000, maxMs = 25_000)
        fun t(phy: PhyChoice, fps: Int, scheme: SchemeId = SchemeId.RAPTORQ) = TrialSpec(phy, fps, scheme, p, Stage.COARSE, maxDurationMs = d)
        val qr = { v: Int, e: QrEcc -> PhyChoice.Qr(v, e) }
        return listOf(
            t(qr(10, QrEcc.M), 10),
            t(qr(15, QrEcc.L), 15),
            t(qr(20, QrEcc.L), 15),
            t(qr(25, QrEcc.L), 15),
            t(qr(20, QrEcc.M), 15),
            t(qr(20, QrEcc.Q), 15),
            t(qr(20, QrEcc.H), 15),
            t(qr(20, QrEcc.L), 10),
            t(qr(20, QrEcc.L), 20),
            t(qr(20, QrEcc.L), 30),
            t(qr(20, QrEcc.L), 15, SchemeId.SEQUENTIAL),
            t(qr(20, QrEcc.L), 15, SchemeId.LT),
            t(PhyChoice.Grid(48, 1), 15),
            t(PhyChoice.Grid(64, 1), 15),
            t(PhyChoice.Grid(96, 1), 15),
            t(PhyChoice.Grid(48, 2), 15),
            t(PhyChoice.Grid(64, 2), 15),
            t(PhyChoice.Grid(48, 3, 48), 15),
            t(PhyChoice.Grid(64, 1, 32, 2), 15),
        )
    }

    /** Stage 3: neighbours of each top config (QR version ±5, fps ±5, ECC ±1; grid cols ±16, parity). */
    /** 256 KB in 60 s = 4.3 KB/s: the slowest goodput a FINE/ROBUSTNESS trial can still measure. */
    const val LONG_TRIAL_MS = 60_000

    fun fine(top: List<TrialSpec>, reps: Int = 1): List<TrialSpec> {
        val out = LinkedHashMap<String, TrialSpec>()
        for (base in top) {
            val cands = ArrayList<Pair<PhyChoice, Int>>()
            cands.add(base.phy to base.fps)
            for (df in listOf(-5, 5)) cands.add(base.phy to (base.fps + df).coerceIn(5, 30))
            when (val phy = base.phy) {
                is PhyChoice.Qr -> {
                    for (dv in listOf(-5, 5)) cands.add(phy.copy(version = (phy.version + dv).coerceIn(5, 30)) to base.fps)
                    val e = QrEcc.entries
                    for (de in listOf(-1, 1)) e.getOrNull(phy.ecc.ordinal + de)?.let { cands.add(phy.copy(ecc = it) to base.fps) }
                }
                is PhyChoice.Grid -> {
                    for (dc in listOf(-16, 16)) cands.add(phy.copy(cols = (phy.cols + dc).coerceIn(32, 160)) to base.fps)
                    cands.add(phy.copy(rsParity = if (phy.rsParity == 32) 48 else 32) to base.fps)
                }
            }
            for ((phy, fps) in cands) {
                val spec = TrialSpec(phy, fps, SchemeId.RAPTORQ, FINE_PAYLOAD, Stage.FINE, maxDurationMs = LONG_TRIAL_MS)
                out.putIfAbsent(spec.configKey(), spec)
            }
        }
        return out.values.take(16).flatMap { s -> (0 until reps).map { s.copy(repetition = it) } }
    }

    /** Large-payload confirmation of the best configs (M1 PASS needs >= 256 KB). */
    fun confirm(top: List<TrialSpec>, reps: Int = 3): List<TrialSpec> = top.take(2).flatMap { b ->
        (0 until reps).map { r ->
            b.copy(payloadBytes = FINE_PAYLOAD, stage = Stage.CONFIRM, repetition = r, scheme = SchemeId.RAPTORQ, maxDurationMs = LONG_TRIAL_MS)
        } + (0 until 2).map { r ->
            b.copy(payloadBytes = 1 shl 20, stage = Stage.CONFIRM, repetition = r, scheme = SchemeId.RAPTORQ, maxDurationMs = 200_000)
        }
    }

    /** Short robustness plan used at each distance / angle / light / motion setting. */
    fun robustness(top: List<TrialSpec>, stage: Stage, reps: Int = 2): List<TrialSpec> =
        top.take(2).flatMap { b ->
            (0 until reps).map { r ->
                b.copy(payloadBytes = ROBUSTNESS_PAYLOAD, stage = stage, repetition = r, scheme = SchemeId.RAPTORQ, maxDurationMs = LONG_TRIAL_MS)
            }
        } + robustBaseline(stage)

    /** Always include one conservative QR config so long distances still produce data points. */
    private fun robustBaseline(stage: Stage) = listOf(
        TrialSpec(PhyChoice.Qr(10, QrEcc.M), 10, SchemeId.RAPTORQ, 16 * 1024, stage, maxDurationMs = 30_000),
    )

    /** Same QR frames decoded by all three QR decoders on the receiver (decoder comparison mode). */
    fun decoderCompare(): List<TrialSpec> = listOf(10 to QrEcc.M, 20 to QrEcc.L, 25 to QrEcc.L).map { (v, e) ->
        TrialSpec(PhyChoice.Qr(v, e), 10, SchemeId.RAPTORQ, 32 * 1024, Stage.DECODER_COMPARE, maxDurationMs = 20_000)
    }

    /** Sequential payload-size ladder over the standard sizes (brief §14) on one config. */
    fun payloadLadder(base: TrialSpec): List<TrialSpec> = listOf(256, 1024, 4096, 16384, 65536, 262144, 1 shl 20).map { size ->
        base.copy(payloadBytes = size, stage = Stage.CONFIRM, scheme = SchemeId.RAPTORQ, maxDurationMs = durationFor(size, minMs = 8_000, maxMs = 240_000))
    }

    /** QR v10-M: the most robust data PHY in the simulator at 40 cm (v20 needs ~20 cm at 1080p). */
    fun ghostPacketProof(): List<TrialSpec> = listOf(
        TrialSpec(PhyChoice.Qr(10, QrEcc.M), 10, SchemeId.RAPTORQ, 8 * 1024, Stage.GHOSTPACKET, maxDurationMs = 30_000, ghostPacket = true),
    )

    /** Built-in defaults when no ranking is available yet (e.g. robustness run before coarse). */
    fun defaultTop(): List<TrialSpec> = listOf(
        TrialSpec(PhyChoice.Qr(20, QrEcc.L), 15, SchemeId.RAPTORQ, FINE_PAYLOAD, Stage.FINE),
        TrialSpec(PhyChoice.Grid(64, 1), 15, SchemeId.RAPTORQ, FINE_PAYLOAD, Stage.FINE),
    )

    // ---- compact binary encoding of "top configs" for the PLAN QR (receiver -> transmitter) ----

    fun encodeTop(top: List<TrialSpec>): ByteArray {
        val w = ByteWriter(64).u8(1).u8(top.size)
        for (t in top) {
            when (val p = t.phy) {
                is PhyChoice.Qr -> w.u8(1).u8(p.version).u8(p.ecc.code).u8(0)
                is PhyChoice.Grid -> w.u8(2).u8(p.cols / 4).u8(p.bitsPerCell).u8((p.rsParity shl 2) or p.finderModule)
            }
            w.u8(t.fps).u8(t.scheme.code)
        }
        return w.toByteArray()
    }

    fun decodeTop(bytes: ByteArray): List<TrialSpec> {
        val r = ByteReader(bytes)
        if (r.u8() != 1) throw FrameFormatException("plan version")
        val n = r.u8()
        if (n > 16) throw FrameFormatException("too many top configs")
        return (0 until n).map {
            val kind = r.u8(); val a = r.u8(); val b = r.u8(); val c = r.u8()
            val phy = when (kind) {
                1 -> { if (a !in 1..40) throw FrameFormatException("qr version"); PhyChoice.Qr(a, QrEcc.fromCode(b)) }
                2 -> {
                    if (b !in 1..3 || a * 4 !in GridSpec.MIN_DIM..GridSpec.MAX_DIM) throw FrameFormatException("grid")
                    val parity = c ushr 2
                    val fm = c and 3
                    if (parity !in 2..62 || parity % 2 != 0 || fm !in 1..3) throw FrameFormatException("grid params")
                    PhyChoice.Grid(a * 4, b, parity, fm)
                }
                else -> throw FrameFormatException("phy kind")
            }
            val fps = r.u8()
            if (fps !in 1..60) throw FrameFormatException("fps")
            TrialSpec(phy, fps, SchemeId.fromCode(r.u8()), FINE_PAYLOAD, Stage.FINE)
        }
    }
}
