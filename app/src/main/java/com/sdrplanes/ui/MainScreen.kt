package com.sdrplanes.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sdrplanes.decode.LatLon
import com.sdrplanes.model.Aircraft
import com.sdrplanes.model.AircraftTracker
import com.sdrplanes.sdr.RtlSdrDriver
import com.sdrplanes.service.ReceiverService
import com.sdrplanes.service.ReceiverState
import com.sdrplanes.service.ReceiverStats
import com.sdrplanes.service.ReceiverStatus
import com.sdrplanes.service.Settings
import com.sdrplanes.service.SourceMode
import com.sdrplanes.ui.theme.NightSky
import com.sdrplanes.ui.theme.Phosphor
import com.sdrplanes.ui.theme.PhosphorDim
import com.sdrplanes.ui.theme.Plum
import com.sdrplanes.ui.theme.RadarTeal
import com.sdrplanes.ui.theme.Rust
import com.sdrplanes.ui.theme.Sunset
import com.sdrplanes.ui.theme.altitudeColor
import com.sdrplanes.ui.theme.isEmergencySquawk

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(autoStart: Boolean) {
    val context = LocalContext.current
    val status by ReceiverState.status.collectAsStateWithLifecycle()
    val aircraft by ReceiverState.aircraft.collectAsStateWithLifecycle()
    val stats by ReceiverState.stats.collectAsStateWithLifecycle()
    val receiver by ReceiverState.receiverPosition.collectAsStateWithLifecycle()

    var settings by remember { mutableStateOf(Settings.load(context)) }
    var showSettings by remember { mutableStateOf(false) }
    var showList by remember { mutableStateOf(false) }
    var showInstallDriver by remember { mutableStateOf(false) }
    var selectedIcao by remember { mutableStateOf<Int?>(null) }
    var cameraRequest by remember { mutableStateOf<CameraRequest?>(null) }

    fun hasLocation() = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    var locationGranted by remember { mutableStateOf(hasLocation()) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        locationGranted = hasLocation()
    }
    LaunchedEffect(Unit) {
        val wanted = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) permissionLauncher.launch(wanted.toTypedArray())
    }
    ReceiverLocationEffect(locationGranted)

    // Centre once: on the receiver, or (no location fix) on the first aircraft with a position.
    // A receiver fix arriving later still wins once.
    var centredOnAircraft by remember { mutableStateOf(false) }
    var centredOnReceiver by remember { mutableStateOf(false) }
    val firstFix = aircraft.firstNotNullOfOrNull { it.position }
    LaunchedEffect(receiver, firstFix != null) {
        val r = receiver
        if (r != null && !centredOnReceiver) {
            centredOnReceiver = true
            cameraRequest = CameraRequest(r, zoom = 8.0)
        } else if (r == null && firstFix != null && !centredOnAircraft) {
            centredOnAircraft = true
            cameraRequest = CameraRequest(firstFix, zoom = 8.0)
        }
    }

    val driverLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val error = RtlSdrDriver.resultError(result.resultCode, result.data)
        if (error == null) {
            ReceiverService.start(context, RtlSdrDriver.HOST, settings.port, settings.tunerConfig)
        } else {
            ReceiverState.setStatus(ReceiverStatus.Error(error))
        }
    }

    fun start() {
        when (settings.mode) {
            SourceMode.DRIVER_APP -> {
                if (!RtlSdrDriver.isInstalled(context)) {
                    showInstallDriver = true
                    return
                }
                ReceiverState.setStatus(ReceiverStatus.Connecting("RTL-SDR driver"))
                driverLauncher.launch(RtlSdrDriver.startIntent(settings.tunerConfig, settings.port))
            }
            SourceMode.NETWORK -> ReceiverService.start(context, settings.host, settings.port, settings.tunerConfig)
        }
    }

    val active = status is ReceiverStatus.Running || status is ReceiverStatus.Connecting
    // Launched by plugging in the dongle: start once (not again after rotation).
    var autoStarted by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(autoStart) {
        if (autoStart && !autoStarted && ReceiverState.status.value !is ReceiverStatus.Running) {
            autoStarted = true
            start()
        }
    }

    Box(Modifier.fillMaxSize().background(NightSky)) {
        AircraftMap(
            aircraft = aircraft,
            receiver = receiver,
            selectedIcao = selectedIcao,
            cameraRequest = cameraRequest,
            onSelect = { selectedIcao = it },
            modifier = Modifier.fillMaxSize(),
        )

        CrtOverlay()

        StatusHud(
            status, stats, aircraft.size,
            Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth(),
        )

        Column(
            Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HudButton(Icons.Filled.Settings, "Settings", onClick = { showSettings = true }, accent = RadarTeal)
            HudButton(Icons.AutoMirrored.Filled.List, "Aircraft list", onClick = { showList = true }, accent = RadarTeal)
            if (receiver != null) {
                HudButton(Icons.Filled.MyLocation, "My location", onClick = { cameraRequest = CameraRequest(receiver!!) }, accent = RadarTeal)
            }
            HudButton(
                if (active) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                if (active) "Stop" else "Start",
                onClick = { if (active) ReceiverService.stop(context) else start() },
                filled = true,
                accent = if (active) Rust else Sunset,
                size = 68.dp,
            )
        }

        val selected = aircraft.firstOrNull { it.icao == selectedIcao }
        // Keep showing the last selection while the exit animation runs.
        val lastSelected = remember { arrayOfNulls<Aircraft>(1) }
        if (selected != null) lastSelected[0] = selected
        AnimatedVisibility(
            visible = selected != null,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomStart).navigationBarsPadding()
                .padding(start = 12.dp, bottom = 16.dp, end = 96.dp),
        ) {
            val shown = selected ?: lastSelected[0] ?: return@AnimatedVisibility
            AircraftCard(shown, receiver, onClose = { selectedIcao = null }, modifier = Modifier.fillMaxWidth())
        }
    }

    if (showList) {
        ModalBottomSheet(
            onDismissRequest = { showList = false },
            containerColor = NightSky,
            contentColor = Phosphor,
            scrimColor = NightSky.copy(alpha = 0.6f),
            dragHandle = { DragHandle() },
        ) {
            AircraftList(aircraft, receiver) { a ->
                selectedIcao = a.icao
                a.position?.let { cameraRequest = CameraRequest(it) }
                showList = false
            }
        }
    }

    if (showSettings) {
        SettingsDialog(settings, onDismiss = { showSettings = false }) {
            settings = it
            Settings.save(context, it)
            showSettings = false
        }
    }

    if (showInstallDriver) {
        HudDialog(
            title = "RTL-SDR driver needed",
            onDismiss = { showInstallDriver = false },
            actions = {
                HudTextButton("Cancel", onClick = { showInstallDriver = false }, accent = RadarTeal)
                HudTextButton("Open Play Store", filled = true, onClick = {
                    showInstallDriver = false
                    try {
                        context.startActivity(RtlSdrDriver.storeIntent())
                    } catch (_: ActivityNotFoundException) {
                        context.startActivity(RtlSdrDriver.storeWebIntent())
                    }
                })
            },
        ) {
            Text(
                "SdrPlanes uses the free \"RTL-SDR driver\" app to access the USB dongle. Install it, then press Start again.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun StatusHud(status: ReceiverStatus, stats: ReceiverStats, count: Int, modifier: Modifier) {
    val (word, color, detail) = when (status) {
        ReceiverStatus.Idle -> Triple("Standby", PhosphorDim, "Idle – press ▶ to start")
        is ReceiverStatus.Connecting -> Triple("Linking", Sunset, "Connecting to ${status.target}…")
        is ReceiverStatus.Running -> Triple("Scanning", RadarTeal, "${status.tuner} · ${status.gain} · 1090 MHz")
        is ReceiverStatus.Error -> Triple("Fault", Rust, status.message)
    }
    HudPanel(modifier, accent = if (status is ReceiverStatus.Error) Rust else Sunset) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "SDR//PLANES", style = MaterialTheme.typography.titleMedium, color = Sunset,
                modifier = Modifier.weight(1f),
            )
            StatusLed(color, blinking = status is ReceiverStatus.Connecting || status is ReceiverStatus.Running)
            Spacer(Modifier.width(8.dp))
            Text(word.uppercase(), style = MaterialTheme.typography.labelLarge, color = color)
        }
        Spacer(Modifier.height(6.dp))
        GlowDivider(color.copy(alpha = 0.8f))
        Spacer(Modifier.height(6.dp))
        Text(
            detail, style = MaterialTheme.typography.bodySmall,
            color = if (status is ReceiverStatus.Error) Rust else PhosphorDim,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        if (status is ReceiverStatus.Running) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Readout("Contacts", count.toString(), valueColor = Sunset)
                Readout("Pos fix", stats.withPosition.toString())
                Readout("Msg/s", stats.messagesPerSec.toString())
                Readout("Total", stats.totalMessages.toString())
            }
        }
    }
}

