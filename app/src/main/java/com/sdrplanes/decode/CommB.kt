package com.sdrplanes.decode

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Comm-B registers carried in the 56-bit MB field of DF20/21 replies. The register
 * number (BDS) is not transmitted, so it is inferred from the content: each register's
 * status bits must match its data fields, reserved bits must be zero, and values must be
 * physically plausible (the approach of pyModeS). 5,0 and 6,0 can both match the same
 * field; [AircraftTracker][com.sdrplanes.model.AircraftTracker] resolves those against the
 * aircraft's ADS-B state.
 */
sealed interface Bds {
    /** Register number as written in ICAO Doc 9871, e.g. "5,0". */
    val code: String

    /** BDS 1,0 – data link capability. */
    data class Capability(val subnetVersion: Int, val acasOperational: Boolean, val acasRa: Boolean) : Bds {
        override val code get() = "1,0"
    }

    /** BDS 1,7 – common usage GICB capability: registers the transponder will report. */
    data class GicbCapability(val supported: Set<String>) : Bds {
        override val code get() = "1,7"
    }

    /** BDS 3,0 – ACAS (TCAS) active resolution advisory. */
    data class AcasRa(
        val issued: Boolean, val corrective: Boolean, val downward: Boolean, val increasedRate: Boolean,
        val reversal: Boolean, val crossing: Boolean, val terminated: Boolean, val multipleThreats: Boolean,
        val threatIcao: Int?,
    ) : Bds {
        override val code get() = "3,0"

        /** Short cockpit-style wording of the advisory, or null when none is active. */
        val advisory: String?
            get() = when {
                terminated -> "CLEAR OF CONFLICT"
                !issued -> null
                !corrective -> "MONITOR VS"
                else -> buildList {
                    if (reversal) add("REVERSE")
                    if (increasedRate) add("INCREASE")
                    add(if (downward) "DESCEND" else "CLIMB")
                    if (crossing) add("CROSSING")
                }.joinToString(" ")
            }
    }

    /** BDS 4,4 – meteorological routine air report. */
    data class MeteoRoutine(
        val windKt: Int?, val windFromDeg: Double?, val temperatureC: Double,
        val pressureHpa: Int?, val turbulence: Int?, val humidityPct: Double?,
    ) : Bds {
        override val code get() = "4,4"
    }

    /** BDS 4,5 – meteorological hazard report; levels 0 = nil .. 3 = severe. */
    data class MeteoHazard(
        val turbulence: Int?, val windShear: Int?, val microburst: Int?, val icing: Int?, val wakeVortex: Int?,
        val temperatureC: Double?, val pressureHpa: Int?, val radioHeightFt: Int?,
    ) : Bds {
        override val code get() = "4,5"
    }

    /** BDS 2,0 – aircraft identification. */
    data class Identification(val callsign: String) : Bds {
        override val code get() = "2,0"
    }

    /** BDS 4,0 – selected vertical intention. */
    data class VerticalIntention(val mcpAltitudeFt: Int?, val fmsAltitudeFt: Int?, val baroHpa: Double?) : Bds {
        override val code get() = "4,0"
    }

    /** BDS 5,0 – track and turn report. */
    data class TrackAndTurn(
        val rollDeg: Double?, val trackDeg: Double?, val groundSpeedKt: Int?,
        val trackRateDegS: Double?, val trueAirspeedKt: Int?,
    ) : Bds {
        override val code get() = "5,0"
    }

    /** BDS 6,0 – heading and speed report. */
    data class HeadingAndSpeed(
        val magHeadingDeg: Double?, val indicatedAirspeedKt: Int?, val mach: Double?,
        val baroRateFpm: Int?, val inertialRateFpm: Int?,
    ) : Bds {
        override val code get() = "6,0"
    }
}

object CommB {
    /** All registers the MB field of [f] (a 112-bit DF20/21 frame) plausibly holds. */
    fun infer(f: ByteArray): List<Bds> {
        if (f.bits(32, 56) == 0L) return emptyList()
        // Registers with a fixed identifier byte (or the very specific 1,7 pattern) win outright.
        (bds17(f) ?: bds10(f) ?: bds20(f) ?: bds30(f))?.let { return listOf(it) }
        return listOfNotNull(bds40(f), bds44(f), bds45(f), bds50(f), bds60(f))
    }

    private val GICB_REGISTERS = listOf(
        "0,5", "0,6", "0,7", "0,8", "0,9", "0,A", "2,0", "2,1", "4,0", "4,1", "4,2", "4,3",
        "4,4", "4,5", "4,8", "5,0", "5,1", "5,2", "5,3", "5,4", "5,5", "5,6", "5,F", "6,0",
    )

