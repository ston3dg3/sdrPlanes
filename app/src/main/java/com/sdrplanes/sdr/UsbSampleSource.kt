package com.sdrplanes.sdr

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import com.sdrplanes.sdr.usb.Rtl2832
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import java.io.IOException

/**
 * Built-in USB backend: drives an RTL2832U dongle on this phone directly (no driver app).
 * USB permission must already be granted (see [UsbDongle]).
 */
class UsbSampleSource(private val context: Context) : SampleSource {
    override val description get() = "USB dongle"

    override suspend fun stream(
        config: TunerConfig,
        onInfo: (TunerInfo) -> Unit,
        onSamples: (ByteArray, Int) -> Unit,
    ) {
        val usb = context.getSystemService(UsbManager::class.java)
        val device = UsbDongle.find(context) ?: throw IOException("No RTL-SDR dongle connected")
        if (!usb.hasPermission(device)) throw IOException("No USB permission for the dongle – press start again")
        val conn = usb.openDevice(device) ?: throw IOException("Cannot open the dongle (in use by another app?)")
        val rtl = Rtl2832(device, conn, config.ppm)
        val job = currentCoroutineContext().job
        try {
            rtl.open()
            rtl.setSampleRate(config.sampleRate)
            rtl.setCenterFrequency(config.frequencyHz)
            rtl.setGain(config.gainTenthsDb)
            rtl.setBiasTee(config.biasTee)
            rtl.resetBuffer()
            if (rtl.i2cRecoveries > 0) Log.i(TAG, "tuner init needed ${rtl.i2cRecoveries} I2C retries")
            onInfo(TunerInfo(rtl.tunerName, rtl.gainCount))
            rtl.stream { buf, len ->
                // Blocking USB loop: poll for cancellation once per transfer.
                if (!job.isActive) rtl.stop() else onSamples(buf, len)
            }
            currentCoroutineContext().ensureActive()
        } finally {
            runCatching { if (config.biasTee) rtl.setBiasTee(false) }
            rtl.close()
        }
    }
}

private const val TAG = "UsbSampleSource"

object UsbDongle {
    fun find(context: Context): UsbDevice? =
        context.getSystemService(UsbManager::class.java).deviceList.values.firstOrNull(Rtl2832::isSupported)
}
