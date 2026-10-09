package com.sdrplanes.decode

/**
 * Mode S CRC-24 (generator 0x1FFF409).
 *
 * The parity field of every Mode S frame is the last 24 bits. For DF11/17/18 the
 * residual (computed CRC xor parity field) is 0 (or the interrogator id for DF11);
 * for DF0/4/5/16/20/21 the residual is the aircraft's ICAO address ("address/parity").
 */
object Crc24 {
    private const val POLY = 0xFFF409

    private val table = IntArray(256).also { t ->
        for (i in 0 until 256) {
            var c = i shl 16
            repeat(8) {
                c = if (c and 0x800000 != 0) (c shl 1) xor POLY else c shl 1
            }
            t[i] = c and 0xFFFFFF
        }
    }

    /** CRC over the first [len] bytes of [data]. */
    fun compute(data: ByteArray, len: Int): Int {
        var crc = 0
        for (i in 0 until len) {
            crc = ((crc shl 8) xor table[((crc ushr 16) xor data[i].toInt()) and 0xFF]) and 0xFFFFFF
        }
        return crc
    }

    /** CRC of the payload xor the transmitted parity field. */
    fun residual(msg: ByteArray, bits: Int): Int {
        val n = bits / 8
        val parity = ((msg[n - 3].toInt() and 0xFF) shl 16) or
            ((msg[n - 2].toInt() and 0xFF) shl 8) or
            (msg[n - 1].toInt() and 0xFF)
        return compute(msg, n - 3) xor parity
    }

    /**
     * Syndrome -> bit index for single-bit errors in 112-bit frames. CRC is linear, so
     * flipping bit i changes the residual by a fixed syndrome regardless of content.
     * The first 5 bits (DF) are excluded: fixing them would change the frame type.
     */
    private val singleBitSyndromes112: Map<Int, Int> = buildMap {
        for (bit in 5 until 112) {
            val m = ByteArray(14)
            m[bit / 8] = (0x80 ushr (bit % 8)).toByte()
            put(residual(m, 112), bit)
        }
    }

    /**
     * Tries to repair a single flipped bit in a 112-bit frame whose residual should be 0.
     * Returns true (and modifies [msg]) on success.
     */
    fun fixSingleBit112(msg: ByteArray, residual: Int): Boolean {
        val bit = singleBitSyndromes112[residual] ?: return false
        msg[bit / 8] = (msg[bit / 8].toInt() xor (0x80 ushr (bit % 8))).toByte()
        return true
    }
}
