package com.sdrplanes.ui

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/** Asks Android for access to a USB device; [request]'s callback gets the user's answer. */
class UsbPermissionRequester internal constructor(private val context: Context) {
    internal var pending: ((Boolean) -> Unit)? = null

    fun request(device: UsbDevice, onResult: (granted: Boolean) -> Unit) {
        val usb = context.getSystemService(UsbManager::class.java)
        if (usb.hasPermission(device)) {
            onResult(true)
            return
        }
        pending = onResult
        // Mutable: the system adds the device and the EXTRA_PERMISSION_GRANTED result.
        val pi = PendingIntent.getBroadcast(
            context, 0, Intent(ACTION).setPackage(context.packageName), PendingIntent.FLAG_MUTABLE,
        )
        usb.requestPermission(device, pi)
    }

    companion object {
        internal const val ACTION = "com.sdrplanes.USB_PERMISSION"
    }
}

@Composable
fun rememberUsbPermissionRequester(): UsbPermissionRequester {
    val context = LocalContext.current
    val requester = remember { UsbPermissionRequester(context.applicationContext) }
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                requester.pending?.invoke(granted)
                requester.pending = null
            }
        }
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(UsbPermissionRequester.ACTION), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose { context.unregisterReceiver(receiver) }
    }
    return requester
}
