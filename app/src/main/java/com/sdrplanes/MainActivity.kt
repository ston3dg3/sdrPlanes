package com.sdrplanes

import android.content.Intent
import android.graphics.Color
import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sdrplanes.service.Settings
import com.sdrplanes.ui.MainScreen
import com.sdrplanes.ui.theme.SdrPlanesTheme
import com.sdrplanes.ui.theme.hud

class MainActivity : ComponentActivity() {
    private val launchedByUsb = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launchedByUsb.value = intent.isUsbAttach()
        var settings by mutableStateOf(Settings.load(this))
        setContent {
            SdrPlanesTheme(settings.theme) {
                // Transparent bars; icon colour follows the active palette.
                val dark = hud.isDark
                LaunchedEffect(dark) {
                    val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
                    else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                }
                MainScreen(
                    settings = settings,
                    onSettingsChange = {
                        settings = it
                        Settings.save(this, it)
                    },
                    autoStart = launchedByUsb.value,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.isUsbAttach()) launchedByUsb.value = true
    }

    private fun Intent?.isUsbAttach() = this?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED
}
