package ghostlink.lab.codecs

import ghostlink.lab.codecs.erasure.AddResult
import ghostlink.lab.codecs.erasure.Erasure
import ghostlink.lab.codecs.erasure.LtCode
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ErasureTest {
    private val schemes = SchemeId.entries

    @Test
    fun chunkingCoversPayloadExactly() {
        for ((size, t) in listOf(1 to 1, 1 to 100, 255 to 16, 256 to 16, 1000 to 999, 4096 to 512)) {
            val k = Erasure.sourceSymbols(size, t)
            assertEquals((size + t - 1) / t, k)
            val p = DeterministicPayload.generate(size.toLong(), size)
            val enc = Erasure.encoder(SchemeId.SEQUENTIAL, p, t)
            val dec = Erasure.decoder(SchemeId.SEQUENTIAL, size, t)
            for (i in 0 until k) dec.add(i.toLong(), enc.symbol(i.toLong()))
            assertContentEquals(p, dec.result())
        }
    }

    @Test
    fun inOrderNoLossAllSchemes() {
        for (scheme in schemes) for (size in listOf(1, 256, 1024, 4096, 65536)) {
            val t = 512
            val p = DeterministicPayload.generate(size * 31L, size)
            val enc = Erasure.encoder(scheme, p, t)
            val dec = Erasure.decoder(scheme, size, t)
            var id = 0L
            while (!dec.isComplete && id < enc.sourceSymbols * 3L + 20) { dec.add(id, enc.symbol(id)); id++ }
            assertTrue(dec.isComplete, "$scheme size=$size did not complete")
            assertContentEquals(p, dec.result(), "$scheme size=$size")
        }
    }

    /**
     * Property test: random loss (iid + bursts), duplicates and reordering. Each case is derived from
     * a printed seed, so any failure is reproducible by re-running that seed.
     */
    @Test
    fun randomLossDuplicatesReorderProperty() {
        val master = Random(0x5EED)
        repeat(120) { case ->
            val seed = master.nextLong()
            val rnd = Random(seed)
            val scheme = schemes[case % schemes.size]
            val size = listOf(300, 2048, 20_000, 100_000)[rnd.nextInt(4)]
            val t = listOf(64, 200, 777, 1200)[rnd.nextInt(4)]
            val loss = rnd.nextDouble(0.0, 0.5)
            val p = DeterministicPayload.generate(seed, size)
            val enc = Erasure.encoder(scheme, p, t)
            val dec = Erasure.decoder(scheme, size, t)
            val window = ArrayList<Long>()
            var id = 0L
            var burst = 0
            val limit = enc.sourceSymbols * 40L + 200
            while (!dec.isComplete && id < limit) {
                if (burst > 0) { burst--; id++; continue }
                if (rnd.nextDouble() < 0.02) { burst = rnd.nextInt(1, 8); continue }
                if (rnd.nextDouble() >= loss) {
                    window.add(id)
                    if (rnd.nextDouble() < 0.1) window.add(id) // duplicate
                }
                if (window.size >= 6) {
                    window.shuffle(rnd)
                    for (w in window) dec.add(w, enc.symbol(w))
                    window.clear()
                }
                id++
            }
            for (w in window) dec.add(w, enc.symbol(w))
            assertTrue(dec.isComplete, "seed=$seed scheme=$scheme size=$size t=$t loss=$loss not complete after $id")
            assertContentEquals(p, dec.result(), "seed=$seed scheme=$scheme")
        }
    }

    @Test
    fun duplicatesAreCountedAndRejectedInputsIgnored() {
        for (scheme in schemes) {
            val p = DeterministicPayload.generate(9, 5000)
            val enc = Erasure.encoder(scheme, p, 500)
            val dec = Erasure.decoder(scheme, 5000, 500)
            assertEquals(AddResult.ACCEPTED, dec.add(0, enc.symbol(0)))
            assertEquals(AddResult.DUPLICATE, dec.add(0, enc.symbol(0)))
            assertEquals(AddResult.REJECTED, dec.add(1, ByteArray(499)))
            assertEquals(AddResult.REJECTED, dec.add(-1, ByteArray(500)))
            assertEquals(1, dec.uniqueSymbols)
            assertEquals(1, dec.duplicateSymbols)
            assertFalse(dec.isComplete)
        }
    }

    @Test
    fun ltGaussianFallbackNeedsFewerSymbolsThanPeelingOnly() {
        val k = 400
        val t = 64
        val p = DeterministicPayload.generate(77, k * t)
        val enc = LtCode.Encoder(p, t)
        fun needed(ge: Boolean): Int {
            val dec = LtCode.Decoder(p.size, t, gaussianFallback = ge)
            // repair-only stream (receiver joined late): worst case for LT
            var id = k.toLong()
            while (!dec.isComplete) { dec.add(id, enc.symbol(id)); id++ }
            assertContentEquals(p, dec.result())
            return dec.uniqueSymbols
        }
        val peel = needed(false)
        val ge = needed(true)
        assertTrue(ge <= peel, "GE $ge vs peeling $peel")
    }
}