    private fun bds10(f: ByteArray): Bds? {
        if (f.mb(1, 8) != 0x10 || f.mb(10, 5) != 0) return null
        val overlay = f.mbBit(15)
        val subnet = f.mb(17, 7)
        // Overlay capability implies a subnetwork version >= 5 (pyModeS heuristic).
        if (overlay && subnet < 5 || !overlay && subnet > 4) return null
        return Bds.Capability(subnet, acasOperational = f.mbBit(16), acasRa = f.mbBit(38))
    }

    private fun bds17(f: ByteArray): Bds? {
        // 2,0 support is mandatory; MB bits 25-56 are reserved.
        if (!f.mbBit(7) || f.bits(31 + 25, 32) != 0L) return null
        return Bds.GicbCapability(GICB_REGISTERS.filterIndexed { i, _ -> f.mbBit(1 + i) }.toSet())
    }

    /** ARA/RAC/TTI layout shared by Comm-B 3,0 and the ADS-B RA broadcast (from MB/ME bit 9). */
    fun acasRa(f: ByteArray, firstBit: Int): Bds.AcasRa? {
        fun bit(n: Int) = f.bits(firstBit + n - 1, 1) == 1L
        fun bits(n: Int, w: Int) = f.bits(firstBit + n - 1, w).toInt()
        if (bits(16, 7) >= 48) return null // ARA bits reserved for ACAS III
        val tti = bits(29, 2)
        if (tti == 3) return null
        return Bds.AcasRa(
            issued = bit(9), corrective = bit(10), downward = bit(11), increasedRate = bit(12),
            reversal = bit(13), crossing = bit(14), terminated = bit(27), multipleThreats = bit(28),
            threatIcao = if (tti == 1) bits(31, 24) else null,
        )
    }

    private fun bds30(f: ByteArray): Bds? {
        if (f.mb(1, 8) != 0x30) return null
        return acasRa(f, 32)
    }

    private fun bds44(f: ByteArray): Bds? {
        if (f.mb(1, 4) > 4 || !f.mbBit(5)) return null // figure of merit, wind present
        val pressure = field(f, 35, 36, 11) ?: return null
        val turbulence = field(f, 47, 48, 2) ?: return null
        val humidity = field(f, 50, 51, 6) ?: return null
        val windKt = f.mb(6, 9)
        val windDirRaw = f.mb(15, 9)
        val tempRaw = f.mb(25, 10)
        if (windKt > 250) return null
        val temp = (if (f.mbBit(24)) tempRaw - 1024 else tempRaw) * 0.25
        if (temp !in -80.0..60.0) return null
        if (windKt == 0 && windDirRaw == 0 && tempRaw == 0) return null
        return Bds.MeteoRoutine(
            windKt, windDirRaw * 180.0 / 256, temp,
            pressure.value, turbulence.value, humidity.value?.let { it * 100.0 / 64 },
        )
    }

    private fun bds45(f: ByteArray): Bds? {
        if (f.mb(52, 5) != 0) return null
        val turb = field(f, 1, 2, 2) ?: return null
        val shear = field(f, 4, 5, 2) ?: return null
        val burst = field(f, 7, 8, 2) ?: return null
        val icing = field(f, 10, 11, 2) ?: return null
        val wake = field(f, 13, 14, 2) ?: return null
        val temp = field(f, 16, 17, 10) ?: return null
        val pressure = field(f, 27, 28, 11) ?: return null
        val height = field(f, 39, 40, 12) ?: return null
        val tempC = temp.value?.let { signed(it, 10) * 0.25 }
        if (tempC != null && tempC !in -80.0..60.0) return null
        val all = listOf(turb, shear, burst, icing, wake, temp, pressure, height)
        if (all.all { it.isAbsent }) return null
        return Bds.MeteoHazard(
            turb.value, shear.value, burst.value, icing.value, wake.value,
            tempC, pressure.value, height.value?.times(16),
        )
    }

    /** MB bit [n] (1-based, as in ICAO Doc 9871) .. [n]+[count]-1. */
    private fun ByteArray.mb(n: Int, count: Int): Int = bits(31 + n, count).toInt()
    private fun ByteArray.mbBit(n: Int): Boolean = bits(31 + n, 1) == 1L

    /** Two's complement of a [width]-bit field whose MSB is the sign. */
    private fun signed(v: Int, width: Int) = if (v and (1 shl (width - 1)) != 0) v - (1 shl width) else v

    private fun bds20(f: ByteArray): Bds? {
        if (f.mb(1, 8) != 0x20) return null
        val sb = StringBuilder()
        for (i in 0 until 8) {
            val c = CHARS[f.mb(9 + i * 6, 6)]
            if (c == '#') return null
            sb.append(c)
        }
        val callsign = sb.toString().trim()
        return if (callsign.isEmpty()) null else Bds.Identification(callsign)
    }

