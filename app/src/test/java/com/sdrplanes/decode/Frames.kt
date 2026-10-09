package com.sdrplanes.decode

/** Builds Mode S test frames from fields (bit numbers 1-based, as in ICAO Doc 9871). */
object Frames {
    /** 56-bit ME/MB value with each (first bit, width, value) field set. */
    fun bits56(vararg fields: Triple<Int, Int, Long>): Long {
        var v = 0L
        for ((start, width, value) in fields) {
            v = v or ((value and ((1L shl width) - 1)) shl (56 - (start + width - 1)))
        }
        return v
    }

    fun f(start: Int, width: Int, value: Long) = Triple(start, width, value)
    fun f(start: Int, width: Int, value: Int) = Triple(start, width, value.toLong())

    private fun put56(frame: ByteArray, from: Int, v: Long) {
        for (i in 0 until 7) frame[from + i] = (v ushr (48 - 8 * i)).toByte()
    }

    /** DF17 extended squitter with a valid CRC. */
    fun df17(icao: Int, me: Long): ByteArray {
        val f = ByteArray(14)
        f[0] = 0x8D.toByte()
        f[1] = (icao ushr 16).toByte(); f[2] = (icao ushr 8).toByte(); f[3] = icao.toByte()
        put56(f, 4, me)
        val crc = Crc24.compute(f, 11)
        f[11] = (crc ushr 16).toByte(); f[12] = (crc ushr 8).toByte(); f[13] = crc.toByte()
        return f
    }

    /** DF20 Comm-B altitude reply whose address/parity encodes [icao]. */
    fun df20(icao: Int, mb: Long, altitudeField: Int = 0): ByteArray {
        val f = ByteArray(14)
        f[0] = (20 shl 3).toByte()
        f[2] = (altitudeField ushr 8).toByte(); f[3] = altitudeField.toByte()
        put56(f, 4, mb)
        val ap = Crc24.compute(f, 11) xor icao
        f[11] = (ap ushr 16).toByte(); f[12] = (ap ushr 8).toByte(); f[13] = ap.toByte()
        return f
    }

    /** Identity field (13 bits) for a Mode A code like "7700". */
    fun squawkField(sq: String): Int {
        val d = sq.map { it - '0' }
        val map = mapOf(7 to (0 to 4), 9 to (0 to 2), 11 to (0 to 1), 1 to (1 to 4), 3 to (1 to 2), 5 to (1 to 1),
            8 to (2 to 4), 10 to (2 to 2), 12 to (2 to 1), 0 to (3 to 4), 2 to (3 to 2), 4 to (3 to 1))
        var f = 0
        for ((bit, dw) in map) if (d[dw.first] and dw.second != 0) f = f or (1 shl bit)
        return f
    }
}
