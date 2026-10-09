package com.sdrplanes.sdr.usb


/** Register access shared by the Fitipower/FCI tuners (plain 8-bit I2C registers). */
private class FcRegs(private val bus: I2cBus, private val addr: Int) {
    private val buf = ByteArray(2)

    fun write(reg: Int, value: Int) {
        buf[0] = reg.toByte(); buf[1] = value.toByte()
        bus.i2cWrite(addr, buf, 2)
    }

    fun read(reg: Int): Int {
        buf[0] = reg.toByte()
        bus.i2cWrite(addr, buf, 1)
        val d = ByteArray(1)
        bus.i2cRead(addr, d, 1)
        return d[0].toInt() and 0xFF
    }
}

/**
 * Fractional-N synthesiser shared by FC0012 and FC0013 (librtlsdr fc0012_set_params /
 * fc0013_set_params). [dividers] maps the upper frequency bound to (multiplier, reg5, reg6).
 */
private fun fcSetParams(r: FcRegs, name: String, freqHz: Long, xtalHz: Long, dividers: List<Triple<Long, Int, Pair<Int, Int>>>, afterWrite: (multi: Int) -> Unit) {
    val reg = IntArray(7)
    val xtalDiv2 = xtalHz / 2
    val (_, multi, regs) = dividers.first { freqHz < it.first }.let { Triple(it.first, it.second, it.third) }
    reg[5] = regs.first
    reg[6] = regs.second
    val fVco = freqHz * multi
    var vcoSelect = false
    if (fVco >= 3_060_000_000L) {
        reg[6] = reg[6] or 0x08
        vcoSelect = true
    }
    var xdiv = (fVco / xtalDiv2).toInt()
    if (fVco - xdiv * xtalDiv2 >= xtalDiv2 / 2) xdiv++
    var pm = xdiv / 8
    var am = xdiv - 8 * pm
    if (am < 2) { am += 8; pm-- }
    if (pm > 31) { reg[1] = am + 8 * (pm - 31); reg[2] = 31 } else { reg[1] = am; reg[2] = pm }
    if (reg[1] > 15 || reg[2] < 0x0b) throw UnsupportedHardwareException("The $name tuner cannot tune %.1f MHz".format(freqHz / 1e6))
    reg[6] = reg[6] or 0x20 // fix clock out
    var xin = ((fVco - (fVco / xtalDiv2) * xtalDiv2) / 1000).toInt() and 0xFFFF
    xin = ((xin shl 15) / (xtalDiv2 / 1000).toInt()) and 0xFFFF
    if (xin >= 16384) xin = (xin + 32768) and 0xFFFF
    reg[3] = xin shr 8
    reg[4] = xin and 0xff
    reg[6] = (reg[6] and 0x3f) or 0x80 // 6 MHz bandwidth, as librtlsdr requests
    reg[5] = reg[5] or 0x07 // modified for Realtek demod
    for (i in 1..6) r.write(i, reg[i])
    afterWrite(multi)
    // VCO calibration, then re-select the VCO band if the control voltage is at an end.
    r.write(0x0e, 0x80); r.write(0x0e, 0x00); r.write(0x0e, 0x00)
    val tmp = r.read(0x0e) and 0x3f
    if (vcoSelect && tmp > 0x3c || !vcoSelect && tmp < 0x02) {
        reg[6] = if (vcoSelect) reg[6] and 0x08.inv() else reg[6] or 0x08
        r.write(0x06, reg[6])
        r.write(0x0e, 0x80); r.write(0x0e, 0x00)
    }
}

/**
 * Fitipower FC0013, ported from librtlsdr's tuner_fc0013.c (GPL-2.0+). Zero-IF, ~22–1100
 * MHz, so 1090 MHz is at its upper edge. Untested on hardware.
 */
class Fc0013Tuner(bus: I2cBus, private val xtalHz: Long) : Tuner {
    private val r = FcRegs(bus, I2C_ADDR)
    override val name = "FC0013"
    override val gains = intArrayOf(-99, -73, -65, -63, -60, -58, -54, 58, 61, 63, 65, 67, 68, 70, 71, 179, 181, 182, 184, 186, 188, 191, 197)

    override fun init() {
        val reg = intArrayOf(
            0x00, 0x09, 0x16, 0x00, 0x00, 0x17, 0x02, 0x0a, 0xff, 0x6e, 0xb8, 0x82, 0xfc, 0x01,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x50, 0x01,
        )
        reg[0x07] = reg[0x07] or 0x20 // 28.8 MHz crystal
        reg[0x0c] = reg[0x0c] or 0x02 // dual master
        for (i in 1 until reg.size) r.write(i, reg[i])
    }

