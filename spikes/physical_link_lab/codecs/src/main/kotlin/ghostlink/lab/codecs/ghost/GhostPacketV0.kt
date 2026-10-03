package ghostlink.lab.codecs.ghost

import ghostlink.lab.codecs.ByteReader
import ghostlink.lab.codecs.ByteWriter
import ghostlink.lab.codecs.Crc32c
import ghostlink.lab.codecs.FrameFormatException

/**
 * Minimal, lab-only implementation of the *existing* GhostFrame / GhostPacket v0 draft
 * (docs/architecture/GHOSTPACKET_v0.md §3, §4) — only what the M1 transport proof needs:
 * GhostFrame(ftype=PACKET) wrapping a GhostPacket(ptype=OBJ_SYMBOL, addr_mode=broadcast).
 * No crypto is involved: OBJ_SYMBOL bodies are, by spec, already-ciphertext symbols.
 * Multi-byte fixed fields are little-endian (spec §6).
 */
object GhostFrameV0 {
    const val VERSION = 0
    const val FTYPE_PACKET = 0

    /** lh = ver(2) | ftype(3) | flags(3); no link_seq, no fragmentation. */
    fun wrap(packet: ByteArray): ByteArray {
        val lh = (VERSION shl 6) or (FTYPE_PACKET shl 3)
        val w = ByteWriter(packet.size + 5).u8(lh).bytes(packet)
        val partial = w.toByteArray()
        return w.u32(Crc32c.compute(partial).toLong() and 0xFFFF_FFFFL).toByteArray()
    }

    /** Validates CRC and header, returns the inner GhostPacket bytes. */
    fun unwrap(frame: ByteArray): ByteArray {
        if (frame.size < 6) throw FrameFormatException("ghostframe too short")
        val crcPos = frame.size - 4
        val expected = ByteReader(frame, crcPos).u32()
        if ((Crc32c.compute(frame, 0, crcPos).toLong() and 0xFFFF_FFFFL) != expected) throw FrameFormatException("ghostframe crc")
        val lh = frame[0].toInt() and 0xFF
        if (lh ushr 6 != VERSION) throw FrameFormatException("ghostframe version")
        if ((lh ushr 3) and 0x7 != FTYPE_PACKET) throw FrameFormatException("ghostframe ftype not PACKET")
        if (lh and 0x7 != 0) throw FrameFormatException("ghostframe flags unsupported in lab")
        return frame.copyOfRange(1, crcPos)
    }
}

data class ObjSymbolPacket(
    val hopLimit: Int,
    val sprayCopies: Int,
    val expiryMinutes: Long,
    val objectId: ByteArray,
    /** (sbn, esi, symbol) */
    val symbols: List<Triple<Int, Int, ByteArray>>,
) {
    init {
        require(objectId.size == 16)
        require(symbols.size in 1..255)
        require(symbols.all { it.third.size == symbols[0].third.size })
    }

    fun encode(): ByteArray {
        val w = ByteWriter(32 + symbols.sumOf { it.third.size + 4 })
        w.u8((VERSION shl 4) or PTYPE_OBJ_SYMBOL)
        w.u8(ADDR_BROADCAST shl 6) // addr_mode=0, prio=0, has_ack_commit=0
        w.u8(hopLimit).u8(sprayCopies).u32(expiryMinutes)
        w.bytes(objectId).u8(symbols.size)
        for ((sbn, esi, _) in symbols) {
            require(sbn in 0..255 && esi in 0..0xFFFFFF)
            w.u8(sbn).u8(esi and 0xFF).u8((esi ushr 8) and 0xFF).u8((esi ushr 16) and 0xFF)
        }
        for ((_, _, s) in symbols) w.bytes(s)
        return w.toByteArray()
    }

    /** Bytes covered by packet_id (spec §4: all immutable fields; hop_limit and spray_copies excluded). */
    fun immutableBytes(): ByteArray {
        val full = encode()
        return ByteWriter(full.size).bytes(full, 0, 2).bytes(full, 4, full.size - 4).toByteArray()
    }

    override fun equals(other: Any?): Boolean = other is ObjSymbolPacket && encode().contentEquals(other.encode())
    override fun hashCode(): Int = encode().contentHashCode()

    companion object {
        const val VERSION = 0
        const val PTYPE_OBJ_SYMBOL = 2
        const val ADDR_BROADCAST = 0

        fun parse(bytes: ByteArray): ObjSymbolPacket {
            val r = ByteReader(bytes)
            val vp = r.u8()
            if (vp ushr 4 != VERSION) throw FrameFormatException("ghostpacket version")
            if (vp and 0xF != PTYPE_OBJ_SYMBOL) throw FrameFormatException("lab parser supports OBJ_SYMBOL only")
            val flags = r.u8()
            if (flags ushr 6 != ADDR_BROADCAST || flags and 0x08 != 0) throw FrameFormatException("lab parser: broadcast, no ack_commit only")
            val hop = r.u8()
            val spray = r.u8()
            val expiry = r.u32()
            val objectId = r.bytes(16)
            val n = r.u8()
            if (n == 0) throw FrameFormatException("no symbols")
            val ids = (0 until n).map { r.u8() to (r.u8() or (r.u8() shl 8) or (r.u8() shl 16)) }
            if (r.remaining % n != 0) throw FrameFormatException("symbol bytes not divisible by n")
            val t = r.remaining / n
            if (t == 0) throw FrameFormatException("empty symbols")
            val syms = ids.map { (sbn, esi) -> Triple(sbn, esi, r.bytes(t)) }
            return ObjSymbolPacket(hop, spray, expiry, objectId, syms)
        }
    }
}