@Composable
private fun AircraftCard(a: Aircraft, receiver: LatLon?, onClose: () -> Unit, modifier: Modifier) {
    val emergency = isEmergencySquawk(a.squawk)
    HudPanel(modifier, accent = if (emergency) Rust else Sunset) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 4.dp, height = 28.dp).background(altitudeColor(a.altitudeFt)))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(a.label, style = MaterialTheme.typography.headlineSmall, color = if (emergency) Rust else Sunset)
                Text(
                    "ICAO ${a.icaoHex}" + (a.squawk?.let { " · SQK $it" } ?: "") + if (emergency) " · EMERGENCY" else "",
                    style = MaterialTheme.typography.labelMedium, color = if (emergency) Rust else RadarTeal,
                )
            }
            Box(
                Modifier.size(40.dp).clickable(role = Role.Button, onClick = onClose)
                    .semantics { contentDescription = "Close" },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Close, null, tint = PhosphorDim) }
        }
        Spacer(Modifier.height(8.dp))
        GlowDivider()
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth()) {
            Readout("Altitude", a.altitudeFt?.let(::formatAltitude) ?: "—", Modifier.weight(1f), altitudeColor(a.altitudeFt))
            Readout("Speed", a.speedKt?.let { "%.0f kt".format(it) } ?: "—", Modifier.weight(1f))
            Readout("Track", a.trackDeg?.let { "%03.0f°".format(it) } ?: "—", Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Readout("V/S", a.verticalRateFpm?.let { "%+d".format(it) } ?: "—", Modifier.weight(1f))
            Readout("Range", distanceText(a, receiver), Modifier.weight(1f))
            Column(Modifier.weight(1f)) {
                HudLabel("Signal")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SignalMeter(a.signal)
                    Spacer(Modifier.width(8.dp))
                    Text("${a.messages}", style = MaterialTheme.typography.bodySmall, color = PhosphorDim)
                }
            }
        }
    }
}

