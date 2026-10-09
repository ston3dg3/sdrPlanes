package com.sdrplanes.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.mapSaver
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
import com.sdrplanes.model.AircraftFilter
import com.sdrplanes.model.AircraftTracker
import com.sdrplanes.model.SortBy
import com.sdrplanes.sdr.RtlSdrDriver
import com.sdrplanes.sdr.UsbDongle
import com.sdrplanes.service.ReceiverService
import com.sdrplanes.service.ReceiverState
import com.sdrplanes.service.ReceiverStats
import com.sdrplanes.service.ReceiverStatus
import com.sdrplanes.service.RecordingState
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
import kotlin.math.roundToInt

private val FilterSaver = mapSaver(
    save = { f ->
        mapOf(
            "q" to f.query, "min" to f.minAltitudeFt, "max" to f.maxAltitudeFt, "gnd" to f.hideGround,
            "pos" to f.positionOnly, "mil" to f.militaryOnly, "sort" to f.sort.name,
        )
    },
    restore = { m ->
        AircraftFilter(
            query = m["q"] as String, minAltitudeFt = m["min"] as Int, maxAltitudeFt = m["max"] as Int,
            hideGround = m["gnd"] as Boolean, positionOnly = m["pos"] as Boolean, militaryOnly = m["mil"] as Boolean,
            sort = SortBy.valueOf(m["sort"] as String),
        )
    },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(settings: Settings, onSettingsChange: (Settings) -> Unit, autoStart: Boolean) {
    val context = LocalContext.current
    val status by ReceiverState.status.collectAsStateWithLifecycle()
    val aircraft by ReceiverState.aircraft.collectAsStateWithLifecycle()
    val stats by ReceiverState.stats.collectAsStateWithLifecycle()
    val receiver by ReceiverState.receiverPosition.collectAsStateWithLifecycle()
    val recording by ReceiverState.recording.collectAsStateWithLifecycle()

    var showSettings by remember { mutableStateOf(false) }
    var showList by remember { mutableStateOf(false) }
    var showInstallDriver by remember { mutableStateOf(false) }
    var selectedIcao by remember { mutableStateOf<Int?>(null) }
    var cameraRequest by remember { mutableStateOf<CameraRequest?>(null) }
    var filter by rememberSaveable(stateSaver = FilterSaver) { mutableStateOf(AircraftFilter()) }

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

    // The same filter drives the list and the map; a selected aircraft stays visible.
    val shown = remember(aircraft, filter, receiver) { filter.apply(aircraft, receiver) }
    val onMap = remember(shown, aircraft, selectedIcao) {
        val sel = aircraft.firstOrNull { it.icao == selectedIcao }
        if (sel != null && shown.none { it.icao == sel.icao }) shown + sel else shown
    }

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
            ReceiverService.start(context)
        } else {
            ReceiverState.setStatus(ReceiverStatus.Error(error))
        }
    }
    val usbPermission = rememberUsbPermissionRequester()

    fun start() {
        when (settings.mode) {
            SourceMode.USB -> {
                val dongle = UsbDongle.find(context)
                if (dongle == null) {
                    ReceiverState.setStatus(ReceiverStatus.Error("No RTL-SDR dongle found – connect it with a USB-OTG adapter"))
                    return
                }
                ReceiverState.setStatus(ReceiverStatus.Connecting("USB dongle"))
                usbPermission.request(dongle) { granted ->
                    if (granted) ReceiverService.start(context)
                    else ReceiverState.setStatus(ReceiverStatus.Error("USB access to the dongle was denied"))
                }
            }
            SourceMode.DRIVER_APP -> {
                if (!RtlSdrDriver.isInstalled(context)) {
                    showInstallDriver = true
                    return
                }
                ReceiverState.setStatus(ReceiverStatus.Connecting("RTL-SDR driver"))
                driverLauncher.launch(RtlSdrDriver.startIntent(settings.tunerConfig, settings.port))
            }
            SourceMode.NETWORK -> ReceiverService.start(context)
        }
    }

    val active = status is ReceiverStatus.Running || status is ReceiverStatus.Connecting ||
        status is ReceiverStatus.Reconnecting
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
            aircraft = onMap,
            receiver = receiver,
            selectedIcao = selectedIcao,
            cameraRequest = cameraRequest,
            onSelect = { selectedIcao = it },
            modifier = Modifier.fillMaxSize(),
        )

        CrtOverlay()

        StatusHud(
            status, stats, shown.size, aircraft.size, filter.isActive, recording,
            Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth(),
        )

        Column(
            Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HudButton(Icons.Filled.Settings, "Settings", onClick = { showSettings = true }, accent = RadarTeal)
            HudButton(
                Icons.AutoMirrored.Filled.List, "Aircraft list", onClick = { showList = true },
                accent = if (filter.isActive) Sunset else RadarTeal,
            )
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
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val cardMax = maxHeight * 0.55f
            AnimatedVisibility(
                visible = selected != null,
                enter = slideInVertically { it } + fadeIn(),
                exit = slideOutVertically { it } + fadeOut(),
                modifier = Modifier.align(Alignment.BottomStart).navigationBarsPadding()
                    .padding(start = 12.dp, bottom = 16.dp, end = 96.dp),
            ) {
                val shownCard = selected ?: lastSelected[0] ?: return@AnimatedVisibility
                AircraftCard(
                    shownCard, receiver, onClose = { selectedIcao = null },
                    modifier = Modifier.fillMaxWidth().heightIn(max = cardMax),
                )
            }
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
            AircraftList(shown, aircraft.size, filter, onFilter = { filter = it }, receiver) { a ->
                selectedIcao = a.icao
                a.position?.let { cameraRequest = CameraRequest(it) }
                showList = false
            }
        }
    }

    if (showSettings) {
        SettingsDialog(
            settings, receiver = receiver, receiving = status is ReceiverStatus.Running,
            onDismiss = { showSettings = false },
            onRecord = {
                ReceiverService.record(context)
                showSettings = false
            },
        ) {
            onSettingsChange(it)
            showSettings = false
        }
    }

    (recording as? RecordingState.Done)?.let { done ->
        HudDialog(
            title = "Recording saved",
            onDismiss = { ReceiverState.setRecording(RecordingState.Idle) },
            actions = {
                HudTextButton("OK", onClick = { ReceiverState.setRecording(RecordingState.Idle) }, accent = RadarTeal)
                HudTextButton("Share", filled = true, onClick = {
                    val send = Intent(Intent.ACTION_SEND)
                        .setType("application/octet-stream")
                        .putExtra(Intent.EXTRA_STREAM, done.uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startActivity(Intent.createChooser(send, done.name))
                    ReceiverState.setRecording(RecordingState.Idle)
                })
            },
        ) {
            Text(
                "Download/SdrPlanes/${done.name}\n%.0f MB of 8-bit IQ".format(done.bytes / 1e6) +
                    if (done.dropped > 0) "\n%.1f MB dropped (storage too slow)".format(done.dropped / 1e6) else "",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
    (recording as? RecordingState.Failed)?.let { failed ->
        HudDialog(
            title = "Recording failed",
            onDismiss = { ReceiverState.setRecording(RecordingState.Idle) },
            actions = { HudTextButton("OK", onClick = { ReceiverState.setRecording(RecordingState.Idle) }, accent = RadarTeal) },
        ) { Text(failed.message, style = MaterialTheme.typography.bodyMedium, color = Rust) }
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
                "The \"RTL-SDR driver app\" source needs the free RTL-SDR driver app. Install it, or switch the source to \"USB dongle\" in settings.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun StatusHud(
    status: ReceiverStatus,
    stats: ReceiverStats,
    shown: Int,
    total: Int,
    filtered: Boolean,
    recording: RecordingState,
    modifier: Modifier,
) {
    val (word, color, detail) = when (status) {
        ReceiverStatus.Idle -> Triple("Standby", PhosphorDim, "Idle – press ▶ to start")
        is ReceiverStatus.Connecting -> Triple("Linking", Sunset, "Connecting to ${status.target}…")
        is ReceiverStatus.Running -> Triple(
            "Scanning", RadarTeal,
            "${status.tuner} · ${status.gain} · ${"%.1f".format(status.sampleRate / 1e6)} MS/s" +
                if (status.biasTee) " · BIAS-T" else "",
        )
        is ReceiverStatus.Reconnecting -> Triple("Relink", Sunset, "${status.message} – retry in ${status.inSeconds} s")
        is ReceiverStatus.Error -> Triple("Fault", Rust, status.message)
    }
    HudPanel(modifier, accent = if (status is ReceiverStatus.Error) Rust else Sunset) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "SDR//PLANES", style = MaterialTheme.typography.titleMedium, color = Sunset,
                modifier = Modifier.weight(1f),
            )
            if (recording is RecordingState.Recording) {
                Text(
                    "REC ${recording.seconds}/${recording.total}s", style = MaterialTheme.typography.labelMedium, color = Rust,
                    modifier = Modifier.padding(end = 10.dp),
                )
            }
            StatusLed(
                color,
                blinking = status is ReceiverStatus.Connecting || status is ReceiverStatus.Running ||
                    status is ReceiverStatus.Reconnecting,
            )
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
                Readout(if (filtered) "Shown" else "Contacts", if (filtered) "$shown/$total" else "$total", valueColor = Sunset)
                Readout("Pos fix", stats.withPosition.toString())
                Readout("Msg/s", if (stats.messagesPerSec < 10) "%.1f".format(stats.messagesPerSec) else "%.0f".format(stats.messagesPerSec))
                Readout("Total", stats.totalMessages.toString())
            }
        }
    }
}

