package ghostlink.lab.codecs

import ghostlink.lab.codecs.visual.GridSpec
import ghostlink.lab.codecs.visual.QrEcc
import ghostlink.lab.codecs.visual.QrSpec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class FrameTest {
    companion object { const val PINNED_SHA_PREFIX = "2a911a0adb758497" }

    @Test
    fun crc32cKnownVector() {
        assertEquals(0xE3069283.toInt(), Crc32c.compute("123456789".toByteArray()))
        assertEquals(0, Crc32c.compute(ByteArray(0)))
    }

    @Test
    fun deterministicPayloadIsStableAndSeedDependent() {
        val a = DeterministicPayload.forTrial(0x1234, 7, 4096)
        val b = DeterministicPayload.forTrial(0x1234, 7, 4096)
        val c = DeterministicPayload.forTrial(0x1234, 8, 4096)
        assertContentEquals(a, b)
        assertNotEquals(a.toHex(), c.toHex())
        // Pinned value: changing the generator silently would invalidate physical datasets.
        assertEquals(PINNED_SHA_PREFIX, DeterministicPayload.sha256(DeterministicPayload.generate(42L, 1024)).toHex().take(16))
        for (size in DeterministicPayload.STANDARD_SIZES) assertEquals(size, DeterministicPayload.forTrial(1, 1, size).size)
    }

    @Test
    fun labFrameRoundTripAndCrc() {
        val f = LabFrame(FrameType.DATA, 0xBEEF, 12, SchemeId.RAPTORQ, 123456L, 1_048_576L, ByteArray(100) { it.toByte() })
        val raw = f.encode()
        assertEquals(LabFrame.OVERHEAD_BYTES + 100, raw.size)
        assertEquals(f, LabFrame.parse(raw))
        val bad = raw.copyOf().also { it[20] = (it[20].toInt() xor 1).toByte() }
        assertFailsWith<FrameFormatException> { LabFrame.parse(bad) }
        assertFailsWith<FrameFormatException> { LabFrame.parse(raw.copyOf(10)) }
        assertNull(LabFrame.parseOrNull(null))
    }

    @Test
    fun trialConfigRoundTrip() {
        for (spec in listOf(QrSpec(25, QrEcc.M), GridSpec(96, 208, 2, 32, 1), GridSpec(64, 140, 1, 48, 2))) {
            val tc = TrialConfig("M1_20261003_120000_A1", 3, 17, Stage.COARSE, 1, spec, 15, SchemeId.LT, 900, 65536, 73, 25_000)
            assertEquals(tc, TrialConfig.parse(tc.encode()))
        }
    }

    /** Fuzz: random and mutated inputs may only ever raise FrameFormatException (seeded, reproducible). */
    @Test
    fun fuzzParsersNeverCrash() {
        val seed = 20261003L
        val rnd = Random(seed)
        val valid = LabFrame(FrameType.ANNOUNCE, 1, 2, SchemeId.SEQUENTIAL, 0, 0,
            TrialConfig("S", 0, 1, Stage.MANUAL, 0, QrSpec(10, QrEcc.M), 10, SchemeId.SEQUENTIAL, 100, 100, 1, 1000).encode()).encode()
        repeat(20_000) { iter ->
            val input = when (iter % 4) {
                0 -> ByteArray(rnd.nextInt(0, 64)) { rnd.nextInt().toByte() }
                1 -> valid.copyOf().also { b -> repeat(rnd.nextInt(1, 4)) { b[rnd.nextInt(b.size)] = rnd.nextInt().toByte() } }
                2 -> valid.copyOf(rnd.nextInt(0, valid.size + 8))
                else -> fixCrc(valid.copyOf().also { b -> repeat(rnd.nextInt(1, 6)) { b[rnd.nextInt(b.size - 4)] = rnd.nextInt().toByte() } })
            }
            try {
                val f = LabFrame.parse(input)
                if (f.type == FrameType.ANNOUNCE) TrialConfig.parse(f.body)
            } catch (_: FrameFormatException) {
            } catch (e: Throwable) {
                throw AssertionError("seed=$seed iter=$iter unexpected ${e::class.simpleName}: ${e.message}", e)
            }
        }
    }

    private fun fixCrc(b: ByteArray): ByteArray {
        val crc = Crc32c.compute(b, 0, b.size - 4)
        for (i in 0 until 4) b[b.size - 4 + i] = (crc ushr (8 * i)).toByte()
        return b
    }
}
