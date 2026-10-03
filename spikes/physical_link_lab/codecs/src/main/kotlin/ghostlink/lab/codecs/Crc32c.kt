package ghostlink.lab.codecs

/**
 * CRC-32C (Castagnoli, reflected polynomial 0x82F63B78), same as GhostFrame v0.
 * Own table implementation because java.util.zip.CRC32C is not available on all Android API levels.
 */
object Crc32c {
    private const val POLY_REFLECTED = 0x82F63B78.toInt()
    private val table = IntArray(256).also { t ->
        for (n in 0 until 256) {
            var c = n
            repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor POLY_REFLECTED else c ushr 1 }
            t[n] = c
        }
    }

    fun compute(data: ByteArray, off: Int = 0, len: Int = data.size - off): Int {
        require(off >= 0 && len >= 0 && off + len <= data.size) { "bad crc range" }
        var crc = -1
        for (i in off until off + len) crc = table[(crc xor data[i].toInt()) and 0xFF] xor (crc ushr 8)
        return crc.inv()
    }
}
