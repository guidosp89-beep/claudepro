package ghostlink.lab.benchmark

import ghostlink.lab.codecs.DeterministicPayload
import ghostlink.lab.codecs.FrameType
import ghostlink.lab.codecs.LabFrame
import ghostlink.lab.codecs.SchemeId
import ghostlink.lab.codecs.erasure.RaptorQCodec
import ghostlink.lab.codecs.ghost.GhostFrameV0
import ghostlink.lab.codecs.ghost.ObjSymbolPacket

/**
 * GhostPacket v0 transport proof (brief §31): a deterministic object is RaptorQ-encoded into
 * OBJ_SYMBOL GhostPackets (n symbols each), wrapped in GhostFrame v0 and sent over the visual PHY.
 * The receiver regenerates the expected packet for the same ESIs and checks byte identity.
 */
class GhostTrial(sessionTag: Int, val trialIndex: Int, val payload: ByteArray, labBodyCapacity: Int) {
    val symbolsPerPacket = SYMBOLS_PER_PACKET
    /** T chosen so one GhostFrame(GhostPacket) fills one visual frame. */
    val symbolSize: Int = (labBodyCapacity - GHOSTFRAME_OVERHEAD - PACKET_FIXED - 4 * SYMBOLS_PER_PACKET) / SYMBOLS_PER_PACKET
    val objectId: ByteArray = DeterministicPayload.sha256(payload).copyOf(16)
    private val tag = sessionTag
    private val encoder by lazy { RaptorQCodec.Encoder(payload, symbolSize) }

    init { require(symbolSize >= 16) { "visual frame too small for GhostPacket proof" } }

    fun packet(packetIndex: Long): ObjSymbolPacket {
        val base = (packetIndex * symbolsPerPacket).toInt()
        val syms = (0 until symbolsPerPacket).map { i -> Triple(0, base + i, encoder.symbol((base + i).toLong())) }
        return ObjSymbolPacket(HOP_LIMIT, SPRAY_COPIES, EXPIRY_MINUTES, objectId, syms)
    }

    fun ghostFrame(packetIndex: Long): ByteArray = GhostFrameV0.wrap(packet(packetIndex).encode())

    fun frame(packetIndex: Long): ByteArray =
        LabFrame(FrameType.GHOST, tag, trialIndex, SchemeId.RAPTORQ, packetIndex, payload.size.toLong(), ghostFrame(packetIndex)).encode()

    companion object {
        const val SYMBOLS_PER_PACKET = 4
        const val GHOSTFRAME_OVERHEAD = 5
        /** ver/ptype, flags, hop, spray, expiry(4), object_id(16), n(1) */
        const val PACKET_FIXED = 8 + 16 + 1
        const val HOP_LIMIT = 8
        const val SPRAY_COPIES = 4
        /** Fixed expiry so the expected bytes are deterministic (minutes since 2020-01-01). */
        const val EXPIRY_MINUTES = 3_700_000L
    }
}
