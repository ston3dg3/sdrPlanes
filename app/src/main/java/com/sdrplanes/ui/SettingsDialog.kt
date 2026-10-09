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
import com.sdrplanes.sdr.usb.R82xxTuner
import com.sdrplanes.db.AircraftDb
import com.sdrplanes.decode.LatLon
import com.sdrplanes.service.Settings
import com.sdrplanes.ui.theme.ThemeMode
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.sdrplanes.service.SourceMode
import com.sdrplanes.ui.theme.NightSky
import com.sdrplanes.ui.theme.Phosphor
import com.sdrplanes.ui.theme.PhosphorDim
import com.sdrplanes.ui.theme.Plum
import com.sdrplanes.ui.theme.RadarTeal
import com.sdrplanes.ui.theme.Rust
import com.sdrplanes.ui.theme.Sunset
import kotlin.math.roundToInt

private val R820T_GAINS = R82xxTuner.GAINS

@Composable
fun SettingsDialog(
    initial: Settings,
    receiver: LatLon?,
    receiving: Boolean,
    onDismiss: () -> Unit,
    onRecord: () -> Unit,
    onSave: (Settings) -> Unit,
) {
    val context = LocalContext.current
    var mode by remember { mutableStateOf(initial.mode) }
    var host by remember { mutableStateOf(initial.host) }
    var port by remember { mutableStateOf(initial.port.toString()) }
    var autoGain by remember { mutableStateOf(initial.gainTenthsDb == null) }
    var gainIdx by remember {
        val g = initial.gainTenthsDb ?: R820T_GAINS.last()
        mutableFloatStateOf(R820T_GAINS.indices.minBy { kotlin.math.abs(R820T_GAINS[it] - g) }.toFloat())
    }
    var ppm by remember { mutableStateOf(initial.ppm.toString()) }
    var sampleRate by remember { mutableStateOf(initial.sampleRate) }
    var biasTee by remember { mutableStateOf(initial.biasTee) }
    var theme by remember { mutableStateOf(initial.theme) }
    val offline by OfflineMaps.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { OfflineMaps.refresh(context) }
    val db = produceState<AircraftDb?>(null) {
        value = withContext(Dispatchers.IO) { runCatching { AircraftDb.open(context) }.getOrNull() }
    }.value

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
                        sampleRate = sampleRate,
                        biasTee = biasTee,
                        theme = theme,
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
            ModeOption("USB dongle", "Built-in driver, dongle on this phone", mode == SourceMode.USB) {
                mode = SourceMode.USB
            }
            ModeOption("RTL-SDR driver app", "Dongle on this phone via the driver app", mode == SourceMode.DRIVER_APP) {
                mode = SourceMode.DRIVER_APP
            }
            ModeOption("Network rtl_tcp server", "PC, Raspberry Pi or emulator", mode == SourceMode.NETWORK) {
                mode = SourceMode.NETWORK
            }
            if (mode == SourceMode.NETWORK) {
                RetroField(host, { host = it.trim() }, "Host (emulator: 127.0.0.1 + adb reverse)")
            }
            if (mode != SourceMode.USB) {
                RetroField(port, { port = it.filter(Char::isDigit) }, "Port", KeyboardType.Number)
            }

            Spacer(Modifier.size(2.dp))
            HudLabel("Tuner")
            HudSwitch("Auto gain", autoGain) { autoGain = it }
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
            Text("Sample rate", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (r in Settings.SAMPLE_RATES) {
                    Box(Modifier.weight(1f)) {
                        ModeOption(
                            "%.1f MS/s".format(r / 1e6),
                            if (r == 2_400_000) "Best decoding" else "Less CPU",
                            sampleRate == r,
                        ) { sampleRate = r }
                    }
                }
            }
            HudSwitch("Bias-tee (antenna power)", biasTee) { biasTee = it }
            if (biasTee) {
                Text(
                    "Powers an LNA or filter through the coax. Only enable with a bias-tee capable dongle and powered gear – never with a DC-shorted antenna.",
                    style = MaterialTheme.typography.labelSmall, color = Sunset,
                )
            }

            Spacer(Modifier.size(2.dp))
            HudLabel("Appearance")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((m, label) in listOf(ThemeMode.SYSTEM to "System", ThemeMode.DARK to "Dark", ThemeMode.LIGHT to "Light")) {
                    Box(Modifier.weight(1f)) { HudChip(label, theme == m, { theme = m }, Modifier.fillMaxWidth()) }
                }
            }

            Spacer(Modifier.size(2.dp))
            HudLabel("Offline map")
            OfflineMapSection(offline, receiver)

            Spacer(Modifier.size(2.dp))
            HudLabel("Recording")
            Text(
                "Saves 30 s of raw IQ (8-bit, rtl_sdr format, ~150 MB) to Download/SdrPlanes – for offline analysis or bug reports.",
                style = MaterialTheme.typography.labelSmall, color = PhosphorDim,
            )
            HudTextButton(
                if (receiving) "Record 30 s of IQ" else "Start receiving to record",
                onClick = { if (receiving) onRecord() }, accent = if (receiving) Sunset else PhosphorDim,
            )

            Spacer(Modifier.size(2.dp))
            HudLabel("Data")
            Text(
                (db?.let { "Aircraft database: %,d aircraft, %s – data by Mictronics via wiedehopf/tar1090-db. ".format(it.size, it.source.substringAfterLast(' ')) }
                    ?: "Aircraft database not loaded. ") +
                    "Map © OpenFreeMap, OpenMapTiles, OpenStreetMap contributors. SdrPlanes is free software under the GPL-3.0-or-later.",
                style = MaterialTheme.typography.labelSmall, color = PhosphorDim,
            )
        }
    }
}

