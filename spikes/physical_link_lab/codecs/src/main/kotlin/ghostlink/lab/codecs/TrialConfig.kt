package ghostlink.lab.codecs

import ghostlink.lab.codecs.visual.VisualSpec

/** Stage of the automated parameter search (brief §22). */
enum class Stage(val code: Int) {
    MANUAL(0), COARSE(1), FINE(2), CONFIRM(3), DISTANCE(4), ANGLE(5), LIGHT(6), MOTION(7), DECODER_COMPARE(8), GHOSTPACKET(9);

    companion object {
        fun fromCode(c: Int): Stage = entries.firstOrNull { it.code == c } ?: throw FrameFormatException("unknown stage $c")
    }
}

/**
 * Everything the receiver needs to decode and score one trial. Sent in ANNOUNCE frames (always as a
 * robust QR) before and periodically during the data stream.
 */
data class TrialConfig(
    val sessionId: String,
    val trialIndex: Int,
    val trialCount: Int,
    val stage: Stage,
    val repetition: Int,
    val visual: VisualSpec,
    val targetFps: Int,
    val scheme: SchemeId,
    val symbolSize: Int,
    val payloadBytes: Int,
    val sourceSymbols: Int,
    val durationMs: Int,
) {
    val sessionTag: Int get() = LabFrame.sessionTagOf(sessionId)

    fun encode(): ByteArray {
        val sid = sessionId.toByteArray(Charsets.US_ASCII)
        require(sid.size in 1..MAX_SESSION_ID) { "session id length" }
        val w = ByteWriter(64)
        w.u8(CONFIG_VERSION).u16(trialIndex).u16(trialCount).u8(stage.code).u8(repetition)
        w.bytes(visual.encode())
        w.u8(targetFps).u8(scheme.code).u16(symbolSize).u32(payloadBytes.toLong()).u32(sourceSymbols.toLong())
        w.u32(durationMs.toLong()).u8(sid.size).bytes(sid)
        return w.toByteArray()
    }

    /** Short human-readable label, also used as the analysis grouping key. */
    fun configKey(): String = "${visual.key()}|fps$targetFps|${scheme.name}"

    companion object {
        const val CONFIG_VERSION = 1
        const val MAX_SESSION_ID = 40

        fun parse(body: ByteArray): TrialConfig {
            val r = ByteReader(body)
            if (r.u8() != CONFIG_VERSION) throw FrameFormatException("bad trial config version")
            val trialIndex = r.u16()
            val trialCount = r.u16()
            val stage = Stage.fromCode(r.u8())
            val repetition = r.u8()
            val visual = VisualSpec.decode(r)
            val fps = r.u8()
            val scheme = SchemeId.fromCode(r.u8())
            val symbolSize = r.u16()
            val payloadBytes = r.u32()
            val k = r.u32()
            val duration = r.u32()
            val sidLen = r.u8()
            if (sidLen !in 1..MAX_SESSION_ID) throw FrameFormatException("bad session id length")
            val sid = String(r.bytes(sidLen), Charsets.US_ASCII)
            if (fps !in 1..120) throw FrameFormatException("bad fps $fps")
            if (symbolSize !in 1..LabFrame.MAX_FRAME_BYTES) throw FrameFormatException("bad symbol size")
            if (payloadBytes > (64L shl 20)) throw FrameFormatException("payload too large")
            if (k < 1 || k > 1_000_000) throw FrameFormatException("bad K")
            if (duration > 3_600_000) throw FrameFormatException("bad duration")
            return TrialConfig(sid, trialIndex, trialCount, stage, repetition, visual, fps, scheme, symbolSize,
                payloadBytes.toInt(), k.toInt(), duration.toInt())
        }
    }
}
