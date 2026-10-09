package com.sdrplanes.dsp

import com.sdrplanes.decode.Crc24
import com.sdrplanes.decode.ModeSDecoder
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Decode rate of random DF17 frames versus SNR, with random sub-sample arrival times,
 * random carrier phase, a band-limited receiver and 8-bit quantisation.
 */
class SensitivityTest {

    /** Builds IQ with [count] frames at [snrDb] (peak pulse power / noise power). */
    private fun signal(rate: Int, snrDb: Double, count: Int, rnd: Random): ByteArray {
        val sps = rate / 1e6
        val spacing = (400 * sps).toInt()
        val n = spacing * (count + 2)
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        val noiseSigma = 3.0 // per component, in 8-bit LSB: a realistic RTL-SDR floor
        val amp = sqrt(2 * noiseSigma * noiseSigma * 10.0.pow(snrDb / 10))
        for (f in 0 until count) {
            val frame = randomDf17(rnd)
            val start = spacing * (f + 1) + rnd.nextDouble() * 2 // fractional arrival, in samples
            val ph = rnd.nextDouble() * 2 * PI
            // Pulse windows in µs: preamble then PPM bits.
            val pulses = ArrayList<Double>()
            pulses += listOf(0.0, 1.0, 3.5, 4.5)
            for (b in 0 until 112) {
                val one = (frame[b / 8].toInt() ushr (7 - b % 8)) and 1 == 1
                pulses += 8.0 + b + if (one) 0.0 else 0.5
            }
            for (p in pulses) {
                // Integrate the 0.5 µs rectangular pulse over each sample period (box filter).
                val a = start + p * sps
                val b = a + 0.5 * sps
                var k = kotlin.math.floor(a - 0.5).toInt()
                while (k <= b + 0.5) {
                    val overlap = (minOf(b, k + 0.5) - maxOf(a, k - 0.5)).coerceAtLeast(0.0)
                    if (k in 0 until n) {
                        re[k] += amp * overlap * cos(ph)
                        im[k] += amp * overlap * sin(ph)
                    }
                    k++
                }
            }
        }
        val out = ByteArray(n * 2)
        for (k in 0 until n) {
            out[2 * k] = q(re[k] + gauss(rnd) * noiseSigma)
            out[2 * k + 1] = q(im[k] + gauss(rnd) * noiseSigma)
        }
        return out
    }

    private fun q(v: Double) = (127.4 + v).roundToInt().coerceIn(0, 255).toByte()
    private fun gauss(rnd: Random) = sqrt(-2 * ln(1 - rnd.nextDouble())) * cos(2 * PI * rnd.nextDouble())

    private fun randomDf17(rnd: Random): ByteArray {
        val f = ByteArray(14)
        f[0] = (17 shl 3 or 5).toByte()
        for (i in 1 until 11) f[i] = rnd.nextInt(256).toByte()
        f[4] = (11 shl 3 or rnd.nextInt(8)).toByte() // TC 11 airborne position
        val crc = Crc24.compute(f, 11)
        f[11] = (crc shr 16).toByte(); f[12] = (crc shr 8).toByte(); f[13] = crc.toByte()
        return f
    }

    /** Frames recovered: CRC-valid DF17 after up to [fixBits] repaired bits. */
    private fun countDecoded(iq: ByteArray, fixBits: Int, make: ((ByteArray, Float) -> Boolean) -> (ByteArray, Int) -> Unit): Int {
        var ok = 0
        val process = make { frame, _ ->
            val good = (frame[0].toInt() and 0xFF) ushr 3 == 17 && run {
                val r = Crc24.residual(frame, 112)
                r == 0 || (fixBits > 0 && Crc24.fixBits112(frame, r, fixBits) > 0)
            }
            if (good) ok++
            good
        }
        var i = 0
        while (i < iq.size) {
            val len = minOf(65536, iq.size - i)
            process(iq.copyOfRange(i, i + len), len)
            i += len
        }
        return ok
    }

