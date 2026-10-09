package com.sdrplanes.sdr

import com.sdrplanes.decode.ModeSDecoder
import com.sdrplanes.dsp.ModeSDemodulator
import com.sdrplanes.model.AircraftTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Live check against a real rtl_tcp server, e.g. on the dev PC:
 *   rtl_tcp -a 127.0.0.1 -p 1234
 *   RTL_TCP_HOST=127.0.0.1 ./gradlew :app:testDebugUnitTest --tests '*RtlTcpLiveTest*' -i
 * Skipped unless RTL_TCP_HOST is set.
 */
class RtlTcpLiveTest {
    @Test
    fun streamsAndDecodes() {
        val host = System.getenv("RTL_TCP_HOST")
        assumeTrue("RTL_TCP_HOST not set", !host.isNullOrBlank())
        val port = System.getenv("RTL_TCP_PORT")?.toIntOrNull() ?: 1234
        val seconds = System.getenv("RTL_TCP_SECONDS")?.toIntOrNull() ?: 15

        var info: TunerInfo? = null
        var bytes = 0L
        val decoder = ModeSDecoder()
        val tracker = AircraftTracker()
        val demod = ModeSDemodulator { frame, signal ->
            val now = System.currentTimeMillis()
            val msg = decoder.decode(frame, now) ?: return@ModeSDemodulator false
            println("frame ${msg.javaClass.simpleName} %06X $msg".format(msg.icao))
            tracker.update(msg, signal, now)
            true
        }

        val started = System.currentTimeMillis()
        runBlocking(Dispatchers.IO) {
            withTimeoutOrNull(seconds * 1000L) {
                RtlTcpSampleSource(host!!, port).stream(TunerConfig(), onInfo = { info = it }) { buf, n ->
                    bytes += n
                    demod.process(buf, n)
                }
            }
        }
        val elapsed = (System.currentTimeMillis() - started) / 1000.0
        val rate = bytes / 2 / elapsed

        println("tuner=$info samples/s=%.0f preambles=${demod.preamblesDetected} accepted=${decoder.acceptedFrames} corrected=${decoder.correctedFrames}".format(rate))
        tracker.snapshot().forEach { println("aircraft $it") }

        assertNotNull(info)
        // Demodulation must keep up with the 2 MS/s stream.
        assertTrue("sample rate too low: $rate", rate > 1_800_000)
    }
}
