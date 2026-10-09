package com.sdrplanes.sdr.usb

/**
 * A tuner chip behind the RTL2832U's I2C repeater (the repeater is switched on by the
 * caller). Ported from librtlsdr's tuner interface.
 */
interface Tuner {
    val name: String

    /** Gains the tuner supports, tenths of dB, ascending. */
    val gains: IntArray

    /**
     * True for tuners that output a low IF (R82xx); the RTL2832U then has to downconvert
     * it ([setBandwidth] returns the IF). Zero-IF tuners deliver baseband I/Q directly.
     */
    val lowIf: Boolean get() = false

    fun init()
    fun standby()
    fun setFrequency(freqHz: Long)

    /** Sets the IF filters for [bwHz]; returns the IF frequency for low-IF tuners, else 0. */
    fun setBandwidth(bwHz: Int): Long

    /** [gainTenthsDb] null = tuner AGC; other values snap to the nearest supported gain. */
    fun setGain(gainTenthsDb: Int?)

    companion object {
        fun nearest(gains: IntArray, g: Int) = gains.minBy { kotlin.math.abs(it - g) }
    }
}

/** The dongle can't do the job at all (unknown tuner, frequency out of range): don't retry. */
class UnsupportedHardwareException(message: String) : java.io.IOException(message)

/** Access to the RTL2832U's GPIOs, which some dongles use for tuner reset or band switching. */
fun interface Gpio {
    fun set(pin: Int, on: Boolean)
}
