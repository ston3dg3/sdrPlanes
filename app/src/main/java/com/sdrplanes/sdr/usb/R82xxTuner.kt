package com.sdrplanes.sdr.usb

import java.io.IOException

/** I2C access through the RTL2832U's repeater. */
interface I2cBus {
    fun i2cWrite(addr: Int, data: ByteArray, len: Int)
    fun i2cRead(addr: Int, data: ByteArray, len: Int)
}

/** RTL-SDR Blog dongles with switched inputs (detected from the USB strings). */
enum class BlogModel { NONE, V4, V4_LITE }

/**
 * Rafael Micro R820T / R828D tuner, ported from osmocom librtlsdr's tuner_r82xx.c
 * (GPL-2.0+), including the RTL-SDR Blog V4 / V4 Lite input switching. Only the paths
 * librtlsdr uses for SDR operation are implemented; the frequency-dependent RF mux table is
 * limited to the band above 650 MHz, which covers 1090 MHz.
 */
class R82xxTuner(
    private val bus: I2cBus,
    val isR828D: Boolean,
    /** Tuner reference crystal in Hz (ppm-corrected). */
    private val xtalHz: Long,
    private val blog: BlogModel = BlogModel.NONE,
    private val gpio: Gpio = Gpio { _, _ -> },
) : Tuner {
    override val name = when {
        blog == BlogModel.V4 -> "R828D (Blog V4)"
        blog == BlogModel.V4_LITE -> "R820T (Blog V4L)"
        isR828D -> "R828D"
        else -> "R820T"
    }
    override val gains get() = GAINS
    override val lowIf get() = true
    /** Input currently selected (librtlsdr priv->input: register bits or HF/VHF/UHF band). */
    private var input = 0
    private val i2cAddr = if (isR828D) R828D_I2C_ADDR else R820T_I2C_ADDR
    private val regs = ByteArray(NUM_REGS).also { R82XX_INIT.copyInto(it) }
    private val buf = ByteArray(MAX_I2C_MSG_LEN)
    private var initDone = false

    /** IF the tuner mixes the wanted signal down to, in Hz; set by [setBandwidth]. */
    var intFreqHz = 3_570_000L
        private set
    var hasLock = false
        private set

    // --- register access -------------------------------------------------------------

    private fun write(reg: Int, values: ByteArray, len: Int = values.size) {
        shadowStore(reg, values, len)
        var r = reg
        var pos = 0
        var left = len
        while (left > 0) {
            val size = minOf(left, MAX_I2C_MSG_LEN - 1)
            buf[0] = r.toByte()
            System.arraycopy(values, pos, buf, 1, size)
            bus.i2cWrite(i2cAddr, buf, size + 1)
            r += size; pos += size; left -= size
        }
    }

    private fun writeReg(reg: Int, value: Int) = write(reg, byteArrayOf(value.toByte()))

    private fun writeRegMask(reg: Int, value: Int, mask: Int) {
        val cur = regs[reg - REG_SHADOW_START].toInt() and 0xFF
        writeReg(reg, (cur and mask.inv()) or (value and mask))
    }

    private fun shadowStore(reg: Int, values: ByteArray, len: Int) {
        var r = reg - REG_SHADOW_START
        var l = len
        var src = 0
        if (r < 0) { l += r; src = -r; r = 0 }
        if (l <= 0) return
        if (l > NUM_REGS - r) l = NUM_REGS - r
        System.arraycopy(values, src, regs, r, l)
    }

    /** Reads [len] status registers starting at 0 (bit-reversed on the wire). */
    private fun read(len: Int): IntArray {
        buf[0] = 0
        bus.i2cWrite(i2cAddr, buf, 1)
        val raw = ByteArray(len)
        bus.i2cRead(i2cAddr, raw, len)
        return IntArray(len) { bitRev(raw[it].toInt() and 0xFF) }
    }

    // --- public API ----------------------------------------------------------------------

    override fun init() {
        write(0x05, R82XX_INIT)
        setTvStandard()
        sysFreqSel()
        initDone = true
    }

    override fun standby() {
        if (!initDone) return
        writeReg(0x06, 0xb1); writeReg(0x05, 0x03); writeReg(0x07, 0x3a); writeReg(0x08, 0x40)
        writeReg(0x09, 0xc0); writeReg(0x0a, 0x36); writeReg(0x0c, 0x35); writeReg(0x0f, 0x68)
        writeReg(0x11, 0x03); writeReg(0x17, 0xf4); writeReg(0x19, 0x0c)
    }

    /** Tunes so that [freqHz] lands at [intFreqHz]. */
    override fun setFrequency(freqHz: Long) {
        // Blog V4 / V4L: HF goes through an upconverter at +28.8 MHz.
        val tuned = if (blog != BlogModel.NONE && freqHz < 28_800_000) freqHz + 28_800_000 else freqHz
        val lo = tuned + intFreqHz
        setMux(lo)
        setPll(lo)
        if (!hasLock) throw IOException("R82xx PLL did not lock at $freqHz Hz")
        when (blog) {
            BlogModel.V4 -> {
                // Notch filters off inside the notch bands (AM, FM, DAB), on elsewhere.
                val notchOff = freqHz <= 2_200_000 || freqHz in 85_000_000..112_000_000 ||
                    freqHz in 172_000_000..242_000_000
                writeRegMask(0x17, if (notchOff) 0x00 else 0x08, 0x08)
                val band = if (freqHz <= 28_800_000) HF else if (freqHz < 250_000_000) VHF else UHF
                if (band == HF) {
                    writeRegMask(0x1a, 0x40, 0xc3)
                    writeReg(0x1b, 0x00)
                }
                if (band != input) {
                    input = band
                    val cable2 = if (band == HF) 0x08 else 0x00
                    writeRegMask(0x06, cable2, 0x08)
                    gpio.set(5, cable2 == 0) // upconverter switch on newer batches
                    writeRegMask(0x05, if (band == VHF) 0x40 else 0x00, 0x40) // cable 1 (VHF)
                    writeRegMask(0x05, if (band == UHF) 0x00 else 0x20, 0x20) // air in (UHF)
                }
            }
            BlogModel.V4_LITE -> {
                val band = if (freqHz <= 28_800_000) HF else UHF
                if (band == HF) {
                    writeRegMask(0x1a, 0x40, 0xc3)
                    writeReg(0x1b, 0x00)
                }
                if (band != input) {
                    input = band
                    val cable1 = if (band == HF) 0x40 else 0x00
                    gpio.set(5, cable1 == 0)
                    writeRegMask(0x05, cable1, 0x40)
                }
            }
            BlogModel.NONE -> if (isR828D) {
                // Air-in above 345 MHz, cable-1 input below (librtlsdr).
                val airCable1 = if (freqHz > 345_000_000) 0x00 else 0x60
                if (airCable1 != input) {
                    input = airCable1
                    writeRegMask(0x05, airCable1, 0x60)
                }
            }
        }
    }

    /**
     * Configures the IF filter for [bwHz] and returns the resulting IF frequency
     * (the RTL2832U's IF must then be set to match).
     */
    override fun setBandwidth(bwHz: Int): Long {
        var bw = bwHz
        val reg0a: Int
        var reg0b: Int
        var realBw = 0
        when {
            bw > 7_000_000 -> { reg0a = 0x10; reg0b = 0x0b; intFreqHz = 4_570_000 }
            bw > 6_000_000 -> { reg0a = 0x10; reg0b = 0x2a; intFreqHz = 4_570_000 }
            bw > IF_LOW_PASS_BW[0] + FILT_HP_BW1 + FILT_HP_BW2 -> { reg0a = 0x10; reg0b = 0x6b; intFreqHz = 3_570_000 }
            else -> {
                reg0a = 0x00
                reg0b = 0x80
                var intFreq = 2_300_000L
                if (bw > IF_LOW_PASS_BW[0]) {
                    bw -= FILT_HP_BW2; intFreq += FILT_HP_BW2; realBw += FILT_HP_BW2
                } else {
                    reg0b = reg0b or 0x20
                }
                if (bw > IF_LOW_PASS_BW[0]) {
                    bw -= FILT_HP_BW1; intFreq += FILT_HP_BW1; realBw += FILT_HP_BW1
                } else {
                    reg0b = reg0b or 0x40
                }
                var i = 0
                while (i < IF_LOW_PASS_BW.size && bw <= IF_LOW_PASS_BW[i]) i++
                i--
                reg0b = reg0b or (15 - i)
                realBw += IF_LOW_PASS_BW[i]
                intFreqHz = intFreq - realBw / 2
            }
        }
        writeRegMask(0x0a, reg0a, 0x10)
        writeRegMask(0x0b, reg0b, 0xef)
        return intFreqHz
    }

    /** [gainTenthsDb] null = tuner AGC. */
    override fun setGain(gainTenthsDb: Int?) {
        if (gainTenthsDb == null) {
            writeRegMask(0x05, 0, 0x10)      // LNA auto
            writeRegMask(0x07, 0x10, 0x10)   // mixer auto
            writeRegMask(0x0c, 0x0b, 0x9f)   // fixed VGA 26.5 dB
            return
        }
        writeRegMask(0x05, 0x10, 0x10)       // LNA manual
        writeRegMask(0x07, 0, 0x10)          // mixer manual
        read(4)
        // Fixed VGA 26.5 dB like AGC mode (librtlsdr uses 16.3 dB here, which leaves weak
        // ADS-B signals within a couple of ADC LSBs).
        writeRegMask(0x0c, 0x0b, 0x9f)
        var total = 0
        var lna = 0
        var mix = 0
        for (i in 0 until 15) {
            if (total >= gainTenthsDb) break
            total += LNA_GAIN_STEPS[++lna]
            if (total >= gainTenthsDb) break
            total += MIXER_GAIN_STEPS[++mix]
        }
        writeRegMask(0x05, lna, 0x0f)
        writeRegMask(0x07, mix, 0x0f)
    }

    // --- internals --------------------------------------------------------------------

    private fun setMux(freqHz: Long) {
        require(freqHz >= 650_000_000) { "Tuner RF mux only configured for >= 650 MHz" }
        // freq_ranges[] entry for 650 MHz and up: open_d=0x00, rf_mux_ploy=0x40, tf_c=0x00, xtal caps 0.
        writeRegMask(0x17, 0x00, 0x08)
        writeRegMask(0x1a, 0x40, 0xc3)
        writeReg(0x1b, 0x00)
        writeRegMask(0x10, 0x00, 0x0b) // XTAL_HIGH_CAP_0P
        writeRegMask(0x08, 0x00, 0x3f)
        writeRegMask(0x09, 0x00, 0x3f)
    }

    private fun setPll(freqHz: Long) {
        val vcoMin = 1_770_000L
        val vcoMax = vcoMin * 2
        val freqKhz = (freqHz + 500) / 1000
        val pllRef = xtalHz
        val vcoPowerRef = if (isR828D || blog == BlogModel.V4_LITE) 1 else 2

        writeRegMask(0x10, 0, 0x10)      // refdiv2 = 0
        writeRegMask(0x1a, 0x00, 0x0c)   // pll autotune 128 kHz
        writeRegMask(0x12, 0x80, 0xe0)   // VCO current = 100

        var mixDiv = 2
        var divNum = 0
        while (mixDiv <= 64) {
            if (freqKhz * mixDiv in vcoMin until vcoMax) {
                var divBuf = mixDiv
                while (divBuf > 2) { divBuf = divBuf shr 1; divNum++ }
                break
            }
            mixDiv = mixDiv shl 1
        }

        val data = read(5)
        val vcoFineTune = (data[4] and 0x30) shr 4
        if (vcoFineTune > vcoPowerRef) divNum-- else if (vcoFineTune < vcoPowerRef) divNum++
        writeRegMask(0x10, divNum shl 5, 0xe0)

        val vcoFreq = freqHz * mixDiv
        // vco_freq / (2 * pll_ref) as nint + sdm / 65536, rounded.
        val vcoDiv = (pllRef + 65536L * vcoFreq) / (2 * pllRef)
        val nint = (vcoDiv / 65536).toInt()
        val sdm = (vcoDiv % 65536).toInt()
        if (nint > 128 / vcoPowerRef - 1) throw IOException("No valid PLL values for $freqHz Hz")

        val ni = (nint - 13) / 4
        val si = nint - 4 * ni - 13
        writeReg(0x14, ni + (si shl 6))
        writeRegMask(0x12, if (sdm == 0) 0x08 else 0x00, 0x08) // pw_sdm
        writeReg(0x16, sdm shr 8)
        writeReg(0x15, sdm and 0xff)

        hasLock = false
        for (i in 0 until 2) {
            Thread.sleep(10)
            if (read(3)[2] and 0x40 != 0) { hasLock = true; break }
            if (i == 0) writeRegMask(0x12, 0x60, 0xe0) // no lock: raise VCO current
        }
        if (hasLock) writeRegMask(0x1a, 0x08, 0x08) // pll autotune 8 kHz
    }

    private fun setTvStandard() {
        val filtCalLo = 56_000_000L
        val filtGain = 0x10
        val imgR = 0x00
        val filtQ = 0x10
        val hpCor = 0x6b
        val extEnable = 0x60
        val loopThrough = 0x01
        val ltAtt = 0x00
        val fltExtWidest = 0x00
        val polyfilCur = 0x60

        R82XX_INIT.copyInto(regs)
        writeRegMask(0x0c, 0x00, 0x0f)
        writeRegMask(0x13, VER_NUM, 0x3f)
        writeRegMask(0x1d, 0x00, 0x38)
        Thread.sleep(1)
        intFreqHz = 3_570_000

        var filCalCode = 0
        for (i in 0 until 2) {
            writeRegMask(0x0b, hpCor, 0x60)
            writeRegMask(0x0f, 0x04, 0x04)   // cali clk on
            writeRegMask(0x10, 0x00, 0x03)   // xtal cap 0 pF for PLL
            setPll(filtCalLo)
            if (!hasLock) throw IOException("R82xx PLL did not lock during filter calibration")
            writeRegMask(0x0b, 0x10, 0x10)   // start trigger
            Thread.sleep(1)
            writeRegMask(0x0b, 0x00, 0x10)   // stop trigger
            writeRegMask(0x0f, 0x00, 0x04)   // cali clk off
            filCalCode = read(5)[4] and 0x0f
            if (filCalCode != 0 && filCalCode != 0x0f) break
        }
        if (filCalCode == 0x0f) filCalCode = 0

        writeRegMask(0x0a, filtQ or filCalCode, 0x1f)
        writeRegMask(0x0b, hpCor, 0xef)
        writeRegMask(0x07, imgR, 0x80)
        writeRegMask(0x06, filtGain, 0x30)
        writeRegMask(0x1e, extEnable, 0x60)
        writeRegMask(0x05, loopThrough, 0x80)
        writeRegMask(0x1f, ltAtt, 0x80)
        writeRegMask(0x0f, fltExtWidest, 0x80)
        writeRegMask(0x19, polyfilCur, 0x60)
    }

    /** sysfreq_sel for digital TV / DVB-T, as librtlsdr calls it. */
    private fun sysFreqSel() {
        val mixerTop = 0x24
        val lnaTop = 0xe5
        val cpCur = 0x38
        val divBufCur = 0x30
        val lnaVthL = 0x53
        val mixerVthL = 0x75
        val airCable1In = 0x00
        val cable2In = 0x00
        val lnaDischarge = 14
        val filterCur = 0x40

        writeRegMask(0x1d, lnaTop, 0xc7)
        writeRegMask(0x1c, mixerTop, 0xf8)
        writeReg(0x0d, lnaVthL)
        writeReg(0x0e, mixerVthL)
        input = airCable1In
        writeRegMask(0x05, airCable1In, 0x60)
        writeRegMask(0x06, cable2In, 0x08)
        writeRegMask(0x11, cpCur, 0x38)
        writeRegMask(0x17, divBufCur, 0x30)
        writeRegMask(0x0a, filterCur, 0x60)

        writeRegMask(0x1d, 0, 0x38)          // LNA top lowest
        writeRegMask(0x1c, 0, 0x04)          // normal mode
        writeRegMask(0x06, 0, 0x40)          // pre-detect off
        writeRegMask(0x1a, 0x30, 0x30)       // agc clk 250 Hz
        Thread.sleep(250)
        writeRegMask(0x1d, 0x18, 0x38)       // LNA top = 3
        writeRegMask(0x1c, mixerTop, 0x04)   // discharge mode
        writeRegMask(0x1e, lnaDischarge, 0x1f)
        writeRegMask(0x1a, 0x20, 0x30)       // agc clk 60 Hz
    }

    companion object {
        const val R820T_I2C_ADDR = 0x34
        const val R828D_I2C_ADDR = 0x74
        const val CHECK_ADDR_VAL = 0x69
        const val R828D_XTAL_HZ = 16_000_000L
        private const val HF = 1
        private const val VHF = 2
        private const val UHF = 3

        private const val REG_SHADOW_START = 5
        private const val NUM_REGS = 30
        private const val MAX_I2C_MSG_LEN = 8
        private const val VER_NUM = 49
        private const val FILT_HP_BW1 = 350_000
        private const val FILT_HP_BW2 = 380_000

        /** Gain steps the tuner can produce, tenths of dB. */
        val GAINS = intArrayOf(
            0, 9, 14, 27, 37, 77, 87, 125, 144, 157, 166, 197, 207, 229, 254,
            280, 297, 328, 338, 364, 372, 386, 402, 421, 434, 439, 445, 480, 496,
        )

        private val R82XX_INIT = byteArrayOf(
            0x83.toByte(), 0x32, 0x75,
            0xc0.toByte(), 0x40, 0xd6.toByte(), 0x6c,
            0xf5.toByte(), 0x63, 0x75, 0x68,
            0x6c, 0x83.toByte(), 0x80.toByte(), 0x00,
            0x0f, 0x00, 0xc0.toByte(), 0x30,
            0x48, 0xcc.toByte(), 0x60, 0x00,
            0x54, 0xae.toByte(), 0x4a, 0xc0.toByte(),
        )

        private val IF_LOW_PASS_BW = intArrayOf(
            1_700_000, 1_600_000, 1_550_000, 1_450_000, 1_200_000, 900_000, 700_000, 550_000, 450_000, 350_000,
        )
        private val LNA_GAIN_STEPS = intArrayOf(0, 9, 13, 40, 38, 13, 31, 22, 26, 31, 26, 14, 19, 5, 35, 13)
        private val MIXER_GAIN_STEPS = intArrayOf(0, 5, 10, 10, 19, 9, 10, 25, 17, 10, 8, 16, 13, 6, 3, -8)

        private val BITREV = intArrayOf(0x0, 0x8, 0x4, 0xc, 0x2, 0xa, 0x6, 0xe, 0x1, 0x9, 0x5, 0xd, 0x3, 0xb, 0x7, 0xf)
        private fun bitRev(b: Int) = (BITREV[b and 0xf] shl 4) or BITREV[b shr 4]
    }
}
