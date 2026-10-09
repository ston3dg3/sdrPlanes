package com.sdrplanes.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp

enum class ThemeMode { SYSTEM, DARK, LIGHT }

/** Material roles mapped onto the HUD palette (same mapping for both themes). */
private fun HudPalette.colorScheme(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = high,
        onPrimaryContainer = accent,
        secondary = secondary,
        onSecondary = background,
        secondaryContainer = high,
        onSecondaryContainer = text,
        tertiary = text,
        onTertiary = background,
        error = alert,
        onError = background,
        errorContainer = alert,
        onErrorContainer = background,
        background = background,
        onBackground = text,
        surface = background,
        onSurface = text,
        surfaceVariant = depth,
        onSurfaceVariant = textDim,
        surfaceContainerLowest = background,
        surfaceContainerLow = raised,
        surfaceContainer = raised,
        surfaceContainerHigh = high,
        surfaceContainerHighest = high,
        outline = secondary,
        outlineVariant = depth,
        scrim = background,
        inverseSurface = text,
        inverseOnSurface = background,
        inversePrimary = alert,
    )
}

private val RetroShapes = Shapes(
    extraSmall = CutCornerShape(4.dp),
    small = CutCornerShape(6.dp),
    medium = CutCornerShape(10.dp),
    large = CutCornerShape(topStart = 16.dp, bottomEnd = 16.dp),
    extraLarge = CutCornerShape(topStart = 20.dp, topEnd = 20.dp),
)

@Composable
fun paletteFor(mode: ThemeMode): HudPalette = when (mode) {
    ThemeMode.DARK -> DarkPalette
    ThemeMode.LIGHT -> LightPalette
    ThemeMode.SYSTEM -> if (isSystemInDarkTheme()) DarkPalette else LightPalette
}

@Composable
fun SdrPlanesTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val palette = paletteFor(mode)
    CompositionLocalProvider(LocalHudPalette provides palette) {
        MaterialTheme(colorScheme = palette.colorScheme(), typography = Typography, shapes = RetroShapes, content = content)
    }
}
