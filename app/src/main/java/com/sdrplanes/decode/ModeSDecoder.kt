package com.sdrplanes.decode

import kotlin.math.atan2
import kotlin.math.hypot

sealed interface ModeSMessage {
    val icao: Int
    val df: Int
}

/** DF11 – all-call reply, carries only the address. [onGround] from the CA field. */
data class AllCallReply(override val icao: Int, override val df: Int, val onGround: Boolean? = null) : ModeSMessage

/**
 * DF0/4/16/20 – altitude replies. [onGround] from the flight status (DF4/20) or vertical
 * status (DF0/16) field; [commB] holds the plausible registers of a DF20's MB field.
 */
data class AltitudeReply(
    override val icao: Int, override val df: Int, val altitudeFt: Int?,
    val onGround: Boolean? = null, val commB: List<Bds> = emptyList(),
) : ModeSMessage

/** DF5/21 – identity (squawk) replies; [commB] as for [AltitudeReply]. */
data class IdentityReply(
    override val icao: Int, override val df: Int, val squawk: String,
    val onGround: Boolean? = null, val commB: List<Bds> = emptyList(),
) : ModeSMessage

/** ADS-B TC 1–4. [category] is the emitter category within the type code's set. */
data class Identification(
    override val icao: Int, override val df: Int,
    val callsign: String, val category: Int, val typeCode: Int = 4,
) : ModeSMessage {
    /** Human-readable emitter category, or null when not reported. */
    val categoryName: String? get() = ModeSDecoder.categoryName(typeCode, category)
}

/** ADS-B TC 5–8: surface position with ground movement. */
data class SurfacePosition(
    override val icao: Int, override val df: Int,
    val groundSpeedKt: Double?, val trackDeg: Double?,
    val odd: Boolean, val cprLat: Int, val cprLon: Int,
) : ModeSMessage

/** ADS-B TC 29 subtype 1 (version 2): autopilot targets. Mode flags null when not reported. */
data class TargetState(
    override val icao: Int, override val df: Int,
    val selectedAltitudeFt: Int?, val altitudeFromFms: Boolean,
    val baroHpa: Double?, val selectedHeadingDeg: Double?,
    val autopilot: Boolean?, val vnav: Boolean?, val altitudeHold: Boolean?,
    val approach: Boolean?, val lnav: Boolean?, val tcas: Boolean?,
) : ModeSMessage

/** ADS-B TC 28 subtype 1: emergency/priority status with the Mode A code. */
data class EmergencyStatus(
    override val icao: Int, override val df: Int,
    /** Null = no emergency. */
    val emergency: String?, val squawk: String,
) : ModeSMessage

/** ADS-B TC 28 subtype 2: TCAS resolution advisory broadcast (same content as Comm-B 3,0). */
data class RaBroadcast(override val icao: Int, override val df: Int, val ra: Bds.AcasRa) : ModeSMessage

/** ADS-B TC 31: operational status; [version] is the ADS-B version (0–2). */
data class OperationalStatus(
    override val icao: Int, override val df: Int,
    val version: Int, val surface: Boolean,
) : ModeSMessage

/** ADS-B TC 9–18 (barometric) and 20–22 (GNSS height). */
data class AirbornePosition(
    override val icao: Int, override val df: Int,
    val altitudeFt: Int?, val gnssAltitude: Boolean,
    val odd: Boolean, val cprLat: Int, val cprLon: Int,
) : ModeSMessage

/** ADS-B TC 19. [trackDeg] is ground track (subtypes 1/2) or heading (subtypes 3/4). */
data class Velocity(
    override val icao: Int, override val df: Int,
    val speedKt: Double?, val trackDeg: Double?,
    val isAirspeed: Boolean, val verticalRateFpm: Int?,
) : ModeSMessage

/** Any other ADS-B type code, kept for statistics. */
data class OtherAdsb(override val icao: Int, override val df: Int, val typeCode: Int) : ModeSMessage

/**
 * Validates (CRC) and decodes raw Mode S frames.
 *
 * Address/parity replies (DF0/4/5/16/20/21) have no standalone integrity check, so they
 * are only accepted when the recovered address was recently seen in a CRC-protected
 * frame (DF11/17/18). Not thread-safe; use from a single decoding coroutine.
 */
