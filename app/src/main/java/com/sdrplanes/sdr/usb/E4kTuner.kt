package com.sdrplanes.sdr.usb

import java.io.IOException
import kotlin.math.abs

/**
 * Elonics E4000 zero-IF tuner, ported from librtlsdr's tuner_e4k.c (GPL-2.0+).
 * Tunes ~52–2200 MHz (with a gap around 1.1–1.25 GHz on many units). Untested on hardware.
 */
class E4kTuner(private val bus: I2cBus, private val fosc: Long) : Tuner {
    override val name = "E4000"
    override val gains = intArrayOf(-10, 15, 40, 65, 90, 115, 140, 165, 190, 215, 240, 290, 340, 420)

    private var band = -1
    private var flo = 0L
    private val one = ByteArray(2)

    // --- register access -------------------------------------------------------------

    private fun write(reg: Int, value: Int) {
        one[0] = reg.toByte(); one[1] = value.toByte()
        bus.i2cWrite(I2C_ADDR, one, 2)
    }

    private fun read(reg: Int): Int {
        one[0] = reg.toByte()
        bus.i2cWrite(I2C_ADDR, one, 1)
        val d = ByteArray(1)
        bus.i2cRead(I2C_ADDR, d, 1)
        return d[0].toInt() and 0xFF
    }

    private fun setMask(reg: Int, mask: Int, value: Int) {
        val tmp = read(reg)
        if ((tmp and mask) == value) return
        write(reg, (tmp and mask.inv()) or (value and mask))
    }

    private fun fieldWrite(reg: Int, shift: Int, width: Int, value: Int) {
        val mask = ((1 shl width) - 1) shl shift
        setMask(reg, mask, value shl shift)
    }

    // --- Tuner -----------------------------------------------------------------------

    override fun init() {
        runCatching { read(0) } // dummy access, not ACKed
        write(REG_MASTER1, MASTER1_RESET or MASTER1_NORM_STBY or MASTER1_POR_DET)
        write(REG_CLK_INP, 0x00)
        write(REG_REF_CLK, 0x00)
        write(REG_CLKOUT_PWDN, 0x96)
        // Magic values from the vendor driver.
        write(0x7e, 0x01); write(0x7f, 0xfe); write(0x82, 0x00); write(0x86, 0x50)
        write(0x87, 0x20); write(0x88, 0x01); write(0x9f, 0x7f); write(0xa0, 0x07)
        write(REG_AGC4, 0x10)
        write(REG_AGC5, 0x04)
        write(REG_AGC6, 0x1a)
        setMask(REG_AGC1, AGC1_MOD_MASK, AGC_MOD_SERIAL)
        setMask(REG_AGC7, AGC7_MIX_GAIN_AUTO, 0)
        enableManualGain(false)
        ifGainSet(1, 6); ifGainSet(2, 0); ifGainSet(3, 0); ifGainSet(4, 0); ifGainSet(5, 9); ifGainSet(6, 9)
        filterSet(FILTER_MIX, 1_900_000)
        filterSet(FILTER_RC, 1_000_000)
        filterSet(FILTER_CHAN, 2_150_000)
        setMask(REG_FILT3, FILT3_DISABLE, 0) // channel filter on
        setMask(REG_DC5, 0x03, 0)
        setMask(REG_DCTIME1, 0x03, 0)
        setMask(REG_DCTIME2, 0x03, 0)
    }

    override fun standby() = setMask(REG_MASTER1, MASTER1_NORM_STBY, 0)

    override fun setFrequency(freqHz: Long) {
        require(fosc in 16_000_000..30_000_000) { "E4000 reference out of range" }
        var r = 2
        var rIdx = 0
        for (p in PLL_VARS) {
            if (freqHz < p[0]) { rIdx = p[1].toInt(); r = p[2].toInt(); break }
        }
        val intendedFvco = freqHz * r
        val z = intendedFvco / fosc
        val remainder = intendedFvco - fosc * z
        val x = (remainder * PLL_Y) / fosc
        flo = (fosc * z + (fosc * x) / PLL_Y) / r
        if (z > 255) throw UnsupportedHardwareException("The E4000 tuner cannot tune %.1f MHz".format(freqHz / 1e6))

        write(REG_SYNTH7, rIdx)
        write(REG_SYNTH3, z.toInt())
        write(REG_SYNTH4, (x and 0xff).toInt())
        write(REG_SYNTH5, (x shr 8).toInt())
        setBand(
            when {
                flo < 140_000_000 -> BAND_VHF2
                flo < 350_000_000 -> BAND_VHF3
                flo < 1_135_000_000 -> BAND_UHF
                else -> BAND_L
            }
        )
        setMask(REG_FILT1, 0xF, rfFilter())
        if (read(REG_SYNTH1) and 0x01 == 0) throw IOException("E4000 PLL did not lock at $freqHz Hz")
    }

