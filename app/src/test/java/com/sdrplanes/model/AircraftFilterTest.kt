package com.sdrplanes.model

import com.sdrplanes.db.AircraftInfo
import com.sdrplanes.decode.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AircraftFilterTest {
    private val rx = LatLon(48.14, 11.58)

    private fun ac(
        icao: Int, callsign: String? = null, alt: Int? = null, pos: LatLon? = null, ground: Boolean = false,
        info: AircraftInfo? = null, signal: Float = 0.1f, seen: Long = 0,
    ) = Aircraft(
        icao, callsign, null, alt, null, null, null, pos, 0, seen, 1, signal, emptyList(),
        onGround = ground, info = info,
    )

    private val kiko = ac(0x3C656F, "DLH4AB", 35000, LatLon(48.35, 11.0), info = AircraftInfo("D-AIKO", "A333", country = "Germany"))
    private val heli = ac(0x3F402A, "GAF123", 2500, LatLon(48.2, 11.3), info = AircraftInfo("79+02", "NH90", military = true))
    private val taxi = ac(0x3C66A2, null, null, LatLon(48.35, 11.78), ground = true, info = AircraftInfo("D-AIUB", "A320"))
    private val noPos = ac(0x4CA123, "RYR1", 20000)
    private val all = listOf(kiko, heli, taxi, noPos)

    @Test
    fun defaultShowsEverythingSortedByDistance() {
        val f = AircraftFilter()
        assertFalse(f.isActive)
        val shown = f.apply(all, rx)
        assertEquals(listOf(heli, taxi, kiko, noPos), shown) // positioned by distance, no position last
    }

    @Test
    fun searchMatchesCallsignRegistrationTypeAndIcao() {
        fun q(s: String) = AircraftFilter(query = s).apply(all, rx).map { it.icao }
        assertEquals(listOf(0x3C656F), q("dlh4"))
        assertEquals(listOf(0x3C66A2), q("d-aiub"))
        assertEquals(listOf(0x3F402A), q("NH90"))
        assertEquals(listOf(0x4CA123), q("4ca1"))
        assertTrue(AircraftFilter(query = "x").isActive)
    }

    @Test
    fun toggles() {
        assertEquals(3, AircraftFilter(hideGround = true).apply(all, rx).size)
        assertEquals(3, AircraftFilter(positionOnly = true).apply(all, rx).size)
        assertEquals(listOf(heli), AircraftFilter(militaryOnly = true).apply(all, rx))
    }

    @Test
    fun altitudeRange() {
        val low = AircraftFilter(maxAltitudeFt = 10_000).apply(all, rx)
        assertEquals(setOf(heli, taxi), low.toSet()) // ground counts as 0 ft
        val high = AircraftFilter(minAltitudeFt = 30_000).apply(all, rx)
        assertEquals(listOf(kiko), high)
    }

    @Test
    fun sortOrders() {
        assertEquals(listOf(kiko, noPos, heli, taxi), AircraftFilter(sort = SortBy.ALTITUDE).apply(all, rx))
        assertEquals(listOf("79+02".let { heli }, kiko, taxi, noPos).map { it.label }.sorted(),
            AircraftFilter(sort = SortBy.CALLSIGN).apply(all, rx).map { it.label })
    }
}
