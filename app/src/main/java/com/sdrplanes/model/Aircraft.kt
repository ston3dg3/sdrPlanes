package com.sdrplanes.model

import com.sdrplanes.decode.LatLon

/** Immutable snapshot of one tracked aircraft, safe to hand to the UI. */
data class Aircraft(
    val icao: Int,
    val callsign: String?,
    val squawk: String?,
    val altitudeFt: Int?,
    val speedKt: Double?,
    val trackDeg: Double?,
    val verticalRateFpm: Int?,
    val position: LatLon?,
    val positionTimeMs: Long,
    val lastSeenMs: Long,
    val messages: Int,
    val signal: Float,
    val trail: List<LatLon>,
) {
    val icaoHex: String get() = "%06X".format(icao)
    val label: String get() = callsign?.takeIf { it.isNotBlank() } ?: icaoHex
}
