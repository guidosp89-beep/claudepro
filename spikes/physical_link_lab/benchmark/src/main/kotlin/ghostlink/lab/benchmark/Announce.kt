package ghostlink.lab.benchmark

import ghostlink.lab.codecs.ByteReader
import ghostlink.lab.codecs.ByteWriter
import ghostlink.lab.codecs.FrameFormatException
import ghostlink.lab.codecs.TrialConfig
import ghostlink.lab.codecs.visual.QrEcc
import ghostlink.lab.codecs.visual.QrSpec

/** Transmitter facts the receiver cannot observe itself; carried in every announce. */
data class TxInfo(
    val model: String,
    val displayWidth: Int,
    val displayHeight: Int,
    /** Refresh rate × 100 (e.g. 12000 = 120 Hz). */
    val refreshCentiHz: Int,
) {
    fun write(w: ByteWriter) {
        val m = model.take(MAX_MODEL).toByteArray(Charsets.UTF_8).let { if (it.size > MAX_MODEL) it.copyOf(MAX_MODEL) else it }
        w.u8(m.size).bytes(m).u16(displayWidth.coerceIn(0, 0xFFFF)).u16(displayHeight.coerceIn(0, 0xFFFF)).u16(refreshCentiHz.coerceIn(0, 0xFFFF))
    }

    companion object {
        const val MAX_MODEL = 24
        fun read(r: ByteReader): TxInfo {
            val n = r.u8()
            if (n > MAX_MODEL) throw FrameFormatException("model too long")
            return TxInfo(String(r.bytes(n), Charsets.UTF_8), r.u16(), r.u16(), r.u16())
        }
    }
}

/**
 * What the transmitter actually achieved for one trial (reported in the *next* announce or END frame,
 * so the receiver's CSV row contains sender-side numbers without manual merging).
 */
data class TxTrialReport(
    val trialIndex: Int,
    val framesGenerated: Long,
    val elapsedMs: Long,
    val missedVsyncs: Long,
) {
    val actualFps: Double get() = if (elapsedMs > 0) framesGenerated * 1000.0 / elapsedMs else 0.0

    fun write(w: ByteWriter) {
        w.u16(trialIndex).u32(framesGenerated.coerceIn(0, 0xFFFF_FFFFL)).u32(elapsedMs.coerceIn(0, 0xFFFF_FFFFL)).u32(missedVsyncs.coerceIn(0, 0xFFFF_FFFFL))
    }

    companion object {
        fun read(r: ByteReader) = TxTrialReport(r.u16(), r.u32(), r.u32(), r.u32())
    }
}

/** ANNOUNCE body = TrialConfig ‖ TxInfo ‖ hasPrev(1) [‖ TxTrialReport]. END body = TxInfo ‖ hasPrev [‖ report]. */
data class Announce(val config: TrialConfig, val tx: TxInfo, val previous: TxTrialReport?) {
    fun encode(): ByteArray {
        val w = ByteWriter(160).bytes(config.encode())
        tx.write(w)
        writePrev(w, previous)
        return w.toByteArray()
    }

    companion object {
        /** Announces always travel as this robust QR, whatever the data PHY of the trial. */
        val ANNOUNCE_SPEC = QrSpec(8, QrEcc.M)

        fun parse(body: ByteArray): Announce {
            val r = ByteReader(body)
            val c = TrialConfig.read(r)
            val tx = TxInfo.read(r)
            return Announce(c, tx, readPrev(r))
        }

        fun encodeEnd(tx: TxInfo, previous: TxTrialReport?): ByteArray {
            val w = ByteWriter(64)
            tx.write(w)
            writePrev(w, previous)
            return w.toByteArray()
        }

        fun parseEnd(body: ByteArray): Pair<TxInfo, TxTrialReport?> {
            val r = ByteReader(body)
            return TxInfo.read(r) to readPrev(r)
        }

        private fun writePrev(w: ByteWriter, p: TxTrialReport?) {
            if (p == null) w.u8(0) else { w.u8(1); p.write(w) }
        }

        private fun readPrev(r: ByteReader): TxTrialReport? = when (r.u8()) {
            0 -> null
            1 -> TxTrialReport.read(r)
            else -> throw FrameFormatException("bad prev flag")
        }
    }
}
