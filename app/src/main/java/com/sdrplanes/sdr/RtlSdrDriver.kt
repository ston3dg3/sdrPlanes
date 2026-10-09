package com.sdrplanes.sdr

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri

/**
 * The "RTL-SDR driver" app (marto.rtl_tcp_andro) owns the USB dongle and exposes it as an
 * rtl_tcp server on localhost. It is started with an `iqsrc://` intent whose data is an
 * rtl_tcp style argument string; it asks for USB permission, then returns RESULT_OK once
 * the server is listening. With `-n 1` it stops after our single connection ends.
 */
object RtlSdrDriver {
    const val PACKAGE = "marto.rtl_tcp_andro"
    const val HOST = "127.0.0.1"
    const val PORT = 1234

    fun startIntent(config: TunerConfig, port: Int = PORT): Intent =
        Intent(Intent.ACTION_VIEW).setData(
            Uri.parse("iqsrc://-a $HOST -p $port -n 1 -f ${config.frequencyHz} -s ${config.sampleRate}")
        )

    fun isInstalled(context: Context): Boolean =
        context.packageManager.queryIntentActivities(startIntent(TunerConfig()), PackageManager.MATCH_DEFAULT_ONLY)
            .isNotEmpty()

    fun storeIntent(): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$PACKAGE"))

    fun storeWebIntent(): Intent =
        Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$PACKAGE"))

    /** Null on success, otherwise a human-readable error from the driver's result. */
    fun resultError(resultCode: Int, data: Intent?): String? {
        if (resultCode == Activity.RESULT_OK) return null
        val id = data?.getIntExtra("marto.rtl_tcp_andro.RtlTcpExceptionId", -1) ?: -1
        val detail = data?.getStringExtra("detailed_exception_message")
        return when {
            detail != null -> "SDR driver: $detail"
            id >= 0 -> "SDR driver error #$id (is the dongle plugged in and USB permission granted?)"
            else -> "SDR driver was cancelled"
        }
    }
}
