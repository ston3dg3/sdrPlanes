package com.sdrplanes.service

import android.content.Context
import androidx.core.content.edit
import com.sdrplanes.sdr.RtlSdrDriver
import com.sdrplanes.sdr.TunerConfig
import com.sdrplanes.ui.theme.ThemeMode

enum class SourceMode { USB, DRIVER_APP, NETWORK }

/**
 * User settings. USB drives the dongle directly; DRIVER_APP goes through the
 * "RTL-SDR driver" app; NETWORK connects to any rtl_tcp server, e.g. `rtl_tcp -a 0.0.0.0`
 * on a PC. From the emulator use `adb reverse tcp:1234 tcp:1234` and host 127.0.0.1.
 */
data class Settings(
    val mode: SourceMode = SourceMode.USB,
    val host: String = "127.0.0.1",
    val port: Int = RtlSdrDriver.PORT,
    /** Tenths of dB; null = tuner AGC. */
    val gainTenthsDb: Int? = 496,
    val ppm: Int = 0,
    val sampleRate: Int = 2_400_000,
    val biasTee: Boolean = false,
    val theme: ThemeMode = ThemeMode.SYSTEM,
) {
    val tunerConfig
        get() = TunerConfig(gainTenthsDb = gainTenthsDb, ppm = ppm, sampleRate = sampleRate, biasTee = biasTee)

    companion object {
        private const val PREFS = "settings"
        val SAMPLE_RATES = listOf(2_000_000, 2_400_000)

        fun load(context: Context): Settings {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val d = Settings()
            // Versions before the built-in USB driver always stored DRIVER_APP: move them to USB.
            val legacy = !p.contains("rate")
            return Settings(
                mode = runCatching { SourceMode.valueOf(p.getString("mode", d.mode.name)!!) }.getOrDefault(d.mode)
                    .let { if (legacy && it == SourceMode.DRIVER_APP) SourceMode.USB else it },
                host = p.getString("host", d.host)!!,
                port = p.getInt("port", d.port),
                gainTenthsDb = p.getInt("gain", d.gainTenthsDb!!).takeIf { it >= 0 },
                ppm = p.getInt("ppm", d.ppm),
                sampleRate = p.getInt("rate", d.sampleRate).takeIf { it in SAMPLE_RATES } ?: d.sampleRate,
                biasTee = p.getBoolean("biasTee", d.biasTee),
                theme = runCatching { ThemeMode.valueOf(p.getString("theme", d.theme.name)!!) }.getOrDefault(d.theme),
            )
        }

        fun save(context: Context, s: Settings) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
                putString("mode", s.mode.name)
                putString("host", s.host)
                putInt("port", s.port)
                putInt("gain", s.gainTenthsDb ?: -1)
                putInt("ppm", s.ppm)
                putInt("rate", s.sampleRate)
                putBoolean("biasTee", s.biasTee)
                putString("theme", s.theme.name)
            }
        }
    }
}
