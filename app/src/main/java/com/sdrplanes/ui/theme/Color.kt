package com.sdrplanes.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

// Palette (colour_palette.md): retro-futurist "sunset over a radar scope".
private val PaletteSunset = Color(0xFFFCAA67)
private val PaletteRust = Color(0xFFB0413E)
private val PaletteCream = Color(0xFFFFFFC7)
private val PaletteTeal = Color(0xFF548687)
private val PalettePlum = Color(0xFF473335)
private val PaletteNight = Color(0xFF020122)

/**
 * Colour roles of the HUD. The names of the dark theme are kept as the role names used
 * throughout the UI ([NightSky] = background, [Phosphor] = text, ...); the light theme maps
 * the same roles onto the palette inverted. Every tone is the palette or a blend of it.
 */
@Immutable
data class HudPalette(
    val isDark: Boolean,
    /** Background (night sky / cream). */
    val background: Color,
    /** Primary text. */
    val text: Color,
    /** Primary accent: buttons, borders, callsigns. */
    val accent: Color,
    /** Alerts, errors, emergency squawks, high altitude. */
    val alert: Color,
    /** Secondary accent: labels, water, rings, low altitude. */
    val secondary: Color,
    /** Depth: dividers, inactive tracks, land use. */
    val depth: Color,
    /** Text/foreground on a filled [accent] surface. */
    val onAccent: Color,
) {
    val raised = lerp(background, depth, if (isDark) 0.22f else 0.12f)
    val high = lerp(background, depth, if (isDark) 0.4f else 0.22f)
    val textDim = lerp(text, background, 0.35f)
    val panelFill = background.copy(alpha = 0.94f)

    /** Altitude ramp shared by map icons and UI swatches: secondary → text → accent → alert. */
    fun altitudeColor(ft: Int?): Color = when {
        ft == null || ft < 0 -> textDim
        ft < 10_000 -> lerp(secondary, text, ft / 10_000f)
        ft < 25_000 -> lerp(text, accent, (ft - 10_000) / 15_000f)
        ft < 40_000 -> lerp(accent, alert, (ft - 25_000) / 15_000f)
        else -> alert
    }
}

val DarkPalette = HudPalette(
    isDark = true,
    background = PaletteNight,
    text = PaletteCream,
    accent = PaletteSunset,
    alert = PaletteRust,
    secondary = PaletteTeal,
    depth = PalettePlum,
    onAccent = PaletteNight,
)

/** Cream background; accents darkened towards rust/night so text keeps its contrast. */
val LightPalette = HudPalette(
    isDark = false,
    background = PaletteCream,
    text = PaletteNight,
    accent = lerp(PaletteSunset, PaletteRust, 0.6f),
    alert = lerp(PaletteRust, PaletteNight, 0.2f),
    secondary = lerp(PaletteTeal, PaletteNight, 0.3f),
    depth = lerp(PalettePlum, PaletteCream, 0.55f),
    onAccent = PaletteCream,
)

val LocalHudPalette = staticCompositionLocalOf { DarkPalette }

/** The active palette. */
val hud: HudPalette @Composable @ReadOnlyComposable get() = LocalHudPalette.current

// Role names used across the UI (resolve against the active palette).
val Sunset: Color @Composable @ReadOnlyComposable get() = LocalHudPalette.current.accent
val Rust: Color @Composable @ReadOnlyComposable get() = LocalHudPalette.current.alert
val Phosphor: Color @Composable @ReadOnlyComposable get() = LocalHudPalette.current.text
val RadarTeal: Color @Composable @ReadOnlyComposable get() = LocalHudPalette.current.secondary
val Plum: Color @Composable @ReadOnlyComposable get() = LocalHudPalette.current.depth
val NightSky: Color @Composable @ReadOnlyComposable get() = LocalHudPalette.current.background
val NightSkyRaised: Color @Composable @ReadOnlyComposable get() = LocalHudPalette.current.raised
val NightSkyHigh: Color @Composable @ReadOnlyComposable get() = LocalHudPalette.current.high
val PhosphorDim: Color @Composable @ReadOnlyComposable get() = LocalHudPalette.current.textDim
val PanelFill: Color @Composable @ReadOnlyComposable get() = LocalHudPalette.current.panelFill
val OnSunset: Color @Composable @ReadOnlyComposable get() = LocalHudPalette.current.onAccent

@Composable
@ReadOnlyComposable
fun altitudeColor(ft: Int?): Color = LocalHudPalette.current.altitudeColor(ft)

/** 7500 hijack, 7600 radio failure, 7700 general emergency. */
fun isEmergencySquawk(squawk: String?) = squawk == "7500" || squawk == "7600" || squawk == "7700"
