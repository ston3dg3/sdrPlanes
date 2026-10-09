package com.sdrplanes

import android.app.Application
import org.maplibre.android.MapLibre

class SdrPlanesApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Must run before any MapView is created. OpenFreeMap tiles need no API key.
        MapLibre.getInstance(this)
    }
}
