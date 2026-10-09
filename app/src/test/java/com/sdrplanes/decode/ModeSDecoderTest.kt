package com.sdrplanes.decode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Vectors from "The 1090 MHz Riddle" (mode-s.org) and the pyModeS test suite. */
class ModeSDecoderTest {
    private val decoder = ModeSDecoder()

    @Test
    fun crcOfValidDf17IsZero() {
        assertEquals(0, Crc24.residual("8D4840D6202CC371C32CE0576098".hexToBytes(), 112))
        assertEquals(0, Crc24.residual("8D485020994409940838175B284F".hexToBytes(), 112))
    }

    @Test
    fun identification() {
        val m = decoder.decode("8D4840D6202CC371C32CE0576098".hexToBytes(), 0) as Identification
        assertEquals(0x4840D6, m.icao)
        assertEquals("KLM1023", m.callsign)
    }

    @Test
    fun groundSpeedVelocity() {
        val m = decoder.decode("8D485020994409940838175B284F".hexToBytes(), 0) as Velocity
        assertEquals(0x485020, m.icao)
        assertEquals(159.20, m.speedKt!!, 0.01)
        assertEquals(182.88, m.trackDeg!!, 0.01)
        assertEquals(-832, m.verticalRateFpm)
        assertFalse(m.isAirspeed)
    }

    @Test
    fun airspeedVelocity() {
        val m = decoder.decode("8DA05F219B06B6AF189400CBC33F".hexToBytes(), 0) as Velocity
        assertTrue(m.isAirspeed)
        assertEquals(375.0, m.speedKt!!, 0.01)
        assertEquals(243.98, m.trackDeg!!, 0.01)
        assertEquals(-2304, m.verticalRateFpm)
    }

    @Test
    fun airbornePositionFields() {
        val even = decoder.decode("8D40621D58C382D690C8AC2863A7".hexToBytes(), 0) as AirbornePosition
        val odd = decoder.decode("8D40621D58C386435CC412692AD6".hexToBytes(), 0) as AirbornePosition
        assertEquals(38000, even.altitudeFt)
        assertFalse(even.odd)
        assertTrue(odd.odd)
        assertEquals(93000, even.cprLat)
        assertEquals(51372, even.cprLon)
        assertEquals(74158, odd.cprLat)
        assertEquals(50194, odd.cprLon)
    }

    @Test
    fun cprGlobalAndLocal() {
        val p = Cpr.global(93000, 51372, 74158, 50194, evenNewer = true)!!
        assertEquals(52.2572, p.lat, 1e-4)
        assertEquals(3.91937, p.lon, 1e-4)

        val l = Cpr.local(93000, 51372, odd = false, ref = LatLon(52.258, 3.918))
        assertEquals(52.2572, l.lat, 1e-4)
        assertEquals(3.91937, l.lon, 1e-4)
    }

    @Test
    fun cprNl() {
        assertEquals(59, Cpr.nl(0.0))
        assertEquals(36, Cpr.nl(52.2572))
        assertEquals(1, Cpr.nl(88.0))
    }

    @Test
    fun singleBitErrorIsCorrectedForKnownAircraft() {
        decoder.decode("8D4840D6202CC371C32CE0576098".hexToBytes(), 0) // learn 4840D6
        val damaged = "8D4840D6202CC371C32CE0576098".hexToBytes()
        damaged[6] = (damaged[6].toInt() xor 0x10).toByte()
        val m = decoder.decode(damaged, 1) as Identification
        assertEquals("KLM1023", m.callsign)
        assertEquals(1, decoder.correctedFrames)
    }

    @Test
    fun singleBitErrorRejectedForUnknownAircraft() {
        val damaged = "8D4840D6202CC371C32CE0576098".hexToBytes()
        damaged[6] = (damaged[6].toInt() xor 0x10).toByte()
        assertNull(decoder.decode(damaged, 0))
    }

    @Test
    fun addressParityRepliesNeedKnownIcao() {
        val df20 = "A02014B400000000000000F9D514".hexToBytes()
        assertNull(decoder.decode(df20.copyOf(), 0))

        val icao = Crc24.residual(df20, 112)
        decoder.decode(allCall(icao), 0)
        val m = decoder.decode(df20.copyOf(), 1) as AltitudeReply
        assertEquals(icao, m.icao)
        assertEquals(32300, m.altitudeFt)
    }

    @Test
    fun squawk() {
        val df21 = "A800292DFFBBA9383FFCEB903D01".hexToBytes()
        decoder.decode(allCall(Crc24.residual(df21, 112)), 0)
        val m = decoder.decode(df21, 0) as IdentityReply
        assertEquals("1346", m.squawk)
    }

    @Test
    fun garbageIsRejected() {
        assertNull(decoder.decode("8D4840D6202CC371C32CE0570000".hexToBytes(), 0))
        assertNull(decoder.decode("FFFFFFFFFFFFFF".hexToBytes(), 0))
    }

    @Test
    fun allCallAccepted() {
        val m = decoder.decode(allCall(0xABCDEF), 0)
        assertNotNull(m)
        assertEquals(0xABCDEF, m!!.icao)
    }

    companion object {
        /** Builds a valid DF11 (CA=5) frame for [icao]. */
        fun allCall(icao: Int): ByteArray {
            val f = byteArrayOf(0x5D, (icao ushr 16).toByte(), (icao ushr 8).toByte(), icao.toByte(), 0, 0, 0)
            val crc = Crc24.compute(f, 4)
            f[4] = (crc ushr 16).toByte(); f[5] = (crc ushr 8).toByte(); f[6] = crc.toByte()
            return f
        }
    }
}
