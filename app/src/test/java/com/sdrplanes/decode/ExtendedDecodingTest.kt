package com.sdrplanes.decode

import com.sdrplanes.decode.ModeSDecoderTest.Companion.allCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Surface position, target state, operational status, Comm-B and 2-bit repair. Vectors from pyModeS. */
class ExtendedDecodingTest {
    private val decoder = ModeSDecoder()

    @Test
    fun surfacePositionLocalDecode() {
        // pyModeS: surface_position_with_ref("8FC8200A3AB8F5F893096B000000", -43.5, 172.5) == (-43.48564, 172.53942)
        val f = "8FC8200A3AB8F5F893096B000000".hexToBytes()
        val lat = f.bits(54, 17).toInt()
        val lon = f.bits(71, 17).toInt()
        val p = Cpr.surfaceLocal(lat, lon, f.bit(53), LatLon(-43.5, 172.5))
        assertEquals(-43.48564, p.lat, 1e-4)
        assertEquals(172.53942, p.lon, 1e-4)
    }

    @Test
    fun surfaceMovement() {
        assertNull(ModeSDecoder.decodeMovement(0))
        assertEquals(0.0, ModeSDecoder.decodeMovement(1)!!, 0.0)
        assertEquals(1.0, ModeSDecoder.decodeMovement(9)!!, 1e-9)
        assertEquals(15.0, ModeSDecoder.decodeMovement(39)!!, 1e-9)
        assertEquals(100.0, ModeSDecoder.decodeMovement(109)!!, 1e-9)
        assertEquals(175.0, ModeSDecoder.decodeMovement(124)!!, 1e-9)
    }

    @Test
    fun surfacePositionMessage() {
        val m = decoder.decode(withCrc("8FC8200A3AB8F5F893096B000000"), 0) as SurfacePosition
        assertEquals(0xC8200A, m.icao)
        assertNotNull(m.groundSpeedKt)
    }

    @Test
    fun targetStateAndStatus() {
        val m = decoder.decode("8DA05629EA21485CBF3F8CADAEEB".hexToBytes(), 0) as TargetState
        assertEquals(16992, m.selectedAltitudeFt)
        assertFalse(m.altitudeFromFms)
        assertEquals(1012.8, m.baroHpa!!, 0.05)
        assertEquals(66.8, m.selectedHeadingDeg!!, 0.1)
        assertEquals(true, m.autopilot)
        assertEquals(true, m.vnav)
        assertEquals(false, m.altitudeHold)
        assertEquals(false, m.approach)
        assertEquals(true, m.lnav)
        assertEquals(true, m.tcas)
    }

    @Test
    fun identificationCategory() {
        val m = decoder.decode("8D4840D6202CC371C32CE0576098".hexToBytes(), 0) as Identification
        assertEquals(4, m.typeCode)
        assertEquals(0, m.category)
        assertNull(m.categoryName)
        assertEquals("Heavy", ModeSDecoder.categoryName(4, 5))
        assertEquals("Rotorcraft", ModeSDecoder.categoryName(4, 7))
    }

    @Test
    fun commBIdentification() {
        val r = CommB.infer("A000083E202CC371C31DE0AA1CCF".hexToBytes())
        assertTrue(r.toString(), Bds.Identification("KLM1017") in r)
    }

    @Test
    fun commBVerticalIntention() {
        val r = CommB.infer("A000029C85E42F313000007047D3".hexToBytes()).filterIsInstance<Bds.VerticalIntention>().single()
        assertEquals(3008, r.mcpAltitudeFt)
        assertEquals(3008, r.fmsAltitudeFt)
        assertEquals(1020.0, r.baroHpa!!, 0.05)
    }

    @Test
    fun commBTrackAndTurn() {
        val r = CommB.infer("A000139381951536E024D4CCF6B5".hexToBytes()).filterIsInstance<Bds.TrackAndTurn>().single()
        assertEquals(2.1, r.rollDeg!!, 0.05)
        assertEquals(114.258, r.trackDeg!!, 0.01)
        assertEquals(438, r.groundSpeedKt)
        assertEquals(0.125, r.trackRateDegS!!, 1e-6)
        assertEquals(424, r.trueAirspeedKt)
    }

    @Test
    fun commBHeadingAndSpeed() {
        val r = CommB.infer("A00004128F39F91A7E27C46ADC21".hexToBytes()).filterIsInstance<Bds.HeadingAndSpeed>().single()
        assertEquals(42.715, r.magHeadingDeg!!, 0.01)
        assertEquals(252, r.indicatedAirspeedKt)
        assertEquals(0.42, r.mach!!, 0.001)
        assertEquals(-1920, r.baroRateFpm)
        assertEquals(-1920, r.inertialRateFpm)
    }

    @Test
    fun commBReachesTrackerThroughDf20() {
        val df20 = "A000139381951536E024D4CCF6B5".hexToBytes()
        decoder.decode(allCall(Crc24.residual(df20, 112)), 0)
        val m = decoder.decode(df20, 1) as AltitudeReply
        assertTrue(m.commB.any { it is Bds.TrackAndTurn })
    }

    @Test
    fun machFromCasIsa() {
        // 250 kt CAS at FL100 is about Mach 0.46.
        assertEquals(0.46, CommB.machFromCas(250.0, 10_000.0), 0.01)
    }

    @Test
    fun twoBitErrorsAreRepairedForKnownAircraft() {
        decoder.decode("8D4840D6202CC371C32CE0576098".hexToBytes(), 0)
        val damaged = "8D4840D6202CC371C32CE0576098".hexToBytes()
        damaged[6] = (damaged[6].toInt() xor 0x10).toByte()
        damaged[9] = (damaged[9].toInt() xor 0x01).toByte()
        val m = decoder.decode(damaged, 1) as Identification
        assertEquals("KLM1023", m.callsign)
    }

    @Test
    fun twoBitRepairDisabledWhenLimitedToOne() {
        val d = ModeSDecoder(maxCorrectedBits = 1)
        d.decode("8D4840D6202CC371C32CE0576098".hexToBytes(), 0)
        val damaged = "8D4840D6202CC371C32CE0576098".hexToBytes()
        damaged[6] = (damaged[6].toInt() xor 0x10).toByte()
        damaged[9] = (damaged[9].toInt() xor 0x01).toByte()
        assertNull(d.decode(damaged, 1))
    }

    @Test
    fun allDoubleBitSyndromesRepairExactly() {
        val good = "8D4840D6202CC371C32CE0576098".hexToBytes()
        for (a in 5 until 112) for (b in a + 1 until 112) {
            val f = good.copyOf()
            f[a / 8] = (f[a / 8].toInt() xor (0x80 ushr (a % 8))).toByte()
            f[b / 8] = (f[b / 8].toInt() xor (0x80 ushr (b % 8))).toByte()
            assertEquals(2, Crc24.fixBits112(f, Crc24.residual(f, 112)))
            assertTrue(good.contentEquals(f))
        }
    }

    /** Replaces the parity field with a valid CRC (for vectors published without one). */
    private fun withCrc(hex: String): ByteArray {
        val f = hex.hexToBytes()
        val crc = Crc24.compute(f, 11)
        f[11] = (crc ushr 16).toByte(); f[12] = (crc ushr 8).toByte(); f[13] = crc.toByte()
        return f
    }
}