@Composable
private fun AircraftCard(a: Aircraft, receiver: LatLon?, onClose: () -> Unit, modifier: Modifier) {
    val emergency = isEmergencySquawk(a.squawk) || a.emergency != null || a.tcasRa != null
    val info = a.info
    HudPanel(modifier, accent = if (emergency) Rust else Sunset) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 4.dp, height = 28.dp).background(altitudeColor(a.altitudeFt)))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(a.label, style = MaterialTheme.typography.headlineSmall, color = if (emergency) Rust else Sunset)
                Text(
                    listOfNotNull(
                        "ICAO ${a.icaoHex}",
                        info?.registration?.takeIf { it != a.label },
                        a.squawk?.let { "SQK $it" },
                    ).joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium, color = if (emergency) Rust else RadarTeal,
                )
            }
            Box(
                Modifier.size(40.dp).clickable(role = Role.Button, onClick = onClose)
                    .semantics { contentDescription = "Close" },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Close, null, tint = PhosphorDim) }
        }
        Column(Modifier.verticalScroll(rememberScrollState())) {
            val type = listOfNotNull(info?.typeCode, info?.description, info?.year?.toString()).joinToString(" · ")
            if (type.isNotEmpty()) {
                Text(type.uppercase(), style = MaterialTheme.typography.labelSmall, color = Phosphor)
            }
            val details = listOfNotNull(
                info?.country?.uppercase(),
                "MILITARY".takeIf { info?.military == true },
                a.category?.uppercase(),
                "ON GROUND".takeIf { a.onGround },
                a.adsbVersion?.let { "ADS-B V$it" },
            )
            if (details.isNotEmpty()) {
                Text(details.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = PhosphorDim)
            }
            a.tcasRa?.let { Banner("TCAS RA · $it") }
            a.emergency?.let { Banner("EMERGENCY · ${it.uppercase()}") }
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
            val ap = a.autopilot
            if (!ap.isEmpty) {
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth()) {
                    Readout("Sel alt", ap.selectedAltitudeFt?.let(::formatAltitude) ?: "—", Modifier.weight(1f))
                    Readout("Sel hdg", ap.selectedHeadingDeg?.let { "%03.0f°".format(it) } ?: "—", Modifier.weight(1f))
                    Readout("QNH", ap.baroHpa?.let { "%.0f".format(it) } ?: "—", Modifier.weight(1f))
                }
                if (ap.modes.isNotEmpty()) {
                    Text(ap.modes.joinToString("  "), style = MaterialTheme.typography.labelSmall, color = RadarTeal)
                }
            }
            val air = a.air
            if (!air.isEmpty) {
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth()) {
                    Readout(
                        "IAS / Mach",
                        listOfNotNull(air.indicatedAirspeedKt?.let { "$it" }, air.mach?.let { "M%.2f".format(it) })
                            .joinToString(" ").ifEmpty { "—" },
                        Modifier.weight(1f),
                    )
                    Readout("TAS", air.trueAirspeedKt?.let { "$it kt" } ?: "—", Modifier.weight(1f))
                    Readout(
                        "Hdg / roll",
                        listOfNotNull(air.magHeadingDeg?.let { "%03.0f°".format(it) }, air.rollDeg?.let { "%+.0f°".format(it) })
                            .joinToString(" ").ifEmpty { "—" },
                        Modifier.weight(1f),
                    )
                }
            }
            val wx = a.weather
            if (!wx.isEmpty) {
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth()) {
                    Readout(
                        "Wind",
                        if (wx.windKt != null && wx.windFromDeg != null) "%03.0f°/%dkt".format(wx.windFromDeg, wx.windKt) else "—",
                        Modifier.weight(1f),
                    )
                    Readout("SAT", wx.temperatureC?.let { "%+.0f °C".format(it) } ?: "—", Modifier.weight(1f))
                    Readout("Humidity", wx.humidityPct?.let { "%.0f%%".format(it) } ?: "—", Modifier.weight(1f))
                }
                if (wx.hazards.isNotEmpty()) {
                    Text(wx.hazards.joinToString("  "), style = MaterialTheme.typography.labelSmall, color = Rust)
                }
            }
        }
    }
}

