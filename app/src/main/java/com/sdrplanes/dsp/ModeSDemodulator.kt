package com.sdrplanes.dsp

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Mode S demodulator for 8-bit unsigned interleaved IQ at any rate from ~2 MS/s up
 * (2.0 and 2.4 MS/s are the useful RTL-SDR rates).
 *
 * Preamble: 0.5 µs pulses at 0, 1.0, 3.5 and 4.5 µs, silence elsewhere until the data
 * starts at 8 µs. Each data bit is pulse-position modulated over 1 µs: energy in the
 * first half = 1.
 *
 * Because 0.5 µs is not a whole number of samples (and the signal's arrival is random),
 * energies are integrated over exact half-bit windows on the cumulative magnitude,
 * interpolating inside samples. Each candidate is evaluated at several sub-sample
 * phases and neighbouring start samples; the best-scoring alignments are sliced in turn
 * until one passes the CRC ("phase correction").
 *
 * [onFrame] receives a candidate frame (14 bytes; 56-bit frames use the first 7) and the
 * signal level (0..1). It returns true if the frame decoded, so the demodulator can skip it.
 */
class ModeSDemodulator(
    val sampleRate: Int,
    private val tuning: Tuning,
    private val onFrame: (frame: ByteArray, signal: Float) -> Boolean,
) {
    constructor(sampleRate: Int = SAMPLE_RATE, onFrame: (frame: ByteArray, signal: Float) -> Boolean) :
        this(sampleRate, Tuning(), onFrame)

    /** Detection thresholds (exposed for benchmarking). */
    data class Tuning(
        val phases: Int = 5,
        val maxTries: Int = 3,
        /** Sum of the 4 preamble pulse peaks must exceed 4 x noise x this. */
        val gateFactor: Float = 1.9f,
        val pulseFactor: Float = 1.2f,
        val minRatio: Float = 1.6f,
    )

    private val sps = sampleRate / 1_000_000.0

    /** Window boundaries (sample offset from the candidate start: whole part + fraction). */
    private class Bounds(times: DoubleArray, sps: Double, phase: Double) {
        val idx = IntArray(times.size)
        val frac = FloatArray(times.size)

        init {
            for (k in times.indices) {
                val x = phase + times[k] * sps
                val i = floor(x).toInt()
                idx[k] = i
                frac[k] = (x - i).toFloat()
            }
        }
    }

    private val preTimes = doubleArrayOf(0.0, 0.5, 1.0, 1.5, 3.5, 4.0, 4.5, 5.0, 8.0)
    private val bitTimes = DoubleArray(BITS_MAX * 2 + 1) { 8.0 + 0.5 * it }
    private val prePhase = Array(tuning.phases) { Bounds(preTimes, sps, it.toDouble() / tuning.phases) }
    private val bitPhase = Array(tuning.phases) { Bounds(bitTimes, sps, it.toDouble() / tuning.phases) }

    /** Samples needed after a start position to slice a long frame (with slack). */
    private val fullLen = ceil((8.0 + BITS_MAX) * sps).toInt() + 3
    private val shortFrameSamples = ((8.0 + 56) * sps).toInt()
    private val longFrameSamples = ((8.0 + 112) * sps).toInt()
    /** Neighbouring start samples searched around a detection (covers one pulse width). */
    private val searchSpan = ceil(sps / 2).toInt() + 1
    /** Nearest sample to each preamble pulse centre, for the cheap gate. */
    private val gateOffsets = doubleArrayOf(0.25, 1.25, 3.75, 4.75).map { floor(it * sps).toInt() }
    private val g0 = gateOffsets[0]
    private val g1 = gateOffsets[1]
    private val g2 = gateOffsets[2]
    private val g3 = gateOffsets[3]

    private var mag = IntArray(0)
    private var sum = LongArray(0)
    private var carry = 0
    private val frame = ByteArray(14)
    private val pb = FloatArray(9)
    private val bb = FloatArray(BITS_MAX * 2 + 1)
    private var noise = 0f

    /** Mean magnitude (noise floor estimate) in 8-bit ADC units. */
    val noiseFloor: Float get() = noise / MAG_SCALE.toFloat()

    private val candScore = FloatArray(tuning.maxTries)
    private val candJ = IntArray(tuning.maxTries)
    private val candPhase = IntArray(tuning.maxTries)

    var preamblesDetected = 0L
        private set

    /** Feeds [len] bytes of IQ (must be even). */
    fun process(iq: ByteArray, len: Int = iq.size) {
        val n = len / 2
        val total = carry + n
        if (mag.size < total) {
            mag = mag.copyOf(total)
            sum = sum.copyOf(total + 1)
        }
        val m = mag
        var k = 0
        var o = carry
        var acc = 0L
        while (k < len - 1) {
            val v = LUT[((iq[k].toInt() and 0xFF) shl 8) or (iq[k + 1].toInt() and 0xFF)]
            m[o++] = v
            acc += v
            k += 2
        }
        // Mean magnitude ~ noise floor (signals occupy a small fraction of the time).
        val mean = if (n > 0) acc.toFloat() / n else noise
        noise = if (noise == 0f) mean else noise * 0.9f + mean * 0.1f

        val s = sum
        s[0] = 0
        for (i in 0 until total) s[i + 1] = s[i] + m[i]

        // Cheap gate: the four preamble pulses together must stand out from the noise floor.
        val gate = (noise * 4 * tuning.gateFactor).toInt()
        var j = 0
        val last = total - fullLen - searchSpan
        while (j < last) {
            val pulses = maxOf(m[j + g0], m[j + g0 + 1]) + maxOf(m[j + g1], m[j + g1 + 1]) +
                maxOf(m[j + g2], m[j + g2 + 1]) + maxOf(m[j + g3], m[j + g3 + 1])
            if (pulses > gate) {
                val consumed = tryAt(j)
                if (consumed > 0) {
                    j += consumed
                    continue
                }
            }
            j++
        }

        carry = (total - j).coerceAtLeast(0)
        System.arraycopy(m, j.coerceAtMost(total), m, 0, carry)
    }

    fun reset() {
        carry = 0
    }

    /** Integral of magnitude from the start of sample 0 to boundary k of [b], relative to [j]. */
    private fun at(b: Bounds, k: Int, j: Int): Float {
        val i = j + b.idx[k]
        return (sum[i] - sum[j]).toFloat() + b.frac[k] * mag[i]
    }

    /** Preamble quality at start sample [j] / [phase], or 0 if it is not a plausible preamble. */
    private fun preambleScore(j: Int, phase: Int): Float {
        val b = prePhase[phase]
        for (k in 0 until 9) pb[k] = at(b, k, j)
        val p0 = pb[1] - pb[0]
        val p1 = pb[3] - pb[2]
        val p2 = pb[5] - pb[4]
        val p3 = pb[7] - pb[6]
        val high = p0 + p1 + p2 + p3                                       // 2 µs of pulses
        val quiet = (pb[2] - pb[1]) + (pb[4] - pb[3]) + (pb[6] - pb[5]) + (pb[8] - pb[7]) // 6 µs
        val halfBit = 0.5f * sps.toFloat()
        val floor = noise * halfBit
        // Every pulse must clear both the noise floor and the gaps around it.
        val quietPerHalf = quiet / 12f
        val minPulse = maxOf(floor, quietPerHalf) * tuning.pulseFactor
        if (p0 < minPulse || p1 < minPulse || p2 < minPulse || p3 < minPulse) return 0f
        val ratio = (high / 4f) / (quietPerHalf + floor * 0.25f)
        return if (ratio >= tuning.minRatio) ratio else 0f
    }

    /** Returns samples consumed if a frame was accepted near [j0], else 0. */
    private fun tryAt(j0: Int): Int {
        var count = 0
        for (dj in 0..searchSpan) {
            val j = j0 + dj
            for (ph in 0 until tuning.phases) {
                val sc = preambleScore(j, ph)
                if (sc <= 0f) continue
                // Keep the best few alignments, sorted by score.
                var pos = count
                while (pos > 0 && candScore[pos - 1] < sc) pos--
                if (pos >= tuning.maxTries) continue
                val end = minOf(count, tuning.maxTries - 1)
                for (q in end downTo pos + 1) {
                    candScore[q] = candScore[q - 1]; candJ[q] = candJ[q - 1]; candPhase[q] = candPhase[q - 1]
                }
                candScore[pos] = sc; candJ[pos] = j; candPhase[pos] = ph
                if (count < tuning.maxTries) count++
            }
        }
        if (count == 0) return 0
        preamblesDetected++

        for (c in 0 until count) {
            val j = candJ[c]
            val bits = slice(j, candPhase[c])
            if (bits == 0) continue
            val b = prePhase[candPhase[c]]
            val high = (at(b, 1, j) - at(b, 0, j)) + (at(b, 3, j) - at(b, 2, j)) +
                (at(b, 5, j) - at(b, 4, j)) + (at(b, 7, j) - at(b, 6, j))
            val signal = (high / (2f * sps.toFloat()) / MAX_MAG).coerceIn(0f, 1f)
            if (onFrame(frame.copyOf(), signal)) {
                return (j - j0) + if (bits == 56) shortFrameSamples else longFrameSamples
            }
        }
        return 0
    }

    /** Slices bits for the alignment into [frame]; returns the frame length or 0. */
    private fun slice(j: Int, phase: Int): Int {
        val b = bitPhase[phase]
        // DF (first 5 bits) decides the length.
        for (k in 0..10) bb[k] = at(b, k, j)
        var df = 0
        for (bit in 0 until 5) df = (df shl 1) or (if (bb[2 * bit + 1] - bb[2 * bit] > bb[2 * bit + 2] - bb[2 * bit + 1]) 1 else 0)
        val bits = when (df) {
            0, 4, 5, 11 -> 56
            16, 17, 18, 20, 21 -> 112
            else -> return 0
        }
        for (k in 11..bits * 2) bb[k] = at(b, k, j)
        frame.fill(0)
        for (bit in 0 until bits) {
            val first = bb[2 * bit + 1] - bb[2 * bit]
            val second = bb[2 * bit + 2] - bb[2 * bit + 1]
            if (first > second) frame[bit ushr 3] = (frame[bit ushr 3].toInt() or (0x80 ushr (bit and 7))).toByte()
        }
        return bits
    }

    companion object {
        const val SAMPLE_RATE = 2_000_000
        const val FREQUENCY_HZ = 1_090_000_000L
        private const val BITS_MAX = 112
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