    private fun bds40(f: ByteArray): Bds? {
        if (f.mb(40, 8) != 0 || f.mb(52, 2) != 0) return null
        val mcp = field(f, 1, 2, 12) ?: return null
        val fms = field(f, 14, 15, 12) ?: return null
        val baro = field(f, 27, 28, 12) ?: return null
        if (field(f, 48, 49, 3) == null || field(f, 54, 55, 2) == null) return null
        if (mcp.isAbsent && fms.isAbsent && baro.isAbsent) return null
        val mcpFt = mcp.value?.times(16)
        val fmsFt = fms.value?.times(16)
        val hpa = baro.value?.let { it * 0.1 + 800 }
        if (mcpFt != null && mcpFt !in 1..50_000) return null
        if (fmsFt != null && fmsFt !in 1..50_000) return null
        if (hpa != null && hpa !in 900.0..1100.0) return null
        return Bds.VerticalIntention(mcpFt, fmsFt, hpa)
    }

    private fun bds50(f: ByteArray): Bds? {
        val roll = field(f, 1, 2, 10) ?: return null
        val track = field(f, 12, 13, 11) ?: return null
        val gs = field(f, 24, 25, 10) ?: return null
        val rate = field(f, 35, 36, 10) ?: return null
        val tas = field(f, 46, 47, 10) ?: return null
        if (roll.isAbsent && track.isAbsent && gs.isAbsent && rate.isAbsent && tas.isAbsent) return null
        val rollDeg = roll.value?.let { signed(it, 10) * 45.0 / 256 }
        val trackDeg = track.value?.let { (signed(it, 11) * 90.0 / 512 + 360) % 360 }
        val gsKt = gs.value?.times(2)
        val rateDegS = rate.value?.let { signed(it, 10) * 8.0 / 256 }
        val tasKt = tas.value?.times(2)
        if (rollDeg != null && abs(rollDeg) > 50) return null
        if (gsKt != null && gsKt > 600) return null
        if (tasKt != null && (tasKt > 500 || tasKt < 50)) return null
        if (gsKt != null && tasKt != null && abs(gsKt - tasKt) > 200) return null
        return Bds.TrackAndTurn(rollDeg, trackDeg, gsKt, rateDegS, tasKt)
    }

    private fun bds60(f: ByteArray): Bds? {
        val hdg = field(f, 1, 2, 11) ?: return null
        val ias = field(f, 13, 14, 10) ?: return null
        val mach = field(f, 24, 25, 10) ?: return null
        val baroVr = field(f, 35, 36, 10) ?: return null
        val inertialVr = field(f, 46, 47, 10) ?: return null
        if (hdg.isAbsent && ias.isAbsent && mach.isAbsent && baroVr.isAbsent && inertialVr.isAbsent) return null
        val hdgDeg = hdg.value?.let { (signed(it, 11) * 90.0 / 512 + 360) % 360 }
        val iasKt = ias.value
        val m = mach.value?.let { it * 2.048 / 512 }
        val bvr = baroVr.value?.let { signed(it, 10) * 32 }
        val ivr = inertialVr.value?.let { signed(it, 10) * 32 }
        if (iasKt != null && iasKt !in 1..500) return null
        if (m != null && (m > 1.0 || m <= 0.0)) return null
        if (bvr != null && abs(bvr) > 6000) return null
        if (ivr != null && abs(ivr) > 6000) return null
        if (bvr != null && ivr != null && abs(bvr - ivr) > 2000) return null
        return Bds.HeadingAndSpeed(hdgDeg, iasKt, m, bvr, ivr)
    }

    private class Field(val value: Int?) {
        val isAbsent get() = value == null
    }

    /**
     * A data field guarded by a status bit: absent when the status is 0 (then all its bits
     * must be 0, or the register is not this one).
     */
    private fun field(f: ByteArray, statusBit: Int, from: Int, width: Int): Field? {
        val v = f.mb(from, width)
        return if (f.mbBit(statusBit)) Field(v) else if (v == 0) Field(null) else null
    }

    /** Mach for a calibrated airspeed at a pressure altitude (ISA). */
    fun machFromCas(casKt: Double, altitudeFt: Double): Double {
        val h = altitudeFt * 0.3048
        val p = if (h < 11_000) 101_325 * (1 - 2.25577e-5 * h).pow(5.25588) else 22_632 * exp(-(h - 11_000) / 6341.62)
        val a0 = 661.4786
        val qc = 101_325 * ((1 + 0.2 * (casKt / a0).pow(2)).pow(3.5) - 1)
        return sqrt(5 * ((qc / p + 1).pow(2.0 / 7) - 1))
    }

    private const val CHARS = "#ABCDEFGHIJKLMNOPQRSTUVWXYZ##### ###############0123456789######"
}