@Composable
private fun DragHandle() {
    Box(
        Modifier.padding(vertical = 12.dp).size(width = 48.dp, height = 4.dp).background(Sunset.copy(alpha = 0.7f))
    )
}

@Composable
private fun AircraftList(aircraft: List<Aircraft>, receiver: LatLon?, onClick: (Aircraft) -> Unit) {
    val sorted = aircraft.sortedWith(compareBy({ it.position == null }, { -it.signal }))
    Column(Modifier.navigationBarsPadding()) {
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("CONTACTS", style = MaterialTheme.typography.titleMedium, color = Sunset, modifier = Modifier.weight(1f))
            Text("[${sorted.size}]", style = MaterialTheme.typography.bodyLarge, color = RadarTeal)
        }
        Spacer(Modifier.height(8.dp))
        GlowDivider(modifier = Modifier.padding(horizontal = 12.dp))
        if (sorted.isEmpty()) {
            HudEmpty("No contacts yet · scanning 1090 MHz")
            return@Column
        }
        LazyColumn {
            items(sorted, key = { it.icao }) { a ->
                val emergency = isEmergencySquawk(a.squawk)
                Row(
                    Modifier.fillMaxWidth().clickable { onClick(a) }.padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(width = 3.dp, height = 34.dp).background(if (emergency) Rust else altitudeColor(a.altitudeFt)))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            a.label, style = MaterialTheme.typography.titleSmall,
                            color = if (emergency) Rust else Phosphor,
                        )
                        Text(
                            listOf(
                                a.icaoHex,
                                a.altitudeFt?.let(::formatAltitude) ?: "ALT ?",
                                a.speedKt?.let { "%.0f KT".format(it) } ?: "SPD ?",
                            ).joinToString("  ·  "),
                            style = MaterialTheme.typography.bodySmall, color = PhosphorDim,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            if (a.position == null) "NO POS" else distanceText(a, receiver),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (a.position == null) PhosphorDim else Sunset,
                        )
                        Spacer(Modifier.height(4.dp))
                        SignalMeter(a.signal)
                    }
                }
                Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp).height(1.dp).background(Plum))
            }
        }
    }
}

private fun distanceText(a: Aircraft, receiver: LatLon?): String {
    val p = a.position ?: return "—"
    val r = receiver ?: return "—"
    return "%.0f km".format(AircraftTracker.distanceKm(p, r))
}
