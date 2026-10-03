package ghostlink.lab.benchmark

import ghostlink.lab.codecs.DeterministicPayload
import ghostlink.lab.codecs.FrameType
import ghostlink.lab.codecs.LabFrame
import ghostlink.lab.codecs.SchemeId
import ghostlink.lab.codecs.TrialConfig
import ghostlink.lab.codecs.erasure.Erasure
import ghostlink.lab.codecs.erasure.ErasureEncoder
import ghostlink.lab.codecs.visual.VisualSpec
import kotlin.math.roundToInt

enum class SlotKind { ANNOUNCE, DATA, END }

/** One visual symbol to display; held for one TX frame period. */
class TxSlot(val kind: SlotKind, val trialIndex: Int, val frameBytes: ByteArray, val spec: VisualSpec, val fps: Int) {
    /** Same bytes + spec => same image: lets renderers cache repeated announce frames. */
    val cacheKey: Int = frameBytes.contentHashCode() * 31 + spec.hashCode()
}

/** What the display loop measured for a trial (filled in by the platform renderer). */
class TxTrialStats(val trialIndex: Int) {
    @Volatile var dataSlotsShown = 0L
    @Volatile var firstDataShownMs = 0L
    @Volatile var lastDataShownMs = 0L
    @Volatile var missedVsyncs = 0L

    fun report(): TxTrialReport = TxTrialReport(trialIndex, dataSlotsShown, maxOf(0L, lastDataShownMs - firstDataShownMs), missedVsyncs)
}

/**
 * Deterministic transmitter schedule for an AUTO BENCHMARK session. Pure logic (no Android):
 * the platform renderer pulls [TxSlot]s (on a producer thread, a few ahead) and reports what it
 * actually displayed through [statsFor].
 *
 * Per trial: ANNOUNCE (robust QR, [announceSeconds]) -> DATA symbols for maxDurationMs, with a short
 * re-announce every [reannounceSeconds] (lets a receiver join late and carries the final TX report of
 * the previous trial). Session ends with END frames.
 */
class TransmitterSession(
    val sessionId: String,
    val plan: List<TrialSpec>,
    private val screenAspect: Double,
    private val tx: TxInfo,
    private val announceSeconds: Double = 1.5,
    private val reannounceSeconds: Double = 3.0,
    private val endSeconds: Double = 2.0,
) {
    val sessionTag = LabFrame.sessionTagOf(sessionId)
    private val stats = HashMap<Int, TxTrialStats>()
    val configs = ArrayList<TrialConfig>()

    @Synchronized fun statsFor(trial: Int): TxTrialStats = stats.getOrPut(trial) { TxTrialStats(trial) }
    @Synchronized fun allStats(): List<TxTrialStats> = stats.values.sortedBy { it.trialIndex }

    /** The whole session as a lazy sequence. Iterate it once (it records [configs] as it goes). */
    fun slots(): Sequence<TxSlot> = sequence {
        for ((idx, spec) in plan.withIndex()) {
            val visual = spec.phy.resolve(screenAspect)
            val symbolSize = visual.frameCapacity() - LabFrame.OVERHEAD_BYTES
            require(symbolSize > 0) { "visual ${visual.key()} too small" }
            val payload = DeterministicPayload.forTrial(sessionTag, idx, spec.payloadBytes)
            val ghost = if (spec.ghostPacket) GhostTrial(sessionTag, idx, payload, visual.frameCapacity() - LabFrame.OVERHEAD_BYTES) else null
            val config = TrialConfig(
                sessionId, idx, plan.size, spec.stage, spec.repetition, visual, spec.fps, spec.scheme,
                ghost?.symbolSize ?: symbolSize, spec.payloadBytes,
                Erasure.sourceSymbols(spec.payloadBytes, ghost?.symbolSize ?: symbolSize), spec.maxDurationMs,
            )
            configs.add(config)
            val encoder: ErasureEncoder? = if (ghost == null) Erasure.encoder(spec.scheme, payload, symbolSize) else null
            fun announce(): TxSlot {
                val prev = if (idx > 0) statsFor(idx - 1).report() else null
                val body = Announce(config, tx, prev).encode()
                val f = LabFrame(FrameType.ANNOUNCE, sessionTag, idx, spec.scheme, 0, spec.payloadBytes.toLong(), body).encode()
                return TxSlot(SlotKind.ANNOUNCE, idx, f, Announce.ANNOUNCE_SPEC, spec.fps)
            }
            val announceSlots = maxOf(3, (announceSeconds * spec.fps).roundToInt())
            val first = announce()
            repeat(announceSlots) { yield(first) }
            val dataSlots = maxOf(1L, spec.maxDurationMs.toLong() * spec.fps / 1000)
            val reEvery = maxOf(spec.fps.toLong(), (reannounceSeconds * spec.fps).roundToInt().toLong())
            val reLen = maxOf(2, (0.25 * spec.fps).roundToInt())
            var symbolId = 0L
            for (s in 0 until dataSlots) {
                if (s > 0 && s % reEvery == 0L) { val a = announce(); repeat(reLen) { yield(a) } }
                val bytes = if (ghost != null) {
                    ghost.frame(symbolId)
                } else {
                    LabFrame(FrameType.DATA, sessionTag, idx, spec.scheme, symbolId, spec.payloadBytes.toLong(), encoder!!.symbol(symbolId)).encode()
                }
                yield(TxSlot(SlotKind.DATA, idx, bytes, visual, spec.fps))
                symbolId++
            }
        }
        val last = plan.size - 1
        val endBody = Announce.encodeEnd(tx, if (last >= 0) statsFor(last).report() else null)
        val end = LabFrame(FrameType.END, sessionTag, maxOf(last, 0), SchemeId.SEQUENTIAL, 0, 0, endBody).encode()
        val fps = plan.lastOrNull()?.fps ?: 10
        repeat(maxOf(3, (endSeconds * fps).roundToInt())) { yield(TxSlot(SlotKind.END, maxOf(last, 0), end, Announce.ANNOUNCE_SPEC, fps)) }
    }

    /** Planned wall-clock duration (s) assuming the target fps is met. */
    fun plannedSeconds(): Double = plan.sumOf { announceSeconds + it.maxDurationMs / 1000.0 + reannounceOverhead(it) } + endSeconds

    private fun reannounceOverhead(t: TrialSpec): Double = (t.maxDurationMs / 1000.0 / reannounceSeconds) * 0.25

    companion object {
        /** Sanity for stage labels shown in UIs. */
        fun describe(plan: List<TrialSpec>): String = plan.groupBy { it.stage }.entries.joinToString { (s, l) -> "${s.name}×${l.size}" }
    }
}
