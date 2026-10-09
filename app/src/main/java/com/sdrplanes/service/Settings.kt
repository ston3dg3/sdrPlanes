package com.sdrplanes.service

import android.content.Context
import androidx.core.content.edit
import com.sdrplanes.sdr.RtlSdrDriver
import com.sdrplanes.sdr.TunerConfig

enum class SourceMode { DRIVER_APP, NETWORK }

/**
 * User settings. NETWORK mode connects to any rtl_tcp server, e.g. `rtl_tcp -a 0.0.0.0`
 * on a PC. From the emulator use `adb reverse tcp:1234 tcp:1234` and host 127.0.0.1.
 */
data class Settings(
    val mode: SourceMode = SourceMode.DRIVER_APP,
    val host: String = "127.0.0.1",
    val port: Int = RtlSdrDriver.PORT,
    /** Tenths of dB; null = tuner AGC. */
    val gainTenthsDb: Int? = 496,
    val ppm: Int = 0,
) {
    val tunerConfig get() = TunerConfig(gainTenthsDb = gainTenthsDb, ppm = ppm)

    companion object {
        private const val PREFS = "settings"

        fun load(context: Context): Settings {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val d = Settings()
            return Settings(
                mode = runCatching { SourceMode.valueOf(p.getString("mode", d.mode.name)!!) }.getOrDefault(d.mode),
                host = p.getString("host", d.host)!!,
                port = p.getInt("port", d.port),
                gainTenthsDb = p.getInt("gain", d.gainTenthsDb!!).takeIf { it >= 0 },
                ppm = p.getInt("ppm", d.ppm),
            )
        }

        fun save(context: Context, s: Settings) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
                putString("mode", s.mode.name)
                putString("host", s.host)
                putInt("port", s.port)
                putInt("gain", s.gainTenthsDb ?: -1)
                putInt("ppm", s.ppm)
            }
        }
    }
}
