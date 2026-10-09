package com.sdrplanes.model

import com.sdrplanes.decode.LatLon

enum class SortBy(val label: String) {
    DISTANCE("Distance"),
    ALTITUDE("Altitude"),
    CALLSIGN("Callsign"),
    SIGNAL("Signal"),
    LAST_SEEN("Last seen"),
}

/**
 * Contacts filter, applied to both the list and the map. Altitude bounds are inclusive;
 * [maxAltitudeFt] at [ALTITUDE_CAP] means "no upper limit".
 */
data class AircraftFilter(
    val query: String = "",
    val minAltitudeFt: Int = 0,
    val maxAltitudeFt: Int = ALTITUDE_CAP,
    val hideGround: Boolean = false,
    val positionOnly: Boolean = false,
    val militaryOnly: Boolean = false,
    val sort: SortBy = SortBy.DISTANCE,
) {
    private val altitudeLimited get() = minAltitudeFt > 0 || maxAltitudeFt < ALTITUDE_CAP

    /** True when anything is hidden (sorting alone doesn't count). */
    val isActive: Boolean
        get() = query.isNotBlank() || altitudeLimited || hideGround || positionOnly || militaryOnly

    fun matches(a: Aircraft): Boolean {
        if (hideGround && a.onGround) return false
        if (positionOnly && a.position == null) return false
        if (militaryOnly && a.info?.military != true) return false
        if (altitudeLimited) {
            // Aircraft on the ground count as 0 ft; unknown altitudes can't satisfy a range.
            val alt = if (a.onGround) 0 else a.altitudeFt ?: return false
            if (alt < minAltitudeFt) return false
            if (maxAltitudeFt < ALTITUDE_CAP && alt > maxAltitudeFt) return false
        }
        val q = query.trim()
        if (q.isNotEmpty()) {
            val fields = listOfNotNull(
                a.callsign, a.icaoHex, a.squawk, a.info?.registration, a.info?.typeCode,
                a.info?.description, a.info?.country, a.category,
            )
            if (fields.none { it.contains(q, ignoreCase = true) }) return false
        }
        return true
    }

    fun apply(aircraft: List<Aircraft>, receiver: LatLon?): List<Aircraft> {
        val shown = aircraft.filter(::matches)
        val byName = compareBy<Aircraft> { it.label.uppercase() }
        return when (sort) {
            SortBy.DISTANCE -> shown.sortedWith(
                compareBy<Aircraft>({ it.position == null }, { a ->
                    val p = a.position
                    if (p != null && receiver != null) AircraftTracker.distanceKm(p, receiver) else Double.MAX_VALUE
                }).then(byName)
            )
            SortBy.ALTITUDE -> shown.sortedWith(
                compareBy<Aircraft>({ it.altitudeFt == null && !it.onGround }, { -(if (it.onGround) 0 else it.altitudeFt ?: 0) })
                    .then(byName)
            )
            SortBy.CALLSIGN -> shown.sortedWith(byName)
            SortBy.SIGNAL -> shown.sortedWith(compareBy<Aircraft> { -it.signal }.then(byName))
            SortBy.LAST_SEEN -> shown.sortedWith(compareBy<Aircraft> { -it.lastSeenMs }.then(byName))
        }
    }

    companion object {
        const val ALTITUDE_CAP = 50_000
    }
}