/** Alert strip inside the aircraft card. */
@Composable
private fun Banner(text: String) {
    Text(
        text, style = MaterialTheme.typography.labelLarge, color = NightSky,
        modifier = Modifier.padding(top = 6.dp).fillMaxWidth().background(Rust).padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
private fun DragHandle() {
    Box(
        Modifier.padding(vertical = 12.dp).size(width = 48.dp, height = 4.dp).background(Sunset.copy(alpha = 0.7f))
    )
}

@Composable
private fun AircraftList(
    shown: List<Aircraft>,
    total: Int,
    filter: AircraftFilter,
    onFilter: (AircraftFilter) -> Unit,
    receiver: LatLon?,
    onClick: (Aircraft) -> Unit,
) {
    Column(Modifier.navigationBarsPadding()) {
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("CONTACTS", style = MaterialTheme.typography.titleMedium, color = Sunset, modifier = Modifier.weight(1f))
            if (filter.isActive) {
                Text(
                    "RESET", style = MaterialTheme.typography.labelMedium, color = RadarTeal,
                    modifier = Modifier.clickable(role = Role.Button) { onFilter(AircraftFilter(sort = filter.sort)) }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
            Text(if (filter.isActive) "[${shown.size}/$total]" else "[$total]", style = MaterialTheme.typography.bodyLarge, color = RadarTeal)
        }
        FilterBar(filter, onFilter)
        Spacer(Modifier.height(8.dp))
        GlowDivider(modifier = Modifier.padding(horizontal = 12.dp))
        if (shown.isEmpty()) {
            HudEmpty(if (total == 0) "No contacts yet · scanning 1090 MHz" else "No contacts match the filter")
            return@Column
        }
        LazyColumn {
            items(shown, key = { it.icao }) { a ->
                val emergency = isEmergencySquawk(a.squawk) || a.emergency != null || a.tcasRa != null
                Row(
                    Modifier.fillMaxWidth().clickable { onClick(a) }.padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(width = 3.dp, height = 34.dp).background(if (emergency) Rust else altitudeColor(a.altitudeFt)))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            listOfNotNull(a.label, a.info?.typeCode, "MIL".takeIf { a.info?.military == true }).joinToString("  "),
                            style = MaterialTheme.typography.titleSmall,
                            color = if (emergency) Rust else Phosphor,
                        )
                        Text(
                            listOfNotNull(
                                a.info?.registration?.takeIf { it != a.label } ?: a.icaoHex,
                                if (a.onGround) "GND" else a.altitudeFt?.let(::formatAltitude) ?: "ALT ?",
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

/** Search, toggles, sort and altitude range for the contacts list (and map). */
@Composable
private fun FilterBar(filter: AircraftFilter, onFilter: (AircraftFilter) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RetroField(filter.query, { onFilter(filter.copy(query = it)) }, "Search callsign, reg, type, ICAO")
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HudChip("Hide ground", filter.hideGround, { onFilter(filter.copy(hideGround = !filter.hideGround)) })
            HudChip("With position", filter.positionOnly, { onFilter(filter.copy(positionOnly = !filter.positionOnly)) })
            HudChip("Military", filter.militaryOnly, { onFilter(filter.copy(militaryOnly = !filter.militaryOnly)) })
            HudChip(
                "Sort: ${filter.sort.label}", selected = false, accent = RadarTeal,
                onClick = { onFilter(filter.copy(sort = SortBy.entries[(filter.sort.ordinal + 1) % SortBy.entries.size])) },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            HudLabel("Altitude", modifier = Modifier.weight(1f))
            val max = if (filter.maxAltitudeFt >= AircraftFilter.ALTITUDE_CAP) "∞" else "%,d".format(filter.maxAltitudeFt)
            Text("%,d – %s ft".format(filter.minAltitudeFt, max), style = MaterialTheme.typography.labelMedium, color = Sunset)
        }
        RangeSlider(
            value = filter.minAltitudeFt.toFloat()..filter.maxAltitudeFt.toFloat(),
            onValueChange = { r ->
                val step = 1000
                onFilter(
                    filter.copy(
                        minAltitudeFt = (r.start / step).roundToInt() * step,
                        maxAltitudeFt = (r.endInclusive / step).roundToInt() * step,
                    )
                )
            },
            valueRange = 0f..AircraftFilter.ALTITUDE_CAP.toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = Sunset, activeTrackColor = Sunset, inactiveTrackColor = Plum,
            ),
            modifier = Modifier.height(32.dp),
        )
    }
}

private fun distanceText(a: Aircraft, receiver: LatLon?): String {
    val p = a.position ?: return "—"
    val r = receiver ?: return "—"
    return "%.0f km".format(AircraftTracker.distanceKm(p, r))
}