    override fun standby() = Unit

    override fun setFrequency(freqHz: Long) {
        // VHF tracking filter (UHF and up: maximum).
        val track = when {
            freqHz <= 177_500_000 -> 0x1c
            freqHz <= 184_500_000 -> 0x18
            freqHz <= 191_500_000 -> 0x14
            freqHz <= 198_500_000 -> 0x10
            freqHz <= 205_500_000 -> 0x0c
            freqHz <= 219_500_000 -> 0x08
            freqHz < 300_000_000 -> 0x04
            else -> 0x1c
        }
        r.write(0x1d, (r.read(0x1d) and 0xe3) or track)
        if (freqHz < 300_000_000) {
            r.write(0x07, r.read(0x07) or 0x10)
            r.write(0x14, r.read(0x14) and 0x1f)
        } else {
            r.write(0x07, r.read(0x07) and 0xef)
            r.write(0x14, (r.read(0x14) and 0x1f) or 0x40)
        }
        fcSetParams(r, name, freqHz, xtalHz, DIVIDERS) { multi ->
            val t = r.read(0x11)
            r.write(0x11, if (multi == 64) t or 0x04 else t and 0xfb)
        }
    }

    override fun setBandwidth(bwHz: Int): Long = 0

    override fun setGain(gainTenthsDb: Int?) {
        // Gain mode: bit 3 of 0x0d = manual.
        val t = r.read(0x0d)
        r.write(0x0d, if (gainTenthsDb != null) t or 0x08 else t and 0x08.inv())
        r.write(0x13, 0x0a)
        if (gainTenthsDb == null) return
        val g = Tuner.nearest(gains, gainTenthsDb)
        val code = LNA_GAINS.firstOrNull { it.first >= g }?.second ?: LNA_GAINS.last().second
        r.write(0x14, (r.read(0x14) and 0xe0) or code)
    }

    companion object {
        const val I2C_ADDR = 0xc6
        const val CHECK_VAL = 0xa3
        private val DIVIDERS = listOf(
            Triple(37_084_000L, 96, 0x82 to 0x00), Triple(55_625_000L, 64, 0x02 to 0x02),
            Triple(74_167_000L, 48, 0x42 to 0x00), Triple(111_250_000L, 32, 0x82 to 0x02),
            Triple(148_334_000L, 24, 0x22 to 0x00), Triple(222_500_000L, 16, 0x42 to 0x02),
            Triple(296_667_000L, 12, 0x12 to 0x00), Triple(445_000_000L, 8, 0x22 to 0x02),
            Triple(593_334_000L, 6, 0x0a to 0x00), Triple(950_000_000L, 4, 0x12 to 0x02),
            Triple(Long.MAX_VALUE, 2, 0x0a to 0x02),
        )
        private val LNA_GAINS = listOf(
            -99 to 0x02, -73 to 0x03, -65 to 0x05, -63 to 0x04, -63 to 0x00, -60 to 0x07, -58 to 0x01,
            -54 to 0x06, 58 to 0x0f, 61 to 0x0e, 63 to 0x0d, 65 to 0x0c, 67 to 0x0b, 68 to 0x0a,
            70 to 0x09, 71 to 0x08, 179 to 0x17, 181 to 0x16, 182 to 0x15, 184 to 0x14, 186 to 0x13,
            188 to 0x12, 191 to 0x11, 197 to 0x10,
        )
    }
}

/**
 * Fitipower FC0012, ported from librtlsdr's tuner_fc0012.c (GPL-2.0+). Its synthesiser
 * ends around 950 MHz, so it cannot receive 1090 MHz; it is detected so the user gets a
 * clear message instead of a silent failure. Untested on hardware.
 */
class Fc0012Tuner(bus: I2cBus, private val xtalHz: Long, private val gpio: Gpio) : Tuner {
    private val r = FcRegs(bus, I2C_ADDR)
    override val name = "FC0012"
    override val gains = intArrayOf(-99, -40, 71, 179, 192)

    override fun init() {
        val reg = intArrayOf(
            0x00, 0x05, 0x10, 0x00, 0x00, 0x0f, 0x00, 0x00, 0xff, 0x6e, 0xb8, 0x82, 0xfc, 0x02,
            0x00, 0x00, 0x00, 0x00, 0x1f, 0x00, 0x00, 0x04,
        )
        reg[0x07] = reg[0x07] or 0x20
        reg[0x0c] = reg[0x0c] or 0x02
        for (i in 1 until reg.size) r.write(i, reg[i])
    }