class ModeSDecoder(
    private val icaoTtlMs: Long = 60_000,
    /** Bit errors repaired in DF17/18 (only for already-known aircraft). */
    private val maxCorrectedBits: Int = 2,
) {
    private val knownIcaos = HashMap<Int, Long>()

    var acceptedFrames = 0L
        private set
    var correctedFrames = 0L
        private set

    /** Returns the frame length in bits for a downlink format, or 0 if unsupported. */
    fun frameBits(df: Int): Int = when (df) {
        0, 4, 5, 11 -> 56
        16, 17, 18, 20, 21 -> 112
        else -> 0
    }

    fun decode(frame: ByteArray, nowMs: Long): ModeSMessage? {
        val df = frame.bits(0, 5).toInt()
        val bits = frameBits(df)
        if (bits == 0 || frame.size * 8 < bits) return null
        val residual = Crc24.residual(frame, bits)

        val msg = when (df) {
            11 -> {
                if (residual and 0xFFFF80 != 0) return null
                val icao = frame.bits(8, 24).toInt()
                remember(icao, nowMs)
                AllCallReply(icao, df, capabilityOnGround(frame.bits(5, 3).toInt()))
            }
            17, 18 -> {
                if (residual != 0) {
                    val original = frame.copyOf()
                    if (Crc24.fixBits112(frame, residual, maxCorrectedBits) == 0) return null
                    // A repaired frame must belong to an aircraft we already trust,
                    // otherwise random noise occasionally "repairs" into a ghost.
                    if (!isKnown(frame.bits(8, 24).toInt(), nowMs)) {
                        original.copyInto(frame)
                        return null
                    }
                    correctedFrames++
                }
                if (df == 18 && frame.bits(5, 3).toInt() !in setOf(0, 1, 6)) return null
                val icao = frame.bits(8, 24).toInt()
                remember(icao, nowMs)
                decodeExtendedSquitter(frame, icao, df)
            }
            0, 16 -> {
                if (!isKnown(residual, nowMs)) return null
                // VS bit: 1 = on the ground.
                AltitudeReply(residual, df, decodeAc13(frame.bits(19, 13).toInt()), onGround = frame.bit(5))
            }
            4, 20 -> {
                if (!isKnown(residual, nowMs)) return null
                AltitudeReply(
                    residual, df, decodeAc13(frame.bits(19, 13).toInt()),
                    onGround = flightStatusOnGround(frame.bits(5, 3).toInt()),
                    commB = if (df == 20) CommB.infer(frame) else emptyList(),
                )
            }
            5, 21 -> {
                if (!isKnown(residual, nowMs)) return null
                IdentityReply(
                    residual, df, decodeSquawk(frame.bits(19, 13).toInt()),
                    onGround = flightStatusOnGround(frame.bits(5, 3).toInt()),
                    commB = if (df == 21) CommB.infer(frame) else emptyList(),
                )
            }
            else -> null
        }
        if (msg != null) acceptedFrames++
        return msg
    }

    private fun remember(icao: Int, nowMs: Long) {
        knownIcaos[icao] = nowMs
        if (knownIcaos.size > 2048) knownIcaos.entries.removeAll { nowMs - it.value > icaoTtlMs }
    }

    private fun isKnown(icao: Int, nowMs: Long): Boolean {
        val t = knownIcaos[icao] ?: return false
        return nowMs - t <= icaoTtlMs
    }

    private fun decodeExtendedSquitter(f: ByteArray, icao: Int, df: Int): ModeSMessage {
        // ME field occupies bits 32..87.
        val tc = f.bits(32, 5).toInt()
        return when (tc) {
            in 1..4 -> Identification(icao, df, decodeCallsign(f), category = f.bits(37, 3).toInt(), typeCode = tc)
            in 5..8 -> SurfacePosition(
                icao, df,
                groundSpeedKt = decodeMovement(f.bits(37, 7).toInt()),
                trackDeg = if (f.bit(44)) f.bits(45, 7) * 360.0 / 128 else null,
                odd = f.bit(53),
                cprLat = f.bits(54, 17).toInt(),
                cprLon = f.bits(71, 17).toInt(),
            )
            28 -> when (f.bits(37, 3).toInt()) {
                1 -> EmergencyStatus(
                    icao, df,
                    emergency = EMERGENCY_STATES.getOrNull(f.bits(40, 3).toInt()),
                    squawk = decodeSquawk(f.bits(43, 13).toInt()),
                )
                2 -> CommB.acasRa(f, 32)?.let { RaBroadcast(icao, df, it) } ?: OtherAdsb(icao, df, 28)
                else -> OtherAdsb(icao, df, 28)
            }
            29 -> decodeTargetState(f, icao, df)
            31 -> OperationalStatus(icao, df, version = f.bits(72, 3).toInt(), surface = f.bits(37, 3).toInt() == 1)
            in 9..18, in 20..22 -> {
                val altField = f.bits(40, 12).toInt()
                val alt = if (tc <= 18) decodeAc12(altField)
                else if (altField == 0) null else (altField * 3.28084).toInt() // GNSS height in metres
                AirbornePosition(
                    icao, df,
                    altitudeFt = alt,
                    gnssAltitude = tc >= 20,
                    odd = f.bit(53),
                    cprLat = f.bits(54, 17).toInt(),
                    cprLon = f.bits(71, 17).toInt(),
                )
            }
            19 -> decodeVelocity(f, icao, df)
            else -> OtherAdsb(icao, df, tc)
        }
    }

    private fun decodeTargetState(f: ByteArray, icao: Int, df: Int): ModeSMessage {
        if (f.bits(37, 2).toInt() == 0) return decodeTargetStateV1(f, icao, df)
        if (f.bits(37, 2).toInt() != 1) return OtherAdsb(icao, df, 29)
        val alt = f.bits(41, 11).toInt()
        val baro = f.bits(52, 9).toInt()
        val modes = f.bit(78)
        fun mode(bit: Int) = if (modes) f.bit(bit) else null
        return TargetState(
            icao, df,
            selectedAltitudeFt = if (alt == 0) null else (alt - 1) * 32,
            altitudeFromFms = f.bit(40),
            baroHpa = if (baro == 0) null else 800 + (baro - 1) * 0.8,
            selectedHeadingDeg = if (f.bit(61)) f.bits(62, 9) * 180.0 / 256 else null,
            autopilot = mode(79), vnav = mode(80), altitudeHold = mode(81),
            approach = mode(83), tcas = f.bit(84), lnav = mode(85),
        )
    }

    /** DO-260A (version 1) layout: target altitude in 100 ft steps, target heading or track. */
    private fun decodeTargetStateV1(f: ByteArray, icao: Int, df: Int): ModeSMessage {
        val verticalSource = f.bits(39, 2).toInt()   // 0 none, 1 MCP/FCU, 2 holding, 3 FMS
        val horizontalSource = f.bits(57, 2).toInt() // 0 none
        val altitude = if (verticalSource == 0) null else -1000 + f.bits(47, 10).toInt() * 100
        val heading = if (horizontalSource == 0) null else f.bits(59, 9).toDouble()
        if (altitude == null && heading == null) return OtherAdsb(icao, df, 29)
        return TargetState(
            icao, df,
            selectedAltitudeFt = altitude?.takeIf { it in -1000..100_000 },
            altitudeFromFms = verticalSource == 3,
            baroHpa = null,
            selectedHeadingDeg = heading?.takeIf { it < 360 },
            autopilot = null, vnav = null, altitudeHold = null, approach = null, lnav = null, tcas = null,
        )
    }

    private fun decodeVelocity(f: ByteArray, icao: Int, df: Int): ModeSMessage {
        val subtype = f.bits(37, 3).toInt()
        val vrRaw = f.bits(69, 9).toInt()
        val vr = if (vrRaw == 0) null else (vrRaw - 1) * 64 * if (f.bit(68)) -1 else 1

        return when (subtype) {
            1, 2 -> {
                val factor = if (subtype == 2) 4 else 1
                val vew = f.bits(46, 10).toInt()
                val vns = f.bits(57, 10).toInt()
                if (vew == 0 || vns == 0) {
                    Velocity(icao, df, null, null, false, vr)
                } else {
                    val vx = (vew - 1) * factor * if (f.bit(45)) -1.0 else 1.0
                    val vy = (vns - 1) * factor * if (f.bit(56)) -1.0 else 1.0
                    var track = Math.toDegrees(atan2(vx, vy))
                    if (track < 0) track += 360.0
                    Velocity(icao, df, hypot(vx, vy), track, false, vr)
                }
            }
            3, 4 -> {
                val factor = if (subtype == 4) 4 else 1
                val heading = if (f.bit(45)) f.bits(46, 10) * 360.0 / 1024.0 else null
                val asRaw = f.bits(57, 10).toInt()
                val speed = if (asRaw == 0) null else ((asRaw - 1) * factor).toDouble()
                Velocity(icao, df, speed, heading, true, vr)
            }
            else -> OtherAdsb(icao, df, 19)
        }
    }

    companion object {
        /** Flight status of DF4/5/20/21: 1/3 on ground, 0/2 airborne, others unknown. */
        fun flightStatusOnGround(fs: Int): Boolean? = when (fs) {
            1, 3 -> true
            0, 2 -> false
            else -> null
        }

        /** Capability of DF11/17: 4 on ground, 5 airborne. */
        fun capabilityOnGround(ca: Int): Boolean? = when (ca) {
            4 -> true
            5 -> false
            else -> null
        }

        /** Surface movement field -> ground speed in knots (null = no information). */
        fun decodeMovement(m: Int): Double? = when (m) {
            1 -> 0.0
            in 2..8 -> (m - 1) * 0.125
            in 9..12 -> 1 + (m - 9) * 0.25
            in 13..38 -> 2 + (m - 13) * 0.5
            in 39..93 -> 15.0 + (m - 39)
            in 94..108 -> 70.0 + (m - 94) * 2
            in 109..123 -> 100.0 + (m - 109) * 5
            124 -> 175.0
            else -> null
        }

        fun categoryName(typeCode: Int, category: Int): String? = when (typeCode) {
            4 -> when (category) {
                1 -> "Light"; 2 -> "Small"; 3 -> "Large"; 4 -> "High-vortex large"
                5 -> "Heavy"; 6 -> "High performance"; 7 -> "Rotorcraft"; else -> null
            }
            3 -> when (category) {
                1 -> "Glider"; 2 -> "Lighter-than-air"; 3 -> "Parachutist"; 4 -> "Ultralight"
                6 -> "UAV"; 7 -> "Space vehicle"; else -> null
            }
            2 -> when (category) {
                1 -> "Emergency vehicle"; 3 -> "Service vehicle"; in 4..7 -> "Obstacle"; else -> null
            }
            else -> null
        }

        private val EMERGENCY_STATES = listOf(
            null, "General emergency", "Medical", "Minimum fuel", "No communications",
            "Unlawful interference", "Downed aircraft",
        )

        private const val CALLSIGN_CHARS =
            "#ABCDEFGHIJKLMNOPQRSTUVWXYZ##### ###############0123456789######"

        fun decodeCallsign(f: ByteArray): String = buildString {
            for (i in 0 until 8) append(CALLSIGN_CHARS[f.bits(40 + i * 6, 6).toInt()])
        }.replace("#", "").trim()

        /** 12-bit ADS-B altitude field (Q bit at LSB index 4). */
        fun decodeAc12(field: Int): Int? {
            if (field == 0) return null
            if (field and 0x10 == 0) return null // Gillham-coded (rare, >50 175 ft or legacy)
            val n = ((field and 0xFE0) ushr 1) or (field and 0x0F)
            return n * 25 - 1000
        }

        /** 13-bit AC field of surveillance replies (M at LSB index 6, Q at index 4). */
        fun decodeAc13(field: Int): Int? {
            if (field == 0) return null
            if (field and 0x40 != 0) return null // metric units, practically unused
            if (field and 0x10 == 0) return null // Gillham-coded
            val n = ((field and 0x1F80) ushr 2) or ((field and 0x20) ushr 1) or (field and 0x0F)
            return n * 25 - 1000
        }

        /** 13-bit identity field: C1 A1 C2 A2 C4 A4 X B1 D1 B2 D2 B4 D4. */
        fun decodeSquawk(field: Int): String {
            fun b(i: Int) = (field ushr i) and 1
            val a = b(7) * 4 + b(9) * 2 + b(11)
            val bb = b(1) * 4 + b(3) * 2 + b(5)
            val c = b(8) * 4 + b(10) * 2 + b(12)
            val d = b(0) * 4 + b(2) * 2 + b(4)
            return "$a$bb$c$d"
        }
    }
}
