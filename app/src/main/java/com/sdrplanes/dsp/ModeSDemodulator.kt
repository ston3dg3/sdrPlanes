package com.sdrplanes.dsp

import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Mode S demodulator for 8-bit unsigned interleaved IQ at 2 MS/s (one bit = 2 samples).
 *
 * Preamble: pulses at 0, 1.0, 3.5 and 4.5 µs -> samples 0, 2, 7, 9; data starts at 8 µs
 * (sample 16). Each data bit is pulse-position modulated: high-then-low = 1.
 *
 * [onFrame] receives a candidate frame (always 14 bytes; 56-bit frames use the first 7)
 * and the signal level (0..1). It returns true if the frame decoded, so the
 * demodulator can skip over it.
 */
class ModeSDemodulator(
    private val onFrame: (frame: ByteArray, signal: Float) -> Boolean,
) {
    private var mag = IntArray(0)
    private var carry = 0
    private val frame = ByteArray(14)

    var preamblesDetected = 0L
        private set

    /** Feeds [len] bytes of IQ (must be even). */
    fun process(iq: ByteArray, len: Int = iq.size) {
        val n = len / 2
        val total = carry + n
        if (mag.size < total) mag = mag.copyOf(total)
        var k = 0
        var o = carry
        while (k < len - 1) {
            mag[o++] = LUT[((iq[k].toInt() and 0xFF) shl 8) or (iq[k + 1].toInt() and 0xFF)]
            k += 2
        }

        var j = 0
        val last = total - FULL_LEN
        while (j < last) {
            val consumed = tryDecodeAt(j)
            j += if (consumed > 0) consumed else 1
        }

        carry = (total - j).coerceAtLeast(0)
        System.arraycopy(mag, j.coerceAtMost(total), mag, 0, carry)
    }

    fun reset() {
        carry = 0
    }

    /** Returns samples consumed if a frame was accepted at [j], else 0. */
    private fun tryDecodeAt(j: Int): Int {
        val m = mag
        val m0 = m[j]; val m1 = m[j + 1]; val m2 = m[j + 2]; val m3 = m[j + 3]
        val m4 = m[j + 4]; val m5 = m[j + 5]; val m6 = m[j + 6]; val m7 = m[j + 7]
        val m8 = m[j + 8]; val m9 = m[j + 9]

        if (!(m0 > m1 && m1 < m2 && m2 > m3 && m3 < m0 &&
                m4 < m0 && m5 < m0 && m6 < m0 &&
                m7 > m8 && m8 < m9 && m9 > m6)
        ) return 0

        val high = (m0 + m2 + m7 + m9) / 6
        if (m4 >= high || m5 >= high) return 0
        if (m[j + 11] >= high || m[j + 12] >= high || m[j + 13] >= high || m[j + 14] >= high) return 0
        preamblesDetected++

        // Downlink format comes from the first 5 bits; it decides the frame length.
        var df = 0
        for (b in 0 until 5) {
            val p = j + PREAMBLE + b * 2
            df = (df shl 1) or (if (m[p] > m[p + 1]) 1 else 0)
        }
        val bits = when (df) {
            0, 4, 5, 11 -> 56
            16, 17, 18, 20, 21 -> 112
            else -> return 0
        }

        frame.fill(0)
        for (b in 0 until bits) {
            val p = j + PREAMBLE + b * 2
            if (m[p] > m[p + 1]) {
                frame[b ushr 3] = (frame[b ushr 3].toInt() or (0x80 ushr (b and 7))).toByte()
            }
        }

        val signal = (m0 + m2 + m7 + m9) / 4f / MAX_MAG
        return if (onFrame(frame.copyOf(), signal)) PREAMBLE + bits * 2 else 0
    }

    companion object {
        const val SAMPLE_RATE = 2_000_000
        const val FREQUENCY_HZ = 1_090_000_000L
        private const val PREAMBLE = 16
        private const val FULL_LEN = PREAMBLE + 112 * 2
        private const val MAG_SCALE = 256.0
        private const val MAX_MAG = (128 * 1.4142 * MAG_SCALE).toFloat()

        /** |I + jQ| for every (I, Q) byte pair, centred on 127.4 and scaled by [MAG_SCALE]. */
        private val LUT = IntArray(65536) { idx ->
            val i = (idx ushr 8) - 127.4
            val q = (idx and 0xFF) - 127.4
            (sqrt(i * i + q * q) * MAG_SCALE).roundToInt()
        }
    }
}