    override fun standby() = Unit

    override fun setFrequency(freqHz: Long) {
        if (freqHz > MAX_FREQ_HZ) {
            throw UnsupportedHardwareException("The FC0012 tuner only reaches ~950 MHz and can't receive 1090 MHz")
        }
        gpio.set(6, freqHz > 300_000_000) // VHF/UHF input switch on these sticks
        fcSetParams(r, name, freqHz, xtalHz, DIVIDERS) {}
    }

    override fun setBandwidth(bwHz: Int): Long = 0

    override fun setGain(gainTenthsDb: Int?) {
        if (gainTenthsDb == null) return // no gain mode control in librtlsdr
        val g = Tuner.nearest(gains, gainTenthsDb)
        val bits = when {
            g < -40 -> 0x02
            g < 71 -> 0x00
            g < 179 -> 0x08
            g < 192 -> 0x17
            else -> 0x10
        }
        r.write(0x13, (r.read(0x13) and 0xe0) or bits)
    }

    companion object {
        const val I2C_ADDR = 0xc6
        const val CHECK_VAL = 0xa1
        private const val MAX_FREQ_HZ = 948_600_000L
        private val DIVIDERS = listOf(
            Triple(37_084_000L, 96, 0x82 to 0x00), Triple(55_625_000L, 64, 0x82 to 0x02),
            Triple(74_167_000L, 48, 0x42 to 0x00), Triple(111_250_000L, 32, 0x42 to 0x02),
            Triple(148_334_000L, 24, 0x22 to 0x00), Triple(222_500_000L, 16, 0x22 to 0x02),
            Triple(296_667_000L, 12, 0x12 to 0x00), Triple(445_000_000L, 8, 0x12 to 0x02),
            Triple(593_334_000L, 6, 0x0a to 0x00), Triple(Long.MAX_VALUE, 4, 0x0a to 0x02),
        )
    }
}

/**
 * FCI FC2580, ported from librtlsdr's tuner_fc2580.c (GPL-2.0+; itself the vendor driver).
 * 16.384 MHz crystal. 1090 MHz falls into its L-band path, which is meant for 1.45 GHz DAB;
 * expect poor sensitivity. Untested on hardware.
 */
class Fc2580Tuner(bus: I2cBus) : Tuner {
    private val r = FcRegs(bus, I2C_ADDR)
    override val name = "FC2580"
    override val gains = intArrayOf(0)

    override fun init() {
        val init = intArrayOf(
            0x00, 0x00, 0x12, 0x86, 0x14, 0x5C, 0x16, 0x3C, 0x1F, 0xD2, 0x09, 0xD7, 0x0B, 0xD5,
            0x0C, 0x32, 0x0E, 0x43, 0x21, 0x0A, 0x22, 0x82,
            0x45, 0x20, 0x4C, 0x02, // external (voltage-controlled) AGC
            0x3F, 0x88, 0x02, 0x0E, 0x58, 0x14,
        )
        for (i in init.indices step 2) r.write(init[i], init[i + 1])
        setFilter(8)
    }

    override fun standby() = Unit

