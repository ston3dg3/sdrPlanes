package com.sdrplanes.ui

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import com.sdrplanes.decode.LatLon
import com.sdrplanes.service.ReceiverState

/** While [granted], keeps [ReceiverState.receiverPosition] updated from the platform location. */
@SuppressLint("MissingPermission")
@Composable
fun ReceiverLocationEffect(granted: Boolean) {
    val context = LocalContext.current
    DisposableEffect(granted) {
        if (!granted) return@DisposableEffect onDispose { }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val candidates = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
        }
        val enabled = candidates.filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }

        fun publish(l: Location) = ReceiverState.setReceiverPosition(LatLon(l.latitude, l.longitude))
        enabled.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
            ?.let(::publish)

        val listener = LocationListener { publish(it) }
        enabled.firstOrNull()?.let { lm.requestLocationUpdates(it, 30_000L, 100f, listener, Looper.getMainLooper()) }
        onDispose { lm.removeUpdates(listener) }
    }
}
