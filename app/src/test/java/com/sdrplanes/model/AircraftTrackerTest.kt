package com.sdrplanes.model

import com.sdrplanes.decode.Crc24
import com.sdrplanes.decode.LatLon
import com.sdrplanes.decode.ModeSDecoderTest
import com.sdrplanes.decode.Velocity
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

    @Test
    fun surfacePositionUsesReceiverAsReference() {
        tracker.receiverPosition = LatLon(-43.5, 172.5)
        val f = "8FC8200A3AB8F5F893096B000000".hexToBytes()
        val crc = Crc24.compute(f, 11)
        f[11] = (crc ushr 16).toByte(); f[12] = (crc ushr 8).toByte(); f[13] = crc.toByte()
        tracker.update(decoder.decode(f, 0)!!, 0.5f, 0)
        val a = tracker.snapshot().single()
        assertTrue(a.onGround)
        assertEquals(-43.48564, a.position!!.lat, 1e-4)
        assertEquals(172.53942, a.position!!.lon, 1e-4)
    }

    @Test
    fun commBTrackAndTurnIgnoredWhenItContradictsAdsb() {
        val df20 = "A000139381951536E024D4CCF6B5".hexToBytes()
        val icao = Crc24.residual(df20, 112)
        decoder.decode(ModeSDecoderTest.allCall(icao), 0)
        // ADS-B says 150 kt; the BDS 5,0 candidate says 438 kt -> must not be applied.
        tracker.update(Velocity(icao, 17, 150.0, 114.0, false, 0), 0.5f, 0)
        tracker.update(decoder.decode(df20.copyOf(), 1)!!, 0.5f, 1)
        assertNull(tracker.snapshot().single().air.trueAirspeedKt)

        val t2 = AircraftTracker()
        t2.update(Velocity(icao, 17, 440.0, 114.0, false, 0), 0.5f, 0)
        t2.update(decoder.decode(df20.copyOf(), 2)!!, 0.5f, 2)
        assertEquals(424, t2.snapshot().single().air.trueAirspeedKt)
    }

    @Test
    fun tcasRaShowsAndExpires() {
        val me = com.sdrplanes.decode.Frames.bits56(
            com.sdrplanes.decode.Frames.f(1, 5, 28), com.sdrplanes.decode.Frames.f(6, 3, 2),
            com.sdrplanes.decode.Frames.f(9, 1, 1), com.sdrplanes.decode.Frames.f(10, 1, 1),
        )
        tracker.update(decoder.decode(com.sdrplanes.decode.Frames.df17(0x3C4B26, me), 0)!!, 0.5f, 0)
        assertEquals("CLIMB", tracker.snapshot().single().tcasRa)
        tracker.update(Velocity(0x3C4B26, 17, 280.0, 250.0, false, 0), 0.5f, 31_000) // keeps it alive
        tracker.expire(31_000)
        assertNull(tracker.snapshot().single().tcasRa)
    }

    @Test
    fun gicbCapabilityRejectsUnsupportedRegisters() {
        val f = com.sdrplanes.decode.Frames
        val icao = 0x3C656F
        decoder.decode(ModeSDecoderTest.allCall(icao), 0)
        // Transponder reports only 2,0 support.
        tracker.update(decoder.decode(f.df20(icao, f.bits56(f.f(7, 1, 1))), 1)!!, 0.5f, 1)
        tracker.update(Velocity(icao, 17, 440.0, 114.0, false, 0), 0.5f, 2)
        // A plausible 5,0 report is now refused.
        tracker.update(decoder.decode("A000139381951536E024D4CCF6B5".hexToBytes().also { patchAp(it, icao) }, 3)!!, 0.5f, 3)
        assertNull(tracker.snapshot().single().air.trueAirspeedKt)
    }

    private fun patchAp(frame: ByteArray, icao: Int) {
        val ap = Crc24.compute(frame, 11) xor icao
        frame[11] = (ap ushr 16).toByte(); frame[12] = (ap ushr 8).toByte(); frame[13] = ap.toByte()
    }

    @Test
    fun databaseInfoIsAttachedOnce() {
        var lookups = 0
        val t = AircraftTracker(lookup = { lookups++; com.sdrplanes.db.AircraftInfo(registration = "D-AIKO") })
        t.update(Velocity(0x3C656F, 17, 400.0, 90.0, false, 0), 0.5f, 0)
        t.update(Velocity(0x3C656F, 17, 401.0, 90.0, false, 0), 0.5f, 1)
        assertEquals(1, lookups)
        assertEquals("D-AIKO", t.snapshot().single().label)
    }
}
