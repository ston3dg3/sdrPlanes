package com.sdrplanes.dsp

import com.sdrplanes.decode.AirbornePosition
import com.sdrplanes.decode.ModeSDecoder
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Decodes recorded 8-bit IQ files (rtl_sdr format) and prints statistics, to compare
 * demodulator changes on real signals:
 *   IQ_FILES=a.bin;b.bin ./gradlew :app:testDebugUnitTest --tests '*IqFileBenchmarkTest*' -i
 * Optional IQ_RATE (default 2000000). Skipped unless IQ_FILES is set.
 */
class IqFileBenchmarkTest {
    @Test
    fun decodeFiles() {
        val files = System.getenv("IQ_FILES")
        assumeTrue("IQ_FILES not set", !files.isNullOrBlank())
        val rate = System.getenv("IQ_RATE")?.toIntOrNull() ?: ModeSDemodulator.SAMPLE_RATE
        for (path in files!!.split(';').filter { it.isNotBlank() }) {
            val decoder = ModeSDecoder()
            val byDf = sortedMapOf<Int, Int>()
            val icaos = HashSet<Int>()
            var positions = 0
            var now = 0L
            val demod = ModeSDemodulator(rate) { frame, _ ->
                val msg = decoder.decode(frame, now) ?: return@ModeSDemodulator false
                byDf.merge(msg.df, 1, Int::plus)
                icaos += msg.icao
                if (msg is AirbornePosition) positions++
                true
            }
            val file = File(path)
            val buf = ByteArray(1 shl 18)
            val t0 = System.nanoTime()
            var read = 0L
            file.inputStream().buffered().use { input ->
                while (true) {
                    val n = input.read(buf)
                    if (n <= 0) break
                    val even = n and 1.inv()
                    demod.process(buf, even)
                    read += even
                    now = read / 2 * 1000 / rate
                }
            }
            val cpuMs = (System.nanoTime() - t0) / 1_000_000
            val seconds = read / 2.0 / rate
            println(
                "%s: %.1fs audio, cpu %d ms (%.0f%% realtime) | preambles %d | accepted %d (%.1f/s) corrected %d | aircraft %d | positions %d | by DF %s"
                    .format(
                        file.name, seconds, cpuMs, cpuMs / 10.0 / seconds, demod.preamblesDetected,
                        decoder.acceptedFrames, decoder.acceptedFrames / seconds, decoder.correctedFrames,
                        icaos.size, positions, byDf,
                    )
            )
        }
    }
}
