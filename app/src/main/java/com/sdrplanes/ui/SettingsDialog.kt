package com.sdrplanes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.sdrplanes.service.Settings
import com.sdrplanes.service.SourceMode
import com.sdrplanes.ui.theme.NightSky
import com.sdrplanes.ui.theme.Phosphor
import com.sdrplanes.ui.theme.PhosphorDim
import com.sdrplanes.ui.theme.Plum
import com.sdrplanes.ui.theme.RadarTeal
import com.sdrplanes.ui.theme.Sunset
import kotlin.math.roundToInt

/** R820T gain table (tenths of dB), as reported by rtl_test for this dongle. */
private val R820T_GAINS = intArrayOf(
    0, 9, 14, 27, 37, 77, 87, 125, 144, 157, 166, 197, 207, 229, 254,
    280, 297, 328, 338, 364, 372, 386, 402, 421, 434, 439, 445, 480, 496,
)

@Composable
fun SettingsDialog(initial: Settings, onDismiss: () -> Unit, onSave: (Settings) -> Unit) {
    var mode by remember { mutableStateOf(initial.mode) }
    var host by remember { mutableStateOf(initial.host) }
    var port by remember { mutableStateOf(initial.port.toString()) }
    var autoGain by remember { mutableStateOf(initial.gainTenthsDb == null) }
    var gainIdx by remember {
        val g = initial.gainTenthsDb ?: R820T_GAINS.last()
        mutableFloatStateOf(R820T_GAINS.indices.minBy { kotlin.math.abs(R820T_GAINS[it] - g) }.toFloat())
    }
    var ppm by remember { mutableStateOf(initial.ppm.toString()) }

    HudDialog(
        title = "Receiver settings",
        onDismiss = onDismiss,
        actions = {
            HudTextButton("Cancel", onClick = onDismiss, accent = RadarTeal)
            HudTextButton("Save", filled = true, onClick = {
                onSave(
                    Settings(
                        mode = mode,
                        host = host.ifBlank { initial.host },
                        port = port.toIntOrNull()?.takeIf { it in 1..65535 } ?: initial.port,
                        gainTenthsDb = if (autoGain) null else R820T_GAINS[gainIdx.roundToInt()],
                        ppm = ppm.toIntOrNull() ?: 0,
                    )
                )
            })
        },
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            HudLabel("Signal source")
            ModeOption("RTL-SDR driver app", "USB dongle on this phone", mode == SourceMode.DRIVER_APP) {
                mode = SourceMode.DRIVER_APP
            }
            ModeOption("Network rtl_tcp server", "PC, Raspberry Pi or emulator", mode == SourceMode.NETWORK) {
                mode = SourceMode.NETWORK
            }
            if (mode == SourceMode.NETWORK) {
                RetroField(host, { host = it.trim() }, "Host (emulator: 127.0.0.1 + adb reverse)")
            }
            RetroField(port, { port = it.filter(Char::isDigit) }, "Port", KeyboardType.Number)

            Spacer(Modifier.size(2.dp))
            HudLabel("Tuner")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Auto gain", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(
                    autoGain, { autoGain = it },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = NightSky, checkedTrackColor = Sunset,
                        uncheckedThumbColor = RadarTeal, uncheckedTrackColor = NightSky, uncheckedBorderColor = RadarTeal,
                    ),
                )
            }
            if (!autoGain) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Gain", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(
                        "%.1f dB".format(R820T_GAINS[gainIdx.roundToInt()] / 10.0),
                        style = MaterialTheme.typography.bodyLarge, color = Sunset,
                    )
                }
                Slider(
                    value = gainIdx, onValueChange = { gainIdx = it },
                    valueRange = 0f..(R820T_GAINS.size - 1).toFloat(),
                    steps = R820T_GAINS.size - 2,
                    colors = SliderDefaults.colors(
                        thumbColor = Sunset, activeTrackColor = Sunset, inactiveTrackColor = Plum,
                        activeTickColor = NightSky, inactiveTickColor = NightSky,
                    ),
                )
            }
            RetroField(
                ppm, { ppm = it.filter { c -> c.isDigit() || c == '-' } },
                "Frequency correction (ppm)", KeyboardType.Number,
            )
        }
    }
}

@Composable
private fun RetroField(value: String, onChange: (String) -> Unit, label: String, keyboard: KeyboardType = KeyboardType.Text) {
    OutlinedTextField(
        value, onChange,
        label = { Text(label) },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge,
        shape = CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Sunset, unfocusedBorderColor = RadarTeal,
            focusedLabelColor = Sunset, unfocusedLabelColor = RadarTeal,
            cursorColor = Sunset, focusedTextColor = Phosphor, unfocusedTextColor = Phosphor,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Selectable cut-corner tile with an indicator block. */
@Composable
private fun ModeOption(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    val shape = CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp)
    val accent = if (selected) Sunset else RadarTeal
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) Sunset.copy(alpha = 0.12f) else NightSky, shape)
            .border(1.dp, accent.copy(alpha = if (selected) 1f else 0.6f), shape)
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(12.dp).border(1.dp, accent).padding(2.dp)
                .background(if (selected) Sunset else NightSky)
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = if (selected) Phosphor else PhosphorDim)
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = RadarTeal)
        }
    }
}
