package com.sdrplanes.ui.theme

import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

/** One fixed dark scheme: the retro look is the brand, so no dynamic/light variants. */
private val RetroColors = darkColorScheme(
    primary = Sunset,
    onPrimary = NightSky,
    primaryContainer = NightSkyHigh,
    onPrimaryContainer = Sunset,
    secondary = RadarTeal,
    onSecondary = NightSky,
    secondaryContainer = NightSkyHigh,
    onSecondaryContainer = Phosphor,
    tertiary = Phosphor,
    onTertiary = NightSky,
    error = Rust,
    onError = Phosphor,
    errorContainer = Rust,
    onErrorContainer = Phosphor,
    background = NightSky,
    onBackground = Phosphor,
    surface = NightSky,
    onSurface = Phosphor,
    surfaceVariant = Plum,
    onSurfaceVariant = PhosphorDim,
    surfaceContainerLowest = NightSky,
    surfaceContainerLow = NightSkyRaised,
    surfaceContainer = NightSkyRaised,
    surfaceContainerHigh = NightSkyHigh,
    surfaceContainerHighest = NightSkyHigh,
    outline = RadarTeal,
    outlineVariant = Plum,
    scrim = NightSky,
    inverseSurface = Phosphor,
    inverseOnSurface = NightSky,
    inversePrimary = Rust,
)

private val RetroShapes = Shapes(
    extraSmall = CutCornerShape(4.dp),
    small = CutCornerShape(6.dp),
    medium = CutCornerShape(10.dp),
    large = CutCornerShape(topStart = 16.dp, bottomEnd = 16.dp),
    extraLarge = CutCornerShape(topStart = 20.dp, topEnd = 20.dp),
)

@Composable
fun SdrPlanesTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = RetroColors, typography = Typography, shapes = RetroShapes, content = content)
}
