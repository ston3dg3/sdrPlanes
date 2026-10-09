package com.sdrplanes.model

import com.sdrplanes.db.AircraftInfo
import com.sdrplanes.decode.AirbornePosition
import com.sdrplanes.decode.AllCallReply
import com.sdrplanes.decode.AltitudeReply
import com.sdrplanes.decode.Bds
import com.sdrplanes.decode.CommB
import com.sdrplanes.decode.Cpr
import com.sdrplanes.decode.IdentityReply
import com.sdrplanes.decode.Identification
import com.sdrplanes.decode.LatLon
import com.sdrplanes.decode.ModeSMessage
import com.sdrplanes.decode.EmergencyStatus
import com.sdrplanes.decode.OperationalStatus
import com.sdrplanes.decode.RaBroadcast
import com.sdrplanes.decode.OtherAdsb
import com.sdrplanes.decode.SurfacePosition
import com.sdrplanes.decode.TargetState
import com.sdrplanes.decode.Velocity
import kotlin.math.abs
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
    /** Offline database lookup, done once per newly seen aircraft. */
    private val lookup: (Int) -> AircraftInfo? = { null },
) {
    /** Receiver location (phone GPS). Rejects implausible positions; reference for surface CPR. */
    var receiverPosition: LatLon? = null

    private class CprFrame(val lat: Int, val lon: Int, val timeMs: Long)

    private class State(val icao: Int, val info: AircraftInfo?) {
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
        var onGround = false
        var category: String? = null
        var adsbVersion: Int? = null
        var autopilot = Autopilot()
        var air = AirData()
        var weather = Weather()
        /** Comm-B registers the transponder reports it supports (BDS 1,7); null = unknown. */
        var gicb: Set<String>? = null
        var tcasRa: String? = null
        var tcasRaTimeMs = 0L
        var emergency: String? = null
    }

    private val states = HashMap<Int, State>()

    fun update(msg: ModeSMessage, signal: Float, nowMs: Long) {
        val s = states.getOrPut(msg.icao) { State(msg.icao, lookup(msg.icao)) }
        s.lastSeenMs = nowMs
        s.messages++
        s.signal = if (s.signal == 0f) signal else s.signal * 0.8f + signal * 0.2f

        when (msg) {
            is Identification -> {
                if (msg.callsign.isNotEmpty()) s.callsign = msg.callsign
                msg.categoryName?.let { s.category = it }
            }
            is AltitudeReply -> {
                msg.altitudeFt?.let { s.altitudeFt = it }
                msg.onGround?.let { s.onGround = it }
                applyCommB(s, msg.commB, nowMs)
            }
            is IdentityReply -> {
                s.squawk = msg.squawk
                msg.onGround?.let { s.onGround = it }
                applyCommB(s, msg.commB, nowMs)
            }
            is Velocity -> {
                msg.speedKt?.let { s.speedKt = it }
                msg.trackDeg?.let { s.trackDeg = it }
                msg.verticalRateFpm?.let { s.verticalRateFpm = it }
            }
            is AirbornePosition -> {
                s.onGround = false
                msg.altitudeFt?.let { s.altitudeFt = it }
                updatePosition(s, msg, nowMs)
            }
            is SurfacePosition -> {
                s.onGround = true
                msg.groundSpeedKt?.let { s.speedKt = it }
                msg.trackDeg?.let { s.trackDeg = it }
                s.verticalRateFpm = null
                updateSurfacePosition(s, msg, nowMs)
            }
            is TargetState -> s.autopilot = Autopilot(
                selectedAltitudeFt = msg.selectedAltitudeFt,
                selectedHeadingDeg = msg.selectedHeadingDeg,
                baroHpa = msg.baroHpa,
                modes = buildList {
                    if (msg.autopilot == true) add("AP")
                    if (msg.vnav == true) add("VNAV")
                    if (msg.lnav == true) add("LNAV")
                    if (msg.altitudeHold == true) add("ALT")
                    if (msg.approach == true) add("APP")
                },
            )
            is OperationalStatus -> s.adsbVersion = msg.version
            is EmergencyStatus -> {
                s.emergency = msg.emergency
                s.squawk = msg.squawk
            }
            is RaBroadcast -> applyRa(s, msg.ra, nowMs)
            is AllCallReply -> msg.onGround?.let { s.onGround = it }
            is OtherAdsb -> Unit
        }
    }

    /** Applies Comm-B registers, choosing between ambiguous ones with the ADS-B state. */
    private fun applyRa(s: State, ra: Bds.AcasRa, nowMs: Long) {
        s.tcasRa = ra.advisory
        s.tcasRaTimeMs = nowMs
    }

    private fun applyCommB(s: State, regs: List<Bds>, nowMs: Long) {
        if (regs.isEmpty()) return
        val chosen = if (regs.size == 1) {
            regs.single().takeIf { consistent(s, it) != false }
        } else {
            regs.singleOrNull { consistent(s, it) == true }
        } ?: return
        when (chosen) {
            is Bds.Capability -> Unit
            is Bds.GicbCapability -> s.gicb = chosen.supported
            is Bds.AcasRa -> applyRa(s, chosen, nowMs)
            is Bds.MeteoRoutine -> s.weather = s.weather.copy(
                windFromDeg = chosen.windFromDeg ?: s.weather.windFromDeg,
                windKt = chosen.windKt ?: s.weather.windKt,
                temperatureC = chosen.temperatureC,
                humidityPct = chosen.humidityPct ?: s.weather.humidityPct,
                hazards = chosen.turbulence?.takeIf { it > 0 }?.let { listOf("TURB ${LEVELS[it]}") } ?: s.weather.hazards,
            )
            is Bds.MeteoHazard -> s.weather = s.weather.copy(
                temperatureC = chosen.temperatureC ?: s.weather.temperatureC,
                hazards = listOfNotNull(
                    chosen.turbulence?.takeIf { it > 0 }?.let { "TURB ${LEVELS[it]}" },
                    chosen.windShear?.takeIf { it > 0 }?.let { "WIND SHEAR ${LEVELS[it]}" },
                    chosen.microburst?.takeIf { it > 0 }?.let { "MICROBURST ${LEVELS[it]}" },
                    chosen.icing?.takeIf { it > 0 }?.let { "ICING ${LEVELS[it]}" },
                    chosen.wakeVortex?.takeIf { it > 0 }?.let { "WAKE ${LEVELS[it]}" },
                ),
            )
            is Bds.Identification -> if (s.callsign == null) s.callsign = chosen.callsign
            is Bds.VerticalIntention -> s.autopilot = s.autopilot.copy(
                selectedAltitudeFt = chosen.mcpAltitudeFt ?: chosen.fmsAltitudeFt ?: s.autopilot.selectedAltitudeFt,
                baroHpa = chosen.baroHpa ?: s.autopilot.baroHpa,
            )
            is Bds.TrackAndTurn -> s.air = s.air.copy(
                rollDeg = chosen.rollDeg ?: s.air.rollDeg,
                trueAirspeedKt = chosen.trueAirspeedKt ?: s.air.trueAirspeedKt,
            )
            is Bds.HeadingAndSpeed -> s.air = s.air.copy(
                indicatedAirspeedKt = chosen.indicatedAirspeedKt ?: s.air.indicatedAirspeedKt,
                mach = chosen.mach ?: s.air.mach,
                magHeadingDeg = chosen.magHeadingDeg ?: s.air.magHeadingDeg,
            )
        }
    }

    /**
     * True/false when the register agrees/disagrees with known data; null = can't tell. A
     * register the transponder declared unsupported (BDS 1,7) is never accepted.
     */
    private fun consistent(s: State, r: Bds): Boolean? {
        val caps = s.gicb
        if (caps != null && r.code in GICB_CHECKED && r.code !in caps) return false
        return consistentWithState(s, r)
    }

    private fun consistentWithState(s: State, r: Bds): Boolean? = when (r) {
        is Bds.Capability, is Bds.GicbCapability, is Bds.AcasRa -> true
        is Bds.MeteoRoutine, is Bds.MeteoHazard -> null
        is Bds.Identification -> s.callsign?.let { it == r.callsign } ?: true
        is Bds.VerticalIntention -> true
        is Bds.TrackAndTurn -> {
            val gs = s.speedKt
            val trk = s.trackDeg
            when {
                gs == null && trk == null -> null
                r.groundSpeedKt != null && gs != null && abs(r.groundSpeedKt - gs) > 30 -> false
                r.trackDeg != null && trk != null && angleDiff(r.trackDeg, trk) > 15 -> false
                else -> true
            }
        }
        is Bds.HeadingAndSpeed -> {
            val alt = s.altitudeFt
            val ias = r.indicatedAirspeedKt
            val mach = r.mach
            val trk = s.trackDeg
            when {
                // Heading and track differ only by the wind correction angle.
                r.magHeadingDeg != null && trk != null && angleDiff(r.magHeadingDeg, trk) > 45 -> false
                alt != null && ias != null && mach != null ->
                    abs(CommB.machFromCas(ias.toDouble(), alt.toDouble()) - mach) < 0.1
                trk != null && r.magHeadingDeg != null -> true
                else -> null
            }
        }
    }

    private fun angleDiff(a: Double, b: Double): Double {
        val d = abs(a - b) % 360
        return if (d > 180) 360 - d else d
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
            if (e != null && o != null && abs(e.timeMs - o.timeMs) <= PAIR_WINDOW_MS) {
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
        accept(s, candidate, nowMs)
    }

    private fun updateSurfacePosition(s: State, msg: SurfacePosition, nowMs: Long) {
        // Reference within ~45 NM: the aircraft's recent fix, else the receiver.
        val last = s.position?.takeIf { nowMs - s.positionTimeMs < POSITION_LOCAL_MS }
        val ref = last ?: receiverPosition ?: return
        val p = Cpr.surfaceLocal(msg.cprLat, msg.cprLon, msg.odd, ref)
        if (distanceKm(p, ref) > SURFACE_REF_KM) return
        if (last != null && distanceKm(p, last) > maxJumpKm(nowMs - s.positionTimeMs)) return
        accept(s, p, nowMs)
    }

    private fun accept(s: State, p: LatLon, nowMs: Long) {
        s.position = p
        s.positionTimeMs = nowMs
        val prev = s.trail.lastOrNull()
        if (prev == null || distanceKm(prev, p) > 0.1) {
            s.trail.addLast(p)
            if (s.trail.size > MAX_TRAIL) s.trail.removeFirst()
        }
    }

    fun expire(nowMs: Long) {
        states.values.removeAll { nowMs - it.lastSeenMs > expiryMs }
        for (s in states.values) if (s.tcasRa != null && nowMs - s.tcasRaTimeMs > RA_HOLD_MS) s.tcasRa = null
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
            onGround = s.onGround,
            category = s.category,
            adsbVersion = s.adsbVersion,
            autopilot = s.autopilot,
            air = s.air,
            info = s.info,
            tcasRa = s.tcasRa,
            emergency = s.emergency,
            weather = s.weather,
        )
    }

    companion object {
        private const val PAIR_WINDOW_MS = 10_000L
        private const val POSITION_LOCAL_MS = 60_000L
        private const val MAX_TRAIL = 300
        /** A resolution advisory stays on screen this long after its last report. */
        private const val RA_HOLD_MS = 30_000L
        private val LEVELS = listOf("NIL", "LIGHT", "MOD", "SEVERE")
        /** Registers whose support BDS 1,7 can confirm. */
        private val GICB_CHECKED = setOf("2,0", "4,0", "4,4", "4,5", "5,0", "6,0")
        /** Surface CPR is unambiguous within half a 1.5° zone. */
        private const val SURFACE_REF_KM = 80.0

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
