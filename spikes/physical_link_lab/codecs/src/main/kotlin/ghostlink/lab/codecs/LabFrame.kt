package ghostlink.lab.codecs

/**
 * Lab link frame carried by every visual symbol (QR or grid). Lab-only format, deliberately close to
 * GhostFrame v0 semantics (header + payload + CRC-32C) but self-describing for benchmarking.
 *
 * ```
 * off size field
 *  0   1   magic 0xA7  (non-alphanumeric: forces QR byte mode; identifies lab frames)
 *  1   1   version(4) | type(4)
 *  2   2   sessionTag (u16, hash of session id)
 *  4   2   trial (u16)
 *  6   1   erasure scheme id
 *  7   4   symbolId (u32: ESI / chunk index)
 * 11   4   payloadBytes (u32: object size, lets a receiver join mid-stream)
 * 15   N   body
 * 15+N 4   CRC-32C over bytes [0, 15+N)
 * ```
 */
data class LabFrame(
    val type: FrameType,
    val sessionTag: Int,
    val trial: Int,
    val scheme: SchemeId,
    val symbolId: Long,
    val payloadBytes: Long,
    val body: ByteArray,
) {
    fun encode(): ByteArray {
        val w = ByteWriter(HEADER_BYTES + body.size + CRC_BYTES)
        w.u8(MAGIC).u8((VERSION shl 4) or type.code).u16(sessionTag).u16(trial).u8(scheme.code)
            .u32(symbolId).u32(payloadBytes).bytes(body)
        val partial = w.toByteArray()
        w.u32(Crc32c.compute(partial).toLong() and 0xFFFF_FFFFL)
        return w.toByteArray()
    }

    override fun equals(other: Any?): Boolean = other is LabFrame && type == other.type &&
        sessionTag == other.sessionTag && trial == other.trial && scheme == other.scheme &&
        symbolId == other.symbolId && payloadBytes == other.payloadBytes && body.contentEquals(other.body)

    override fun hashCode(): Int = (((type.hashCode() * 31 + trial) * 31 + symbolId.hashCode()) * 31) + body.contentHashCode()

    companion object {
        const val MAGIC = 0xA7
        const val VERSION = 1
        const val HEADER_BYTES = 15
        const val CRC_BYTES = 4
        const val OVERHEAD_BYTES = HEADER_BYTES + CRC_BYTES
        /** Hard cap to reject absurd lengths before allocating anything. */
        const val MAX_FRAME_BYTES = 64 * 1024

        /** Parses and validates a frame. Throws [FrameFormatException] on any inconsistency. */
        fun parse(raw: ByteArray): LabFrame {
            if (raw.size < OVERHEAD_BYTES) throw FrameFormatException("frame too short: ${raw.size}")
            if (raw.size > MAX_FRAME_BYTES) throw FrameFormatException("frame too long: ${raw.size}")
            val crcPos = raw.size - CRC_BYTES
            val expected = ByteReader(raw, crcPos).u32()
            val actual = Crc32c.compute(raw, 0, crcPos).toLong() and 0xFFFF_FFFFL
            if (expected != actual) throw FrameFormatException("crc mismatch")
            val r = ByteReader(raw, 0, crcPos)
            if (r.u8() != MAGIC) throw FrameFormatException("bad magic")
            val vt = r.u8()
            if (vt ushr 4 != VERSION) throw FrameFormatException("unsupported version ${vt ushr 4}")
            val type = FrameType.fromCode(vt and 0x0F)
            val sessionTag = r.u16()
            val trial = r.u16()
            val scheme = SchemeId.fromCode(r.u8())
            val symbolId = r.u32()
            val payloadBytes = r.u32()
            return LabFrame(type, sessionTag, trial, scheme, symbolId, payloadBytes, r.rest())
        }

        /** Returns null instead of throwing: convenient on the hot decode path. */
        fun parseOrNull(raw: ByteArray?): LabFrame? = try { raw?.let { parse(it) } } catch (_: FrameFormatException) { null }

        fun sessionTagOf(sessionId: String): Int = (Crc32c.compute(sessionId.toByteArray(Charsets.US_ASCII)) ushr 16) and 0xFFFF
    }
}

enum class FrameType(val code: Int) {
    ANNOUNCE(1), DATA(2), PLAN(3), END(4), GHOST(5);

    companion object {
        fun fromCode(c: Int): FrameType = entries.firstOrNull { it.code == c } ?: throw FrameFormatException("unknown frame type $c")
    }
}

enum class SchemeId(val code: Int) {
    /** No FEC across frames: chunks repeated in a carousel; receiver needs every chunk. */
    SEQUENTIAL(0),
    /** Luby Transform fountain (systematic, robust soliton), spike implementation. */
    LT(1),
    /** RaptorQ RFC 6330 via raptorq-kotlin. */
    RAPTORQ(2);

    companion object {
        fun fromCode(c: Int): SchemeId = entries.firstOrNull { it.code == c } ?: throw FrameFormatException("unknown scheme $c")
    }
}
