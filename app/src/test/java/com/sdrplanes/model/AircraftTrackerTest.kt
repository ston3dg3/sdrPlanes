package com.sdrplanes.model

import com.sdrplanes.decode.LatLon
import com.sdrplanes.decode.ModeSDecoder
import com.sdrplanes.decode.hexToBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AircraftTrackerTest {
    private val decoder = ModeSDecoder()
    private val tracker = AircraftTracker()

    private fun feed(hex: String, t: Long) = tracker.update(decoder.decode(hex.hexToBytes(), t)!!, 0.5f, t)

    @Test
    fun evenOddPairProducesPosition() {
        feed("8D40621D58C386435CC412692AD6", 0)     // odd
        assertNull(tracker.snapshot().single().position)
        feed("8D40621D58C382D690C8AC2863A7", 2000)  // even, newer
        val a = tracker.snapshot().single()
        assertEquals(52.2572, a.position!!.lat, 1e-4)
        assertEquals(3.91937, a.position!!.lon, 1e-4)
        assertEquals(38000, a.altitudeFt)
        assertEquals("40621D", a.icaoHex)
    }

    @Test
    fun pairTooFarApartIsIgnored() {
        feed("8D40621D58C386435CC412692AD6", 0)
        feed("8D40621D58C382D690C8AC2863A7", 20_000)
        assertNull(tracker.snapshot().single().position)
    }

    @Test
    fun receiverRangeRejectsDistantFix() {
        tracker.receiverPosition = LatLon(-33.9, 151.2) // Sydney, far from the Netherlands
        feed("8D40621D58C386435CC412692AD6", 0)
        feed("8D40621D58C382D690C8AC2863A7", 2000)
        assertNull(tracker.snapshot().single().position)
    }

    @Test
    fun mergesCallsignAndVelocityAndExpires() {
        feed("8D4840D6202CC371C32CE0576098", 0)
        feed("8D485020994409940838175B284F", 0)
        val byIcao = tracker.snapshot().associateBy { it.icaoHex }
        assertEquals("KLM1023", byIcao.getValue("4840D6").callsign)
        assertEquals(159.2, byIcao.getValue("485020").speedKt!!, 0.1)

        tracker.expire(120_000)
        assertTrue(tracker.snapshot().isEmpty())
    }
}