    override fun setBandwidth(bwHz: Int): Long {
        filterSet(FILTER_MIX, bwHz)
        filterSet(FILTER_RC, bwHz)
        filterSet(FILTER_CHAN, bwHz)
        return 0
    }

    override fun setGain(gainTenthsDb: Int?) {
        if (gainTenthsDb == null) {
            enableManualGain(false)
            return
        }
        enableManualGain(true)
        val gain = Tuner.nearest(gains, gainTenthsDb)
        val mix = if (gain > 340) 12 else 4
        val lna = minOf(300, gain - mix * 10)
        val code = LNA_GAIN.firstOrNull { it.first == lna }?.second ?: throw IOException("E4000 gain $gain unsupported")
        setMask(REG_GAIN1, 0xf, code)
        setMask(REG_GAIN2, 1, if (mix == 12) 1 else 0)
    }

    // --- internals --------------------------------------------------------------------

    private fun enableManualGain(manual: Boolean) {
        if (manual) {
            setMask(REG_AGC1, AGC1_MOD_MASK, AGC_MOD_SERIAL)
            setMask(REG_AGC7, AGC7_MIX_GAIN_AUTO, 0)
        } else {
            setMask(REG_AGC1, AGC1_MOD_MASK, AGC_MOD_IF_SERIAL_LNA_AUTON)
            setMask(REG_AGC7, AGC7_MIX_GAIN_AUTO, 1)
            setMask(REG_AGC11, 0x7, 0)
        }
    }

    private fun setBand(b: Int) {
        write(REG_BIAS, if (b == BAND_L) 0 else 3)
        // Reset first, or there is a gap at 325–350 MHz (librtlsdr workaround).
        setMask(REG_SYNTH1, 0x06, 0)
        setMask(REG_SYNTH1, 0x06, b shl 1)
        band = b
    }

    private fun rfFilter(): Int = when (band) {
        BAND_UHF -> closest(RF_FILT_UHF, flo)
        BAND_L -> closest(RF_FILT_L, flo)
        else -> 0
    }

    private fun filterSet(filter: Int, bw: Int) {
        val (table, reg, shift, width) = when (filter) {
            FILTER_MIX -> Quad(MIX_FILTER_BW, REG_FILT2, 4, 4)
            FILTER_CHAN -> Quad(IFCH_FILTER_BW, REG_FILT3, 0, 5)
            else -> Quad(IFRC_FILTER_BW, REG_FILT2, 0, 4)
        }
        fieldWrite(reg, shift, width, closest(table, bw.toLong()))
    }

    private fun ifGainSet(stage: Int, value: Int) {
        val (table, reg, shift, width) = IF_STAGES[stage]
        val idx = table.indexOf(value.toLong()).takeIf { it >= 0 } ?: return
        fieldWrite(reg, shift, width, idx)
    }

    private data class Quad(val table: LongArray, val reg: Int, val shift: Int, val width: Int)

