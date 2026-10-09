package com.sdrplanes.decode

/** Reads [count] (≤ 63) bits starting at 0-based bit [from], MSB first. */
internal fun ByteArray.bits(from: Int, count: Int): Long {
    var v = 0L
    for (i in from until from + count) {
        val bit = (this[i ushr 3].toInt() ushr (7 - (i and 7))) and 1
        v = (v shl 1) or bit.toLong()
    }
    return v
}

internal fun ByteArray.bit(at: Int): Boolean = bits(at, 1) == 1L

internal fun ByteArray.toHex(len: Int = size): String =
    buildString(len * 2) { for (i in 0 until len) append("%02X".format(this@toHex[i].toInt() and 0xFF)) }

internal fun String.hexToBytes(): ByteArray {
    val clean = trim().removePrefix("*").removeSuffix(";")
    return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