    override fun setFrequency(freqHz: Long) {
        val fLo = ((freqHz + 500) / 1000).toInt() // kHz
        val band = if (fLo > 1_000_000) L_BAND else if (fLo > 400_000) UHF_BAND else VHF_BAND
        val fVco = when (band) {
            UHF_BAND -> fLo.toLong() * 4
            L_BAND -> fLo.toLong() * 2
            else -> fLo.toLong() * 12
        }
        val rVal = if (fVco >= 2L * 76 * XTAL_KHZ) 1 else if (fVco >= 76L * XTAL_KHZ) 2 else 4
        val fComp = XTAL_KHZ / rVal
        val nVal = (fVco / 2 / fComp).toInt()
        val fDiff = fVco - 2L * fComp * nVal
        val pre = 4
        val fDiffShifted = fDiff shl (20 - pre)
        val div = (2L * fComp) shr pre
        var kVal = fDiffShifted / div
        if (fDiffShifted - kVal * div >= (fComp.toLong() shr pre)) kVal++
        var data02 = 0x0E // USE_EXT_CLK = 0
        data02 = if (fVco >= BORDER_FREQ_KHZ) data02 or 0x08 else data02 and 0xF7

        when (band) {
            UHF_BAND -> {
                data02 = data02 and 0x3F
                writeAll(0x25, 0xF0, 0x27, 0x77, 0x28, 0x53, 0x29, 0x60, 0x30, 0x09, 0x50, 0x8C, 0x53, 0x50)
                r.write(0x5F, if (fLo < 538_000) 0x13 else 0x15)
                when {
                    fLo < 538_000 -> writeAll(0x61, 0x07, 0x62, 0x06, 0x67, 0x06, 0x68, 0x08, 0x69, 0x10, 0x6A, 0x12)
                    fLo < 794_000 -> writeAll(0x61, 0x03, 0x62, 0x03, 0x67, 0x03, 0x68, 0x05, 0x69, 0x0C, 0x6A, 0x0E)
                    else -> writeAll(0x61, 0x07, 0x62, 0x06, 0x67, 0x07, 0x68, 0x09, 0x69, 0x10, 0x6A, 0x12)
                }
                writeAll(0x63, 0x15, 0x6B, 0x0B, 0x6C, 0x0C, 0x6D, 0x78, 0x6E, 0x32, 0x6F, 0x14)
                setFilter(8)
            }
            VHF_BAND -> {
                data02 = (data02 and 0x3F) or 0x80
                writeAll(
                    0x27, 0x77, 0x28, 0x33, 0x29, 0x40, 0x30, 0x09, 0x50, 0x8C, 0x53, 0x50, 0x5F, 0x0F,
                    0x61, 0x07, 0x62, 0x00, 0x63, 0x15, 0x67, 0x03, 0x68, 0x05, 0x69, 0x10, 0x6A, 0x12,
                    0x6B, 0x08, 0x6C, 0x0A, 0x6D, 0x78, 0x6E, 0x32, 0x6F, 0x54,
                )
                setFilter(7)
            }
            else -> {
                data02 = (data02 and 0x3F) or 0x40
                writeAll(
                    0x2B, 0x70, 0x2C, 0x37, 0x2D, 0xE7, 0x30, 0x09, 0x44, 0x20, 0x50, 0x8C, 0x53, 0x50,
                    0x5F, 0x0F, 0x61, 0x0F, 0x62, 0x00, 0x63, 0x13, 0x67, 0x00, 0x68, 0x02, 0x69, 0x0C,
                    0x6A, 0x0E, 0x6B, 0x08, 0x6C, 0x0A, 0x6D, 0xA0, 0x6E, 0x50, 0x6F, 0x14,
                )
                setFilter(1)
            }
        }
        r.write(0x02, data02)
        r.write(0x18, (if (rVal == 1) 0x00 else if (rVal == 2) 0x10 else 0x20) + (kVal shr 16).toInt())
        r.write(0x1A, (kVal shr 8).toInt() and 0xff)
        r.write(0x1B, kVal.toInt() and 0xff)
        r.write(0x1C, nVal and 0xff)
        if (band == UHF_BAND) r.write(0x2D, if (fLo <= 794_000) 0x9F else 0x8F)
    }

    override fun setBandwidth(bwHz: Int): Long {
        setFilter(1) // librtlsdr always selects the 1.53 MHz (T-DMB) filter
        return 0
    }

    override fun setGain(gainTenthsDb: Int?) = Unit // no gain control in librtlsdr

    private fun writeAll(vararg pairs: Int) {
        for (i in pairs.indices step 2) r.write(pairs[i], pairs[i + 1])
    }

    private fun setFilter(bw: Int) {
        when (bw) {
            1 -> writeAll(0x36, 0x1C, 0x37, 4151 * XTAL_KHZ / 1_000_000, 0x39, 0x00, 0x2E, 0x09)
            6 -> writeAll(0x36, 0x18, 0x37, 4400 * XTAL_KHZ / 1_000_000, 0x39, 0x00, 0x2E, 0x09)
            7 -> writeAll(0x36, 0x18, 0x37, 3910 * XTAL_KHZ / 1_000_000, 0x39, 0x80, 0x2E, 0x09)
            8 -> writeAll(0x36, 0x18, 0x37, 3300 * XTAL_KHZ / 1_000_000, 0x39, 0x80, 0x2E, 0x09)
        }
        for (i in 0 until 5) {
            if (r.read(0x2F) and 0xC0 == 0xC0) break
            r.write(0x2E, 0x01)
            r.write(0x2E, 0x09)
        }
        r.write(0x2E, 0x01)
    }

    companion object {
        const val I2C_ADDR = 0xac
        const val CHECK_ADDR = 0x01
        const val CHECK_VAL = 0x56
        private const val XTAL_KHZ = 16_384
        private const val BORDER_FREQ_KHZ = 2_600_000L
        private const val VHF_BAND = 0
        private const val UHF_BAND = 1
        private const val L_BAND = 2
    }
}