    private fun newDemod(rate: Int, tuning: ModeSDemodulator.Tuning = ModeSDemodulator.Tuning()) =
        { cb: (ByteArray, Float) -> Boolean -> val d = ModeSDemodulator(rate, tuning, cb); { b: ByteArray, l: Int -> d.process(b, l) } }

    private fun decodedFraction(rate: Int, snrDb: Double, fixBits: Int, count: Int = 300): Double {
        val iq = signal(rate, snrDb, count, Random(snrDb.toInt() * 7 + rate / 100000))
        return countDecoded(iq, fixBits, newDemod(rate)).toDouble() / count
    }

    /**
     * Regression guard. For reference, the previous fixed-phase 2 MS/s demodulator with
     * 1-bit repair recovered 24% / 41% / 60% at 10 / 12 / 15 dB on this signal model.
     */
    @Test
    fun sensitivity() {
        val report = StringBuilder()
        for (rate in listOf(2_000_000, 2_400_000)) for (snr in listOf(8.0, 10.0, 12.0, 15.0, 20.0)) {
            report.append(
                "rate %.1f snr %4.1f dB: raw %.0f%%, +2-bit repair %.0f%%\n".format(
                    rate / 1e6, snr, 100 * decodedFraction(rate, snr, 0), 100 * decodedFraction(rate, snr, 2),
                )
            )
        }
        println(report)
        assertTrue(decodedFraction(2_400_000, 12.0, 2) >= 0.8)
        assertTrue(decodedFraction(2_400_000, 15.0, 2) >= 0.95)
        assertTrue(decodedFraction(2_000_000, 12.0, 2) >= 0.45)
    }

    /** Threshold sweep; run with TUNE=1. */
    @Test
    fun tuneThresholds() {
        org.junit.Assume.assumeTrue(System.getenv("TUNE") != null)
        val count = 200
        val sets = listOf(1.5f, 1.7f, 1.9f, 2.1f, 2.3f).map { ModeSDemodulator.Tuning(gateFactor = it) }
        val rnd = Random(9)
        for (rate in listOf(2_000_000, 2_400_000)) {
            val sigs = listOf(10.0, 12.0, 15.0).map { it to signal(rate, it, count, Random(it.toInt() + rate)) }
            val noise = ByteArray(rate * 2) { q(gauss(rnd) * 3.0) }
            for (tu in sets) {
                val res = sigs.joinToString(" ") { (snr, iq) -> "%.0fdB:%d".format(snr, countDecoded(iq, 2, newDemod(rate, tu))) }
                var sliced = 0
                val d = ModeSDemodulator(rate, tu) { _, _ -> sliced++; false }
                val t0 = System.nanoTime()
                var i = 0
                while (i < noise.size) { val len = minOf(262144, noise.size - i); d.process(noise.copyOfRange(i, i + len), len); i += len }
                println("rate %.1f %s -> %s | noise: %d sliced/s, %.0f ms per s".format(rate / 1e6, tu, res, sliced, (System.nanoTime() - t0) / 1e6))
            }
        }
    }

    @Test
    fun noiseOnlyFalsePreambleRate() {
        val rnd = Random(9)
        for (rate in listOf(2_000_000, 2_400_000)) {
            val n = rate // 1 s
            val iq = ByteArray(n * 2) { q(gauss(rnd) * 3.0) }
            var frames = 0
            val d = ModeSDemodulator(rate) { _, _ -> frames++; false }
            var i = 0
            val t0 = System.nanoTime()
            while (i < iq.size) { val len = minOf(262144, iq.size - i); d.process(iq.copyOfRange(i, i + len), len); i += len }
            println("noise rate %.1f: preambles %d, frames sliced %d, %.0f ms/s".format(rate / 1e6, d.preamblesDetected, frames, (System.nanoTime() - t0) / 1e6))
        }
    }
}
