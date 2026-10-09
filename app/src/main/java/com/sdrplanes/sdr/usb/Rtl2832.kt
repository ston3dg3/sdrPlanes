package com.sdrplanes.sdr.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbRequest
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.roundToLong

/**
 * Realtek RTL2832U in SDR mode, driven directly over the Android USB host API.
 * Ported from osmocom librtlsdr's librtlsdr.c (GPL-2.0+). Tuners: R820T/R828D (incl. RTL-SDR
 * Blog V4/V4L), E4000, FC0013, FC0012, FC2580 – only the R820T is tested on hardware.
 *
 * Usage: [open], then [setSampleRate], [setCenterFrequency], [setGain], [resetBuffer],
 * and [stream]; [close] when done. Not thread-safe, except that [stop] may be called from
 * any thread to make [stream] return.
 */
class Rtl2832(
    private val device: UsbDevice,
    private val conn: UsbDeviceConnection,
    ppm: Int = 0,
) : I2cBus, Closeable {
    private val iface: UsbInterface = device.getInterface(0)
    private val bulkIn: UsbEndpoint = (0 until iface.endpointCount).map(iface::getEndpoint)
        .firstOrNull { it.direction == UsbConstants.USB_DIR_IN && it.type == UsbConstants.USB_ENDPOINT_XFER_BULK }
        ?: throw IOException("RTL2832U bulk IN endpoint not found")

    private val ppm = ppm
    private val rtlXtalHz = correct(RTL_XTAL_HZ, ppm)
    private var tuner: Tuner? = null
    private var centerHz = 0L

    var tunerName = "unknown"
        private set
    var sampleRate = 0
        private set
    val gainCount: Int get() = tuner?.gains?.size ?: 0
    @Volatile private var stopped = false
    private var closed = false

    // --- register access (librtlsdr rtlsdr_*_reg) -------------------------------------

    /** Control transfer with a few retries: the tuner's I2C occasionally NAKs while settling. */
    private fun control(type: Int, value: Int, index: Int, data: ByteArray, len: Int, what: () -> String) {
        var r = -1
        for (attempt in 0 until CTRL_RETRIES) {
            r = conn.controlTransfer(type, 0, value, index, data, len, CTRL_TIMEOUT_MS)
            if (r == len) return
            Thread.sleep(2L shl attempt)
        }
        throw IOException("USB ${what()} failed ($r)")
    }

    private fun readArray(block: Int, addr: Int, data: ByteArray, len: Int) =
        control(CTRL_IN, addr, block shl 8, data, len) { "read block $block addr 0x${addr.toString(16)}" }

    private fun writeArray(block: Int, addr: Int, data: ByteArray, len: Int) =
        control(CTRL_OUT, addr, (block shl 8) or 0x10, data, len) {
            "write block $block addr 0x${addr.toString(16)} data ${data.take(len).joinToString(" ") { "%02x".format(it) }}"
        }

    private fun readReg(block: Int, addr: Int, len: Int): Int {
        val d = ByteArray(2)
        readArray(block, addr, d, len)
        return ((d[1].toInt() and 0xFF) shl 8) or (d[0].toInt() and 0xFF)
    }

    private fun writeReg(block: Int, addr: Int, value: Int, len: Int) {
        val d = if (len == 1) byteArrayOf(value.toByte()) else byteArrayOf((value shr 8).toByte(), value.toByte())
        writeArray(block, addr, d, len)
    }

    private fun demodWriteReg(page: Int, addr: Int, value: Int, len: Int) {
        val d = if (len == 1) byteArrayOf(value.toByte()) else byteArrayOf((value shr 8).toByte(), value.toByte())
        val r = conn.controlTransfer(CTRL_OUT, 0, (addr shl 8) or 0x20, 0x10 or page, d, len, CTRL_TIMEOUT_MS)
        if (r != len) throw IOException("USB demod write failed (page $page addr 0x${addr.toString(16)}: $r)")
        demodReadReg(0x0a, 0x01, 1)
    }

    private fun demodReadReg(page: Int, addr: Int, len: Int): Int {
        val d = ByteArray(2)
        val r = conn.controlTransfer(CTRL_IN, 0, (addr shl 8) or 0x20, page, d, len, CTRL_TIMEOUT_MS)
        if (r != len) throw IOException("USB demod read failed: $r")
        return ((d[1].toInt() and 0xFF) shl 8) or (d[0].toInt() and 0xFF)
    }

    private fun setI2cRepeater(on: Boolean) = demodWriteReg(1, 0x01, if (on) 0x18 else 0x10, 1)

    override fun i2cWrite(addr: Int, data: ByteArray, len: Int) = i2cRetry { writeArray(BLOCK_IIC, addr, data, len) }
    override fun i2cRead(addr: Int, data: ByteArray, len: Int) = i2cRetry { readArray(BLOCK_IIC, addr, data, len) }

    /**
     * The tuner's I2C link through the repeater sometimes stalls for tens of ms (seen on
     * R820T sticks on phones). librtlsdr ignores such errors and leaves the tuner half
     * configured; instead re-arm the repeater and retry with a growing pause.
     */
    private inline fun i2cRetry(op: () -> Unit) {
        var last: IOException? = null
        for (attempt in 0 until I2C_RETRIES) {
            try {
                op()
                if (attempt > 0) i2cRecoveries++
                return
            } catch (e: IOException) {
                last = e
                Thread.sleep(20L * (attempt + 1))
                runCatching { setI2cRepeater(true) }
            }
        }
        throw last!!
    }

    /** I2C transfers that only succeeded after a retry (diagnostics). */
    var i2cRecoveries = 0
        private set

    private fun i2cReadReg(addr: Int, reg: Int): Int? = runCatching {
        writeArray(BLOCK_IIC, addr, byteArrayOf(reg.toByte()), 1)
        val d = ByteArray(1)
        readArray(BLOCK_IIC, addr, d, 1)
        d[0].toInt() and 0xFF
    }.getOrNull()

    private inline fun <T> withRepeater(block: () -> T): T {
        setI2cRepeater(true)
        try { return block() } finally { setI2cRepeater(false) }
    }

    // --- lifecycle --------------------------------------------------------------------

    fun open() {
        if (!conn.claimInterface(iface, true)) throw IOException("Cannot claim the dongle's USB interface")
        // Dummy write; librtlsdr resets the device if this fails.
        writeReg(BLOCK_USB, USB_SYSCTL, 0x09, 1)
        initBaseband()

        // RTL-SDR Blog V4 / V4 Lite identify themselves in the USB strings.
        val blog = when {
            device.manufacturerName != "RTLSDRBlog" -> BlogModel.NONE
            device.productName == "Blog V4" -> BlogModel.V4
            device.productName == "Blog V4L" -> BlogModel.V4_LITE
            else -> BlogModel.NONE
        }
        val gpio = Gpio { pin, on -> setGpio(pin, on) }

        setI2cRepeater(true)
        try {
            val t = probeTuner(blog, gpio)
            if (t.lowIf) {
                demodWriteReg(1, 0xb1, 0x1a, 1)   // disable zero-IF mode
                demodWriteReg(0, 0x08, 0x4d, 1)   // only in-phase ADC input
                setIfFrequency(R82XX_IF_HZ)
                demodWriteReg(1, 0x15, 0x01, 1)   // spectrum inversion
            }
            t.init()
            tuner = t
            tunerName = t.name
        } finally {
            setI2cRepeater(false)
        }
        setSampleFreqCorrection()
    }

    /** librtlsdr's probe order (the FC0012/FC2580 only answer after a GPIO 4 reset). */
    private fun probeTuner(blog: BlogModel, gpio: Gpio): Tuner {
        if (i2cReadReg(E4kTuner.I2C_ADDR, E4kTuner.CHECK_ADDR) == E4kTuner.CHECK_VAL) {
            return E4kTuner(this, rtlXtalHz)
        }
        if (i2cReadReg(Fc0013Tuner.I2C_ADDR, 0) == Fc0013Tuner.CHECK_VAL) {
            return Fc0013Tuner(this, rtlXtalHz)
        }
        if (i2cReadReg(R82xxTuner.R820T_I2C_ADDR, 0) == R82xxTuner.CHECK_ADDR_VAL) {
            return R82xxTuner(this, isR828D = false, xtalHz = rtlXtalHz,
                blog = if (blog == BlogModel.V4_LITE) blog else BlogModel.NONE, gpio = gpio)
        }
        if (i2cReadReg(R82xxTuner.R828D_I2C_ADDR, 0) == R82xxTuner.CHECK_ADDR_VAL) {
            // The Blog V4 clocks its R828D from the 28.8 MHz RTL crystal; others use 16 MHz.
            val v4 = blog == BlogModel.V4
            return R82xxTuner(this, isR828D = true,
                xtalHz = if (v4) rtlXtalHz else correct(R82xxTuner.R828D_XTAL_HZ, ppm),
                blog = if (v4) blog else BlogModel.NONE, gpio = gpio)
        }
        setGpio(4, true)  // reset tuner before probing
        setGpio(4, false)
        if ((i2cReadReg(Fc2580Tuner.I2C_ADDR, Fc2580Tuner.CHECK_ADDR) ?: -1) and 0x7f == Fc2580Tuner.CHECK_VAL) {
            return Fc2580Tuner(this)
        }
        if (i2cReadReg(Fc0012Tuner.I2C_ADDR, 0) == Fc0012Tuner.CHECK_VAL) {
            setGpioOutput(6)
            return Fc0012Tuner(this, rtlXtalHz, gpio)
        }
        throw UnsupportedHardwareException("No supported tuner found on this dongle")
    }

    private fun initBaseband() {
        writeReg(BLOCK_USB, USB_SYSCTL, 0x09, 1)
        writeReg(BLOCK_USB, USB_EPA_MAXPKT, 0x0002, 2)
        writeReg(BLOCK_USB, USB_EPA_CTL, 0x1002, 2)
        writeReg(BLOCK_SYS, DEMOD_CTL_1, 0x22, 1)   // power on demod
        writeReg(BLOCK_SYS, DEMOD_CTL, 0xe8, 1)
        demodWriteReg(1, 0x01, 0x14, 1)             // soft reset
        demodWriteReg(1, 0x01, 0x10, 1)
        demodWriteReg(1, 0x15, 0x00, 1)             // no spectrum inversion / adjacent channel rejection
        demodWriteReg(1, 0x16, 0x0000, 2)
        for (i in 0 until 6) demodWriteReg(1, 0x16 + i, 0x00, 1) // clear DDC shift and IF registers
        setFir()
        demodWriteReg(0, 0x19, 0x05, 1)             // SDR mode, DAGC off
        demodWriteReg(1, 0x93, 0xf0, 1)             // FSM state-holding register
        demodWriteReg(1, 0x94, 0x0f, 1)
        demodWriteReg(1, 0x11, 0x00, 1)             // AGC off
        demodWriteReg(1, 0x04, 0x00, 1)             // RF/IF AGC loop off
        demodWriteReg(0, 0x61, 0x60, 1)             // PID filter off
        demodWriteReg(0, 0x06, 0x80, 1)             // default ADC I/Q datapath
        demodWriteReg(1, 0xb1, 0x1b, 1)             // zero-IF, DC cancellation, IQ comp
        demodWriteReg(0, 0x0d, 0x83, 1)             // no 4.096 MHz clock output on TP_CK0
    }

    private fun setFir() {
        val fir = ByteArray(20)
        for (i in 0 until 8) fir[i] = FIR_DEFAULT[i].toByte()
        var i = 0
        while (i < 8) {
            val v0 = FIR_DEFAULT[8 + i]
            val v1 = FIR_DEFAULT[8 + i + 1]
            fir[8 + i * 3 / 2] = (v0 shr 4).toByte()
            fir[8 + i * 3 / 2 + 1] = ((v0 shl 4) or ((v1 shr 8) and 0x0f)).toByte()
            fir[8 + i * 3 / 2 + 2] = v1.toByte()
            i += 2
        }
        for (k in fir.indices) demodWriteReg(1, 0x1c + k, fir[k].toInt() and 0xFF, 1)
    }

    private fun setIfFrequency(freqHz: Long) {
        val ifFreq = -((freqHz * (1L shl 22)) / rtlXtalHz)
        demodWriteReg(1, 0x19, ((ifFreq shr 16) and 0x3f).toInt(), 1)
        demodWriteReg(1, 0x1a, ((ifFreq shr 8) and 0xff).toInt(), 1)
        demodWriteReg(1, 0x1b, (ifFreq and 0xff).toInt(), 1)
    }

    private fun setSampleFreqCorrection() {
        val offs = (-ppm * (1 shl 24) / 1_000_000)
        demodWriteReg(1, 0x3f, offs and 0xff, 1)
        demodWriteReg(1, 0x3e, (offs shr 8) and 0x3f, 1)
    }

    fun setSampleRate(rate: Int) {
        require(rate in 900_001..3_200_000) { "Unsupported sample rate $rate" }
        // The resampler runs off the nominal crystal; ppm is applied by setSampleFreqCorrection().
        val ratio = ((RTL_XTAL_HZ * (1L shl 22)) / rate).toInt() and 0x0ffffffc
        val realRatio = ratio.toLong() or ((ratio.toLong() and 0x08000000) shl 1)
        sampleRate = ((RTL_XTAL_HZ * (1L shl 22)).toDouble() / realRatio).roundToLong().toInt()

        tuner?.let { t ->
            withRepeater {
                val ifHz = t.setBandwidth(sampleRate)
                // A low-IF tuner moves its IF with the filter: follow it and retune.
                if (t.lowIf) {
                    setIfFrequency(ifHz)
                    if (centerHz > 0) t.setFrequency(centerHz)
                }
            }
        }
        demodWriteReg(1, 0x9f, ratio shr 16, 2)
        demodWriteReg(1, 0xa1, ratio and 0xffff, 2)
        setSampleFreqCorrection()
        demodWriteReg(1, 0x01, 0x14, 1) // soft reset
        demodWriteReg(1, 0x01, 0x10, 1)
    }

    fun setCenterFrequency(freqHz: Long) {
        val t = tuner ?: throw IOException("Tuner not initialised")
        withRepeater { t.setFrequency(freqHz) }
        centerHz = freqHz
    }

    /** [gainTenthsDb] null = tuner AGC. */
    fun setGain(gainTenthsDb: Int?) {
        val t = tuner ?: throw IOException("Tuner not initialised")
        withRepeater { t.setGain(gainTenthsDb) }
    }

    /** Powers an LNA or filter through the coax (RTL-SDR Blog dongles; GPIO 0). */
    fun setBiasTee(on: Boolean) = setGpio(0, on)

    private fun setGpioOutput(pin: Int) {
        val bit = 1 shl pin
        writeReg(BLOCK_SYS, GPD, readReg(BLOCK_SYS, GPD, 1) and bit.inv(), 1)
        writeReg(BLOCK_SYS, GPOE, readReg(BLOCK_SYS, GPOE, 1) or bit, 1)
    }

    /** Drives GPIO [pin] as an output (librtlsdr rtlsdr_set_bias_tee_gpio). */
    private fun setGpio(pin: Int, on: Boolean) {
        setGpioOutput(pin)
        val bit = 1 shl pin
        val gpo = readReg(BLOCK_SYS, GPO, 1)
        writeReg(BLOCK_SYS, GPO, if (on) gpo or bit else gpo and bit.inv(), 1)
    }

    fun resetBuffer() {
        writeReg(BLOCK_USB, USB_EPA_CTL, 0x1002, 2)
        writeReg(BLOCK_USB, USB_EPA_CTL, 0x0000, 2)
    }

    /**
     * Streams 8-bit unsigned IQ until [stop] is called or a transfer fails. Keeps
     * [QUEUE_DEPTH] bulk requests in flight so the dongle's FIFO never overflows.
     */
    fun stream(onSamples: (ByteArray, Int) -> Unit) {
        val requests = List(QUEUE_DEPTH) {
            UsbRequest().apply {
                if (!initialize(conn, bulkIn)) throw IOException("Cannot initialise USB request")
                clientData = ByteBuffer.allocateDirect(TRANSFER_SIZE)
            }
        }
        val copy = ByteArray(TRANSFER_SIZE)
        try {
            for (r in requests) queue(r)
            while (!stopped) {
                val done = conn.requestWait(READ_TIMEOUT_MS)
                    ?: if (stopped) break else throw IOException("USB transfer failed (dongle unplugged?)")
                if (stopped) break
                val bb = done.clientData as ByteBuffer
                val n = bb.position()
                if (n > 0) {
                    bb.flip()
                    bb.get(copy, 0, n)
                    onSamples(copy, n and 1.inv())
                }
                queue(done)
            }
        } catch (e: java.util.concurrent.TimeoutException) {
            if (!stopped) throw IOException("No data from the dongle for ${READ_TIMEOUT_MS / 1000} s", e)
        } finally {
            for (r in requests) runCatching { r.cancel(); r.close() }
        }
    }

    private fun queue(r: UsbRequest) {
        val bb = r.clientData as ByteBuffer
        bb.clear()
        if (!r.queue(bb)) throw IOException("Cannot queue USB transfer (dongle unplugged?)")
    }

    fun stop() {
        stopped = true
    }

    override fun close() {
        if (closed) return
        closed = true
        stopped = true
        runCatching {
            tuner?.let { t -> withRepeater { t.standby() } }
            writeReg(BLOCK_SYS, DEMOD_CTL, 0x20, 1) // power off demod and ADCs
        }
        runCatching { conn.releaseInterface(iface) }
        conn.close()
    }

    companion object {
        /** RTL2832U dongles: generic 0BDA:2838 / 0BDA:2832. */
        val SUPPORTED_IDS = setOf(0x0bda to 0x2838, 0x0bda to 0x2832)
        fun isSupported(d: UsbDevice) = (d.vendorId to d.productId) in SUPPORTED_IDS

        private const val RTL_XTAL_HZ = 28_800_000L
        private const val R82XX_IF_HZ = 3_570_000L

        private const val CTRL_IN = UsbConstants.USB_TYPE_VENDOR or UsbConstants.USB_DIR_IN
        private const val CTRL_OUT = UsbConstants.USB_TYPE_VENDOR or UsbConstants.USB_DIR_OUT
        private const val CTRL_TIMEOUT_MS = 300
        private const val CTRL_RETRIES = 3
        private const val I2C_RETRIES = 6
        private const val READ_TIMEOUT_MS = 5_000L
        private const val TRANSFER_SIZE = 16 * 16384
        private const val QUEUE_DEPTH = 12

        private const val BLOCK_USB = 1
        private const val BLOCK_SYS = 2
        private const val BLOCK_IIC = 6

        private const val USB_SYSCTL = 0x2000
        private const val USB_EPA_CTL = 0x2148
        private const val USB_EPA_MAXPKT = 0x2158
        private const val DEMOD_CTL = 0x3000
        private const val GPO = 0x3001
        private const val GPOE = 0x3003
        private const val GPD = 0x3004
        private const val DEMOD_CTL_1 = 0x300b

        private val FIR_DEFAULT = intArrayOf(
            -54, -36, -41, -40, -32, -14, 14, 53,
            101, 156, 215, 273, 327, 372, 404, 421,
        )

        private fun correct(xtal: Long, ppm: Int) = xtal + xtal * ppm / 1_000_000
    }
}