    companion object {
        const val I2C_ADDR = 0xc8
        const val CHECK_ADDR = 0x02
        const val CHECK_VAL = 0x40

        private const val REG_MASTER1 = 0x00
        private const val REG_CLK_INP = 0x05
        private const val REG_REF_CLK = 0x06
        private const val REG_SYNTH1 = 0x07
        private const val REG_SYNTH3 = 0x09
        private const val REG_SYNTH4 = 0x0a
        private const val REG_SYNTH5 = 0x0b
        private const val REG_SYNTH7 = 0x0d
        private const val REG_FILT1 = 0x10
        private const val REG_FILT2 = 0x11
        private const val REG_FILT3 = 0x12
        private const val REG_GAIN1 = 0x14
        private const val REG_GAIN2 = 0x15
        private const val REG_GAIN3 = 0x16
        private const val REG_GAIN4 = 0x17
        private const val REG_AGC1 = 0x1a
        private const val REG_AGC4 = 0x1d
        private const val REG_AGC5 = 0x1e
        private const val REG_AGC6 = 0x1f
        private const val REG_AGC7 = 0x20
        private const val REG_AGC11 = 0x24
        private const val REG_DC5 = 0x2d
        private const val REG_DCTIME1 = 0x70
        private const val REG_DCTIME2 = 0x71
        private const val REG_BIAS = 0x78
        private const val REG_CLKOUT_PWDN = 0x7a

        private const val MASTER1_RESET = 1
        private const val MASTER1_NORM_STBY = 2
        private const val MASTER1_POR_DET = 4
        private const val FILT3_DISABLE = 1 shl 5
        private const val AGC1_MOD_MASK = 0xF
        private const val AGC_MOD_SERIAL = 0x0
        private const val AGC_MOD_IF_SERIAL_LNA_AUTON = 0x9
        private const val AGC7_MIX_GAIN_AUTO = 1

        private const val BAND_VHF2 = 0
        private const val BAND_VHF3 = 1
        private const val BAND_UHF = 2
        private const val BAND_L = 3
        private const val PLL_Y = 65536L

        private const val FILTER_MIX = 0
        private const val FILTER_CHAN = 1
        private const val FILTER_RC = 2

        /** Upper LO bound (Hz), SYNTH7 value (3-phase flag | R index), divider R. */
        private val PLL_VARS = arrayOf(
            longArrayOf(72_400_000, (1 shl 3 or 7).toLong(), 48),
            longArrayOf(81_200_000, (1 shl 3 or 6).toLong(), 40),
            longArrayOf(108_300_000, (1 shl 3 or 5).toLong(), 32),
            longArrayOf(162_500_000, (1 shl 3 or 4).toLong(), 24),
            longArrayOf(216_600_000, (1 shl 3 or 3).toLong(), 16),
            longArrayOf(325_000_000, (1 shl 3 or 2).toLong(), 12),
            longArrayOf(350_000_000, (1 shl 3 or 1).toLong(), 8),
            longArrayOf(432_000_000, 3, 8),
            longArrayOf(667_000_000, 2, 6),
            longArrayOf(1_200_000_000, 1, 4),
        )

        private fun mhz(vararg v: Int) = LongArray(v.size) { v[it] * 1_000_000L }
        private fun khz(vararg v: Int) = LongArray(v.size) { v[it] * 1_000L }

        private val RF_FILT_UHF = mhz(360, 380, 405, 425, 450, 475, 505, 540, 575, 615, 670, 720, 760, 840, 890, 970)
        private val RF_FILT_L = mhz(1300, 1320, 1360, 1410, 1445, 1460, 1490, 1530, 1560, 1590, 1640, 1660, 1680, 1700, 1720, 1750)
        private val MIX_FILTER_BW = khz(
            27000, 27000, 27000, 27000, 27000, 27000, 27000, 27000, 4600, 4200, 3800, 3400, 3300, 2700, 2300, 1900,
        )
        private val IFRC_FILTER_BW = khz(
            21400, 21000, 17600, 14700, 12400, 10600, 9000, 7700, 6400, 5300, 4400, 3400, 2600, 1800, 1200, 1000,
        )
        private val IFCH_FILTER_BW = khz(
            5500, 5300, 5000, 4800, 4600, 4400, 4300, 4100, 3900, 3800, 3700, 3600, 3400, 3300, 3200, 3100,
            3000, 2950, 2900, 2800, 2750, 2700, 2600, 2550, 2500, 2450, 2400, 2300, 2280, 2240, 2200, 2150,
        )

        /** IF gain stages 1..6: allowed dB values and their register field. */
        private val IF_STAGES = arrayOf(
            Quad(longArrayOf(), 0, 0, 0),
            Quad(longArrayOf(-3, 6), REG_GAIN3, 0, 1),
            Quad(longArrayOf(0, 3, 6, 9), REG_GAIN3, 1, 2),
            Quad(longArrayOf(0, 3, 6, 9), REG_GAIN3, 3, 2),
            Quad(longArrayOf(0, 1, 2, 2), REG_GAIN3, 5, 2),
            Quad(longArrayOf(3, 6, 9, 12, 15, 15, 15, 15), REG_GAIN4, 0, 3),
            Quad(longArrayOf(3, 6, 9, 12, 15, 15, 15, 15), REG_GAIN4, 3, 3),
        )

        /** LNA gain (tenths of dB) -> GAIN1 code. */
        private val LNA_GAIN = listOf(
            -50 to 0, -25 to 1, 0 to 4, 25 to 5, 50 to 6, 75 to 7, 100 to 8, 125 to 9,
            150 to 10, 175 to 11, 200 to 12, 250 to 13, 300 to 14,
        )

        private fun closest(arr: LongArray, v: Long): Int {
            var best = 0
            var bestDelta = Long.MAX_VALUE
            for (i in arr.indices) {
                val d = abs(v - arr[i])
                if (d < bestDelta) { bestDelta = d; best = i }
            }
            return best
        }
    }
}
