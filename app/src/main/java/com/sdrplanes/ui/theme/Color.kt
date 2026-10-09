package com.sdrplanes.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

// Palette (colour_palette.md): retro-futurist "sunset over a radar scope".
val Sunset = Color(0xFFFCAA67)    // primary accent: buttons, borders, callsigns
val Rust = Color(0xFFB0413E)      // alerts, errors, emergency squawks, high altitude
val Phosphor = Color(0xFFFFFFC7)  // primary text
val RadarTeal = Color(0xFF548687) // secondary accent: labels, water, rings, low altitude
val Plum = Color(0xFF473335)      // depth: dividers, inactive tracks, land use
val NightSky = Color(0xFF020122)  // background

// Derived tones (blends of the palette only, so everything stays on-theme).
val NightSkyRaised = lerp(NightSky, Plum, 0.22f)
val NightSkyHigh = lerp(NightSky, Plum, 0.4f)
val PhosphorDim = lerp(Phosphor, NightSky, 0.35f)
val PanelFill = NightSky.copy(alpha = 0.94f)

/** Altitude ramp shared by map icons and UI swatches: teal → phosphor → sunset → rust. */
fun altitudeColor(ft: Int?): Color = when {
    ft == null || ft < 0 -> PhosphorDim
    ft < 10_000 -> lerp(RadarTeal, Phosphor, ft / 10_000f)
    ft < 25_000 -> lerp(Phosphor, Sunset, (ft - 10_000) / 15_000f)
    ft < 40_000 -> lerp(Sunset, Rust, (ft - 25_000) / 15_000f)
    else -> Rust
}

/** 7500 hijack, 7600 radio failure, 7700 general emergency. */
fun isEmergencySquawk(squawk: String?) = squawk == "7500" || squawk == "7600" || squawk == "7700"
