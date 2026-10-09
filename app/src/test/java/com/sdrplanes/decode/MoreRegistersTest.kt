package com.sdrplanes.decode

import com.sdrplanes.decode.Frames.bits56
import com.sdrplanes.decode.Frames.df17
import com.sdrplanes.decode.Frames.df20
import com.sdrplanes.decode.Frames.f
import com.sdrplanes.decode.ModeSDecoderTest.Companion.allCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Comm-B 1,0 / 1,7 / 3,0 / 4,4 / 4,5, ADS-B TC 28 and the TC 29 version-1 layout. */
class MoreRegistersTest {
    private fun infer(mb: Long) = CommB.infer(df20(0x123456, mb))

    @Test
    fun bds10Capability() {
        val r = infer(bits56(f(1, 8, 0x10), f(15, 1, 1), f(16, 1, 1), f(17, 7, 5), f(38, 1, 1))).single() as Bds.Capability
        assertEquals(5, r.subnetVersion)
        assertTrue(r.acasOperational)
        assertTrue(r.acasRa)
        // Overlay capability with an old subnetwork version is inconsistent.
        assertTrue(infer(bits56(f(1, 8, 0x10), f(15, 1, 1), f(17, 7, 3))).none { it is Bds.Capability })
    }

    @Test
    fun bds17GicbCapabilityWinsOverMeteoHazard() {
        // 2,0 (index 6), 4,0 (8), 5,0 (15), 6,0 (23) supported.
        val mb = bits56(f(7, 1, 1), f(9, 1, 1), f(16, 1, 1), f(24, 1, 1))
        val r = infer(mb).single() as Bds.GicbCapability
        assertEquals(setOf("2,0", "4,0", "5,0", "6,0"), r.supported)
    }

    @Test
    fun bds30ResolutionAdvisory() {
        val mb = bits56(f(1, 8, 0x30), f(9, 1, 1), f(10, 1, 1), f(11, 1, 1), f(29, 2, 1), f(31, 24, 0x3C6586))
        val r = infer(mb).single() as Bds.AcasRa
        assertTrue(r.issued)
        assertEquals("DESCEND", r.advisory)
        assertEquals(0x3C6586, r.threatIcao)
        // Terminated RA.
        val clear = infer(bits56(f(1, 8, 0x30), f(27, 1, 1))).single() as Bds.AcasRa
        assertEquals("CLEAR OF CONFLICT", clear.advisory)
        // Threat type 3 is reserved.
        assertTrue(infer(bits56(f(1, 8, 0x30), f(29, 2, 3))).none { it is Bds.AcasRa })
    }

    @Test
    fun bds44RoutineMeteo() {
        val tempRaw = 1024 - 50 // -12.5 °C
        val mb = bits56(
            f(1, 4, 1), f(5, 1, 1), f(6, 9, 45), f(15, 9, 384), f(24, 1, 1), f(25, 10, tempRaw),
            f(47, 1, 1), f(48, 2, 1),
        )
        val r = infer(mb).filterIsInstance<Bds.MeteoRoutine>().single()
        assertEquals(45, r.windKt)
        assertEquals(270.0, r.windFromDeg!!, 0.01)
        assertEquals(-12.5, r.temperatureC, 1e-9)
        assertEquals(1, r.turbulence)
        assertNull(r.pressureHpa)
    }

    @Test
    fun bds45HazardReport() {
        val mb = bits56(f(1, 1, 1), f(2, 2, 2), f(10, 1, 1), f(11, 2, 1), f(16, 1, 1), f(17, 10, 1024 - 120), f(39, 1, 1), f(40, 12, 100))
        val r = infer(mb).filterIsInstance<Bds.MeteoHazard>().single()
        assertEquals(2, r.turbulence)
        assertEquals(1, r.icing)
        assertEquals(-30.0, r.temperatureC!!, 1e-9)
        assertEquals(1600, r.radioHeightFt)
        assertNull(r.windShear)
    }

    @Test
    fun emergencyStatusBroadcast() {
        val d = ModeSDecoder()
        val me = bits56(f(1, 5, 28), f(6, 3, 1), f(9, 3, 3), f(12, 13, Frames.squawkField("7700")))
        val m = d.decode(df17(0x3C6586, me), 0) as EmergencyStatus
        assertEquals("Minimum fuel", m.emergency)
        assertEquals("7700", m.squawk)
    }

    @Test
    fun raBroadcast() {
        val d = ModeSDecoder()
        val me = bits56(f(1, 5, 28), f(6, 3, 2), f(9, 1, 1), f(10, 1, 1), f(12, 1, 1), f(29, 2, 1), f(31, 24, 0xABCDEF))
        val m = d.decode(df17(0x3C4B26, me), 0) as RaBroadcast
        assertEquals("INCREASE CLIMB", m.ra.advisory)
        assertEquals(0xABCDEF, m.ra.threatIcao)
    }

    @Test
    fun targetStateVersion1() {
        val d = ModeSDecoder()
        // Vertical source MCP (1), target altitude 37 000 ft = (37000 + 1000) / 100, heading 270 from MCP.
        val me = bits56(f(1, 5, 29), f(6, 2, 0), f(8, 2, 1), f(16, 10, 380), f(26, 2, 1), f(28, 9, 270))
        val m = d.decode(df17(0x3C656F, me), 0) as TargetState
        assertEquals(37_000, m.selectedAltitudeFt)
        assertEquals(270.0, m.selectedHeadingDeg!!, 1e-9)
        assertFalse(m.altitudeFromFms)
        assertNull(m.autopilot)
    }

    @Test
    fun commBThroughDecoderNeedsKnownAddress() {
        val d = ModeSDecoder()
        val frame = df20(0x3C4B26, bits56(f(1, 8, 0x30), f(9, 1, 1), f(10, 1, 1)))
        assertNull(d.decode(frame.copyOf(), 0))
        d.decode(allCall(0x3C4B26), 0)
        val m = d.decode(frame, 1) as AltitudeReply
        assertTrue(m.commB.single() is Bds.AcasRa)
    }
}