@Composable
private fun OfflineMapSection(state: OfflineMapState, receiver: LatLon?) {
    val context = LocalContext.current
    val text = when (state) {
        OfflineMapState.Unknown -> "Checking…"
        OfflineMapState.None -> "No offline area. Downloads ${OfflineMaps.RADIUS_KM.toInt()} km around you (dark + light map, zoom ≤ 10, about 110 MB – use Wi-Fi)."
        is OfflineMapState.Downloading -> "Downloading… %d%% · %.1f MB".format((state.progress * 100).toInt(), state.bytes / 1e6)
        is OfflineMapState.Ready -> "Stored: %.0f km around %.2f, %.2f · %.1f MB".format(
            OfflineMaps.RADIUS_KM, state.center.lat, state.center.lon, state.bytes / 1e6,
        )
        is OfflineMapState.Failed -> state.message
    }
    Text(text, style = MaterialTheme.typography.labelSmall, color = if (state is OfflineMapState.Failed) Rust else PhosphorDim)
    if (state is OfflineMapState.Downloading) {
        LinearProgressIndicator(
            progress = { state.progress }, color = Sunset, trackColor = Plum, modifier = Modifier.fillMaxWidth(),
        )
        return
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        HudTextButton(
            if (receiver == null) "Needs location" else if (state is OfflineMapState.Ready) "Update area" else "Download area",
            onClick = { receiver?.let { OfflineMaps.download(context, it) } },
            accent = if (receiver == null) PhosphorDim else Sunset,
        )
        if (state is OfflineMapState.Ready) {
            HudTextButton("Delete", onClick = { OfflineMaps.delete(context) }, accent = RadarTeal)
        }
    }
}

@Composable
fun HudSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(
            checked, onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = NightSky, checkedTrackColor = Sunset,
                uncheckedThumbColor = RadarTeal, uncheckedTrackColor = NightSky, uncheckedBorderColor = RadarTeal,
            ),
        )
    }
}

@Composable
fun RetroField(value: String, onChange: (String) -> Unit, label: String, keyboard: KeyboardType = KeyboardType.Text) {
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
fun ModeOption(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
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
