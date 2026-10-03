package ghostlink.lab.codecs

import ghostlink.lab.codecs.ghost.GhostFrameV0
import ghostlink.lab.codecs.ghost.ObjSymbolPacket
import ghostlink.lab.codecs.visual.GridCodec
import ghostlink.lab.codecs.visual.GridLayout
import ghostlink.lab.codecs.visual.GridSpec
import ghostlink.lab.codecs.visual.QrCodec
import ghostlink.lab.codecs.visual.QrEcc
import ghostlink.lab.codecs.visual.QrSpec
import org.bouncycastle.crypto.digests.Blake2sDigest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VisualCodecTest {
    @Test
    fun qrByteCapacityMatchesIsoTables() {
        // ISO/IEC 18004 byte-mode capacities.
        assertEquals(2953, QrCodec.byteCapacity(40, QrEcc.L))
        assertEquals(1273, QrCodec.byteCapacity(25, QrEcc.L))
        assertEquals(997, QrCodec.byteCapacity(25, QrEcc.M))
        assertEquals(858, QrCodec.byteCapacity(20, QrEcc.L))
        assertEquals(271, QrCodec.byteCapacity(10, QrEcc.L))
        assertEquals(213, QrCodec.byteCapacity(10, QrEcc.M))
        assertEquals(17, QrCodec.byteCapacity(1, QrEcc.L))
    }

    @Test
    fun qrEncodesAtExactVersionAndCapacity() {
        for (v in listOf(5, 10, 15, 20, 25)) for (ecc in QrEcc.entries) {
            val spec = QrSpec(v, ecc)
            val data = ByteArray(spec.frameCapacity()) { (it * 7 + 0xA7).toByte() }
            data[0] = 0xA7.toByte()
            val f = QrCodec.encode(data, spec, maskPattern = 0)
            assertEquals(QrCodec.modules(v) + 2 * QrCodec.QUIET_MODULES, f.width)
            assertFailsWith<IllegalArgumentException> { QrCodec.encode(ByteArray(spec.frameCapacity() + 1), spec) }
        }
    }

    @Test
    fun gridSymbolsRoundTripAndCorrectErrors() {
        val rnd = Random(4242)
        for (spec in listOf(GridSpec(48, 104, 1), GridSpec(64, 140, 2), GridSpec(96, 208, 3, 48), GridSpec(64, 140, 1, 32, 2))) {
            val layout = GridLayout.of(spec)
            val data = ByteArray(layout.frameCapacity) { rnd.nextInt().toByte() }
            val symbols = GridCodec.encodeSymbols(data, layout)
            val clean = GridCodec.decodeSymbols(symbols.copyOf(), layout)
            assertContentEquals(data, clean.data, spec.key())
            // Corrupt a localized burst of cells: interleaving spreads it over RS blocks.
            val noisy = symbols.copyOf()
            val maxSym = (1 shl spec.bitsPerCell) - 1
            val burst = layout.blockLengths.size * spec.rsParity / 2 * 8 / spec.bitsPerCell / 3
            val start = rnd.nextInt(noisy.size - burst)
            for (i in start until start + burst) noisy[i] = noisy[i] xor maxSym
            val repaired = GridCodec.decodeSymbols(noisy, layout)
            assertContentEquals(data, repaired.data, "${spec.key()} burst=$burst")
            assertTrue(repaired.correctedBytes > 0)
            // Massive corruption must fail cleanly (null), never return wrong bytes silently.
            val garbage = IntArray(noisy.size) { rnd.nextInt(maxSym + 1) }
            val g = GridCodec.decodeSymbols(garbage, layout)
            assertNull(g.data)
        }
    }

    @Test
    fun gridCapacitiesAreSane() {
        val l = GridLayout.of(GridSpec(96, 208, 1))
        assertTrue(l.frameCapacity in 1500..2500, "96x208 b1 capacity ${l.frameCapacity}")
        val l2 = GridLayout.of(GridSpec(96, 208, 2))
        assertTrue(l2.frameCapacity > 2 * l.frameCapacity - 100)
        assertFailsWith<IllegalArgumentException> { GridLayout.of(GridSpec(24, 200, 3)) }
    }

    @Test
    fun ghostPacketV0ObjSymbolRoundTripAndPacketId() {
        val payload = DeterministicPayload.generate(1, 768)
        val objectId = DeterministicPayload.sha256(payload).copyOf(16)
        val syms = (0 until 4).map { Triple(0, it, payload.copyOfRange(it * 192, (it + 1) * 192)) }
        val pkt = ObjSymbolPacket(hopLimit = 8, sprayCopies = 4, expiryMinutes = 3_600_000L, objectId = objectId, symbols = syms)
        val bytes = pkt.encode()
        // Header 8 B (no dest) + object_id 16 + n 1 + 4*(1+3) + 4*192 = 809 B
        assertEquals(8 + 16 + 1 + 16 + 768, bytes.size)
        val frame = GhostFrameV0.wrap(bytes)
        assertEquals(bytes.size + 5, frame.size)
        assertContentEquals(bytes, GhostFrameV0.unwrap(frame))
        assertEquals(pkt, ObjSymbolPacket.parse(bytes))
        // packet_id = BLAKE2s-128(immutable fields) per GHOSTPACKET_v0 §4; hop/spray changes must not alter it.
        fun packetId(p: ObjSymbolPacket): ByteArray {
            val d = Blake2sDigest(128); val im = p.immutableBytes(); d.update(im, 0, im.size)
            return ByteArray(16).also { d.doFinal(it, 0) }
        }
        val id1 = packetId(pkt)
        val id2 = packetId(pkt.copy(hopLimit = 1, sprayCopies = 1))
        assertContentEquals(id1, id2)
        assertNotNull(id1)
        val corrupted = frame.copyOf().also { it[10] = (it[10].toInt() xor 0x40).toByte() }
        assertFailsWith<FrameFormatException> { GhostFrameV0.unwrap(corrupted) }
    }

    @Test
    fun ghostPacketParserFuzz() {
        val rnd = Random(99)
        val base = ObjSymbolPacket(4, 2, 1000, ByteArray(16), listOf(Triple(0, 1, ByteArray(10)))).encode()
        repeat(10_000) { i ->
            val b = if (i % 2 == 0) ByteArray(rnd.nextInt(0, 60)) { rnd.nextInt().toByte() }
            else base.copyOf(rnd.nextInt(0, base.size + 4)).also { if (it.isNotEmpty()) it[rnd.nextInt(it.size)] = rnd.nextInt().toByte() }
            try { ObjSymbolPacket.parse(b) } catch (_: FrameFormatException) {
            }
        }
    }
}
