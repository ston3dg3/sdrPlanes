package com.sdrplanes.decode

import kotlin.math.atan2
import kotlin.math.hypot

sealed interface ModeSMessage {
    val icao: Int
    val df: Int
}

/** DF11 – all-call reply, carries only the address. */
data class AllCallReply(override val icao: Int, override val df: Int) : ModeSMessage

/** DF0/4/16/20 – altitude replies. */
data class AltitudeReply(override val icao: Int, override val df: Int, val altitudeFt: Int?) : ModeSMessage

/** DF5/21 – identity (squawk) replies. */
data class IdentityReply(override val icao: Int, override val df: Int, val squawk: String) : ModeSMessage

/** ADS-B TC 1–4. */
data class Identification(
    override val icao: Int, override val df: Int,
    val callsign: String, val category: Int,
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

/** Any other ADS-B type code (surface position, status, ...), kept for statistics. */
data class OtherAdsb(override val icao: Int, override val df: Int, val typeCode: Int) : ModeSMessage

/**
 * Validates (CRC) and decodes raw Mode S frames.
 *
 * Address/parity replies (DF0/4/5/16/20/21) have no standalone integrity check, so they
 * are only accepted when the recovered address was recently seen in a CRC-protected
 * frame (DF11/17/18). Not thread-safe; use from a single decoding coroutine.
 */
class ModeSDecoder(private val icaoTtlMs: Long = 60_000) {
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
                AllCallReply(icao, df)
            }
            17, 18 -> {
                if (residual != 0) {
                    if (!Crc24.fixSingleBit112(frame, residual)) return null
                    // A repaired frame must belong to an aircraft we already trust,
                    // otherwise random noise occasionally "repairs" into a ghost.
                    if (!isKnown(frame.bits(8, 24).toInt(), nowMs)) return null
                    correctedFrames++
                }
                if (df == 18 && frame.bits(5, 3).toInt() !in setOf(0, 1, 6)) return null
                val icao = frame.bits(8, 24).toInt()
                remember(icao, nowMs)
                decodeExtendedSquitter(frame, icao, df)
            }
            0, 4, 16, 20 -> {
                if (!isKnown(residual, nowMs)) return null
                AltitudeReply(residual, df, decodeAc13(frame.bits(19, 13).toInt()))
            }
            5, 21 -> {
                if (!isKnown(residual, nowMs)) return null
                IdentityReply(residual, df, decodeSquawk(frame.bits(19, 13).toInt()))
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
            in 1..4 -> Identification(icao, df, decodeCallsign(f), category = f.bits(37, 3).toInt())
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
