package ghostlink.lab.codecs

import java.security.MessageDigest

/**
 * Deterministic benchmark payloads: both phones derive the same bytes from (session, trial, size),
 * so the receiver can verify SHA-256 byte-for-byte without ever receiving the expected hash.
 */
object DeterministicPayload {
    /** Standard payload sizes from the M1 brief. */
    val STANDARD_SIZES: List<Int> = listOf(256, 1024, 4096, 16384, 65536, 262144, 1048576)

    fun seedFor(sessionTag: Int, trial: Int, sizeBytes: Int): Long =
        SplitMix64.mix(0x47484F53544C4E4BL xor (sessionTag.toLong() shl 40) xor (trial.toLong() shl 20) xor sizeBytes.toLong())

    fun generate(seed: Long, sizeBytes: Int): ByteArray {
        require(sizeBytes in 0..(64 shl 20)) { "payload size out of range: $sizeBytes" }
        val rng = SplitMix64(seed)
        val out = ByteArray(sizeBytes)
        var i = 0
        while (i < sizeBytes) {
            var r = rng.next()
            var k = 0
            while (k < 8 && i < sizeBytes) { out[i++] = r.toByte(); r = r ushr 8; k++ }
        }
        return out
    }

    fun forTrial(sessionTag: Int, trial: Int, sizeBytes: Int): ByteArray =
        generate(seedFor(sessionTag, trial, sizeBytes), sizeBytes)

    fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)
}

/** SplitMix64 PRNG (public domain algorithm by S. Vigna). Deterministic across JVM and Android. */
class SplitMix64(private var state: Long) {
    fun next(): Long {
        state += GOLDEN
        return mix(state)
    }

    /** Uniform int in [0, bound). */
    fun nextInt(bound: Int): Int {
        require(bound > 0)
        return ((next() ushr 33) % bound).toInt()
    }

    /** Uniform double in [0, 1). */
    fun nextDouble(): Double = (next() ushr 11) * (1.0 / (1L shl 53))

    companion object {
        private const val GOLDEN = -0x61c8864680b583ebL // 0x9E3779B97F4A7C15
        fun mix(z0: Long): Long {
            var z = z0
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L // 0xBF58476D1CE4E5B9
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L // 0x94D049BB133111EB
            return z xor (z ushr 31)
        }
    }
}
