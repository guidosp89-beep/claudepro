package ghostlink.lab.codecs

/** Thrown for any malformed, truncated or out-of-range frame content. Never crash on input. */
class FrameFormatException(message: String) : Exception(message)

/** Bounds-checked little-endian reader. Every read validates remaining length first. */
class ByteReader(private val buf: ByteArray, private var pos: Int = 0, private val end: Int = buf.size) {
    init {
        if (pos < 0 || end > buf.size || pos > end) throw FrameFormatException("bad reader bounds")
    }

    val remaining: Int get() = end - pos
    val position: Int get() = pos

    private fun need(n: Int) {
        if (n < 0 || n > remaining) throw FrameFormatException("truncated: need $n, have $remaining")
    }

    fun u8(): Int { need(1); return buf[pos++].toInt() and 0xFF }

    fun u16(): Int { need(2); val v = (buf[pos].toInt() and 0xFF) or ((buf[pos + 1].toInt() and 0xFF) shl 8); pos += 2; return v }

    fun u32(): Long {
        need(4)
        var v = 0L
        for (i in 0 until 4) v = v or ((buf[pos + i].toLong() and 0xFF) shl (8 * i))
        pos += 4
        return v
    }

    fun i32(): Int = u32().toInt()

    fun u64(): Long {
        need(8)
        var v = 0L
        for (i in 0 until 8) v = v or ((buf[pos + i].toLong() and 0xFF) shl (8 * i))
        pos += 8
        return v
    }

    fun bytes(n: Int): ByteArray { need(n); val out = buf.copyOfRange(pos, pos + n); pos += n; return out }

    fun rest(): ByteArray = bytes(remaining)
}

/** Growable little-endian writer. */
class ByteWriter(initial: Int = 64) {
    private var buf = ByteArray(initial)
    var size: Int = 0
        private set

    private fun ensure(n: Int) {
        if (size + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, size + n))
    }

    fun u8(v: Int): ByteWriter { require(v in 0..0xFF) { "u8 out of range: $v" }; ensure(1); buf[size++] = v.toByte(); return this }

    fun u16(v: Int): ByteWriter {
        require(v in 0..0xFFFF) { "u16 out of range: $v" }
        ensure(2); buf[size++] = v.toByte(); buf[size++] = (v ushr 8).toByte(); return this
    }

    fun u32(v: Long): ByteWriter {
        require(v in 0..0xFFFF_FFFFL) { "u32 out of range: $v" }
        ensure(4); for (i in 0 until 4) buf[size++] = (v ushr (8 * i)).toByte(); return this
    }

    fun u64(v: Long): ByteWriter { ensure(8); for (i in 0 until 8) buf[size++] = (v ushr (8 * i)).toByte(); return this }

    fun bytes(b: ByteArray, off: Int = 0, len: Int = b.size - off): ByteWriter {
        ensure(len); System.arraycopy(b, off, buf, size, len); size += len; return this
    }

    fun toByteArray(): ByteArray = buf.copyOf(size)
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "odd hex length" }
    return ByteArray(length / 2) { i -> substring(2 * i, 2 * i + 2).toInt(16).toByte() }
}
