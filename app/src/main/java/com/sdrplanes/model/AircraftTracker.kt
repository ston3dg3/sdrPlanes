package com.sdrplanes.model

import com.sdrplanes.decode.AirbornePosition
import com.sdrplanes.decode.AllCallReply
import com.sdrplanes.decode.AltitudeReply
import com.sdrplanes.decode.Cpr
import com.sdrplanes.decode.IdentityReply
import com.sdrplanes.decode.Identification
import com.sdrplanes.decode.LatLon
import com.sdrplanes.decode.ModeSMessage
import com.sdrplanes.decode.OtherAdsb
import com.sdrplanes.decode.Velocity
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Merges decoded messages into per-aircraft state. Not thread-safe; owned by the
 * decoding coroutine, which publishes [snapshot]s to the UI.
 */
class AircraftTracker(
    private val expiryMs: Long = 60_000,
    private val maxRangeKm: Double = 500.0,
) {
    /** Receiver location (phone GPS). Used only to reject implausible positions. */
    var receiverPosition: LatLon? = null

    private class CprFrame(val lat: Int, val lon: Int, val timeMs: Long)

    private class State(val icao: Int) {
        var callsign: String? = null
        var squawk: String? = null
        var altitudeFt: Int? = null
        var speedKt: Double? = null
        var trackDeg: Double? = null
        var verticalRateFpm: Int? = null
        var position: LatLon? = null
        var positionTimeMs = 0L
        var lastSeenMs = 0L
        var messages = 0
        var signal = 0f
        var even: CprFrame? = null
        var odd: CprFrame? = null
        val trail = ArrayDeque<LatLon>()
    }

    private val states = HashMap<Int, State>()

    fun update(msg: ModeSMessage, signal: Float, nowMs: Long) {
        val s = states.getOrPut(msg.icao) { State(msg.icao) }
        s.lastSeenMs = nowMs
        s.messages++
        s.signal = if (s.signal == 0f) signal else s.signal * 0.8f + signal * 0.2f

        when (msg) {
            is Identification -> if (msg.callsign.isNotEmpty()) s.callsign = msg.callsign
            is AltitudeReply -> msg.altitudeFt?.let { s.altitudeFt = it }
            is IdentityReply -> s.squawk = msg.squawk
            is Velocity -> {
                msg.speedKt?.let { s.speedKt = it }
                msg.trackDeg?.let { s.trackDeg = it }
                msg.verticalRateFpm?.let { s.verticalRateFpm = it }
            }
            is AirbornePosition -> {
                msg.altitudeFt?.let { s.altitudeFt = it }
                updatePosition(s, msg, nowMs)
            }
            is AllCallReply, is OtherAdsb -> Unit
        }
    }

    private fun updatePosition(s: State, msg: AirbornePosition, nowMs: Long) {
        val cpr = CprFrame(msg.cprLat, msg.cprLon, nowMs)
        if (msg.odd) s.odd = cpr else s.even = cpr

        val last = s.position
        val candidate: LatLon? = if (last != null && nowMs - s.positionTimeMs < POSITION_LOCAL_MS) {
            // Relative to the aircraft's own last fix: one frame is enough.
            Cpr.local(msg.cprLat, msg.cprLon, msg.odd, last)
                .takeIf { distanceKm(it, last) <= maxJumpKm(nowMs - s.positionTimeMs) }
        } else {
            val e = s.even
            val o = s.odd
            if (e != null && o != null && kotlin.math.abs(e.timeMs - o.timeMs) <= PAIR_WINDOW_MS) {
                Cpr.global(e.lat, e.lon, o.lat, o.lon, evenNewer = !msg.odd)
            } else null
        }

        if (candidate == null) {
            // Lost track (e.g. bad local decode): fall back to a fresh global pair next time.
            if (last != null && nowMs - s.positionTimeMs >= POSITION_LOCAL_MS) s.position = null
            return
        }
        val rx = receiverPosition
        if (rx != null && distanceKm(candidate, rx) > maxRangeKm) return

        s.position = candidate
        s.positionTimeMs = nowMs
        val prev = s.trail.lastOrNull()
        if (prev == null || distanceKm(prev, candidate) > 0.1) {
            s.trail.addLast(candidate)
            if (s.trail.size > MAX_TRAIL) s.trail.removeFirst()
        }
    }

    fun expire(nowMs: Long) {
        states.values.removeAll { nowMs - it.lastSeenMs > expiryMs }
    }

    fun snapshot(): List<Aircraft> = states.values.map { s ->
        Aircraft(
            icao = s.icao,
            callsign = s.callsign,
            squawk = s.squawk,
            altitudeFt = s.altitudeFt,
            speedKt = s.speedKt,
            trackDeg = s.trackDeg,
            verticalRateFpm = s.verticalRateFpm,
            position = s.position,
            positionTimeMs = s.positionTimeMs,
            lastSeenMs = s.lastSeenMs,
            messages = s.messages,
            signal = s.signal,
            trail = s.trail.toList(),
        )
    }

    companion object {
        private const val PAIR_WINDOW_MS = 10_000L
        private const val POSITION_LOCAL_MS = 60_000L
        private const val MAX_TRAIL = 300

        /** Generous bound (~Mach 2 plus slack) for how far a fix may move. */
        private fun maxJumpKm(dtMs: Long) = 5.0 + dtMs / 1000.0 * 0.7

        fun distanceKm(a: LatLon, b: LatLon): Double {
            val r = 6371.0
            val dLat = Math.toRadians(b.lat - a.lat)
            val dLon = Math.toRadians(b.lon - a.lon)
            val h = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2) * sin(dLon / 2)
            return 2 * r * asin(sqrt(h))
        }
    }
}
