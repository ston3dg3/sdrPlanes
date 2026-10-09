package com.sdrplanes

import android.content.Intent
import android.graphics.Color
import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.sdrplanes.ui.MainScreen
import com.sdrplanes.ui.theme.SdrPlanesTheme

class MainActivity : ComponentActivity() {
    private val launchedByUsb = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Always-dark UI: light status/navigation bar icons on transparent bars.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        launchedByUsb.value = intent.isUsbAttach()
        setContent {
            SdrPlanesTheme {
                MainScreen(autoStart = launchedByUsb.value)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.isUsbAttach()) launchedByUsb.value = true
    }

    private fun Intent?.isUsbAttach() = this?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED
}
