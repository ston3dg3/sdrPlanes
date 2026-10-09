package com.sdrplanes.decode

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max

data class LatLon(val lat: Double, val lon: Double)

/** Compact Position Reporting (17-bit airborne and surface) decoding. */
object Cpr {
    private const val NZ = 15
    private const val SCALE = 131072.0 // 2^17

    /** Number of longitude zones for a latitude. */
    fun nl(lat: Double): Int {
        val a = abs(lat)
        if (a == 0.0) return 59
        if (a == 87.0) return 2
        if (a > 87.0) return 1
        val c = cos(PI / 180.0 * a)
        val tmp = 1 - (1 - cos(PI / (2.0 * NZ))) / (c * c)
        return floor(2 * PI / acos(tmp)).toInt()
    }

    private fun mod(a: Double, b: Double): Double = a - b * floor(a / b)

    /**
     * Global decode from an even/odd pair. [evenNewer] selects which frame's latitude
     * zone is used for the result (the most recent one). Returns null when the pair
     * straddles a longitude zone boundary.
     */
    fun global(
        evenLat: Int, evenLon: Int,
        oddLat: Int, oddLon: Int,
        evenNewer: Boolean,
    ): LatLon? {
        val latE = evenLat / SCALE
        val lonE = evenLon / SCALE
        val latO = oddLat / SCALE
        val lonO = oddLon / SCALE

        val j = floor(59 * latE - 60 * latO + 0.5)
        var rlatE = 360.0 / 60 * (mod(j, 60.0) + latE)
        var rlatO = 360.0 / 59 * (mod(j, 59.0) + latO)
        if (rlatE >= 270) rlatE -= 360
        if (rlatO >= 270) rlatO -= 360
        if (rlatE !in -90.0..90.0 || rlatO !in -90.0..90.0) return null
        if (nl(rlatE) != nl(rlatO)) return null

        val lat: Double
        val lon: Double
        if (evenNewer) {
            lat = rlatE
            val nlv = nl(lat)
            val ni = max(nlv, 1)
            val m = floor(lonE * (nlv - 1) - lonO * nlv + 0.5)
            lon = 360.0 / ni * (mod(m, ni.toDouble()) + lonE)
        } else {
            lat = rlatO
            val nlv = nl(lat)
            val ni = max(nlv - 1, 1)
            val m = floor(lonE * (nlv - 1) - lonO * nlv + 0.5)
            lon = 360.0 / ni * (mod(m, ni.toDouble()) + lonO)
        }
        return LatLon(lat, normalizeLon(lon))
    }

    /**
     * Local decode of a single frame relative to a reference position that is within
     * ~180 NM (receiver location, or the aircraft's own last known position).
     */
    fun local(cprLat: Int, cprLon: Int, odd: Boolean, ref: LatLon): LatLon {
        val i = if (odd) 1 else 0
        val latC = cprLat / SCALE
        val lonC = cprLon / SCALE

        val dLat = 360.0 / (60 - i)
        val j = floor(ref.lat / dLat) + floor(0.5 + mod(ref.lat, dLat) / dLat - latC)
        val lat = dLat * (j + latC)

        val dLon = 360.0 / max(nl(lat) - i, 1)
        val m = floor(ref.lon / dLon) + floor(0.5 + mod(ref.lon, dLon) / dLon - lonC)
        val lon = dLon * (m + lonC)
        return LatLon(lat, normalizeLon(lon))
    }

    /**
     * Local decode of a surface position frame. Surface CPR packs the same 17 bits into a
     * quarter of the airborne zone size (90° instead of 360°), so it needs a reference
     * within ~45 NM: the receiver, or the aircraft's last known position.
     */
    fun surfaceLocal(cprLat: Int, cprLon: Int, odd: Boolean, ref: LatLon): LatLon {
        val i = if (odd) 1 else 0
        val latC = cprLat / SCALE
        val lonC = cprLon / SCALE

        val dLat = 90.0 / (60 - i)
        val j = floor(ref.lat / dLat) + floor(0.5 + mod(ref.lat, dLat) / dLat - latC)
        val lat = dLat * (j + latC)

        val dLon = 90.0 / max(nl(lat) - i, 1)
        val m = floor(ref.lon / dLon) + floor(0.5 + mod(ref.lon, dLon) / dLon - lonC)
        val lon = dLon * (m + lonC)
        return LatLon(lat, normalizeLon(lon))
    }

    private fun normalizeLon(lon: Double): Double {
        var l = lon
        while (l >= 180) l -= 360
        while (l < -180) l += 360
        return l
    }
}
