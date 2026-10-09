package com.sdrplanes.model

import com.sdrplanes.db.AircraftInfo
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
    val onGround: Boolean = false,
    /** Emitter category, e.g. "Heavy" or "Rotorcraft". */
    val category: String? = null,
    val adsbVersion: Int? = null,
    val autopilot: Autopilot = Autopilot(),
    val air: AirData = AirData(),
    /** Offline database entry (registration, type, country); null if unknown. */
    val info: AircraftInfo? = null,
    /** Active TCAS resolution advisory, e.g. "CLIMB" (Comm-B 3,0 / ADS-B TC 28). */
    val tcasRa: String? = null,
    /** Declared emergency (ADS-B TC 28), e.g. "Minimum fuel". */
    val emergency: String? = null,
    val weather: Weather = Weather(),
) {
    val icaoHex: String get() = "%06X".format(icao)
    val label: String get() = callsign?.takeIf { it.isNotBlank() } ?: info?.registration ?: icaoHex
}

/** Autopilot targets (ADS-B target state, Comm-B 4,0). Null = not reported. */
data class Autopilot(
    val selectedAltitudeFt: Int? = null,
    val selectedHeadingDeg: Double? = null,
    val baroHpa: Double? = null,
    /** Engaged modes, e.g. "AP", "VNAV", "LNAV", "ALT", "APP". */
    val modes: List<String> = emptyList(),
) {
    val isEmpty get() = selectedAltitudeFt == null && selectedHeadingDeg == null && baroHpa == null && modes.isEmpty()
}

/** Weather measured by the aircraft (Comm-B 4,4 / 4,5). Null = not reported. */
data class Weather(
    val windFromDeg: Double? = null,
    val windKt: Int? = null,
    val temperatureC: Double? = null,
    val humidityPct: Double? = null,
    /** Hazards with level, e.g. "TURB MOD", "ICING LIGHT". */
    val hazards: List<String> = emptyList(),
) {
    val isEmpty get() = windKt == null && temperatureC == null && humidityPct == null && hazards.isEmpty()
}

/** Air data from Comm-B 5,0 / 6,0 (interrogated by radar). Null = not reported. */
data class AirData(
    val indicatedAirspeedKt: Int? = null,
    val trueAirspeedKt: Int? = null,
    val mach: Double? = null,
    val magHeadingDeg: Double? = null,
    val rollDeg: Double? = null,
) {
    val isEmpty get() = indicatedAirspeedKt == null && trueAirspeedKt == null && mach == null &&
        magHeadingDeg == null && rollDeg == null
}
