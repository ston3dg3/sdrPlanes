package com.sdrplanes.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sdrplanes.ui.theme.NightSky
import com.sdrplanes.ui.theme.PanelFill
import com.sdrplanes.ui.theme.Phosphor
import com.sdrplanes.ui.theme.Plum
import com.sdrplanes.ui.theme.RadarTeal
import com.sdrplanes.ui.theme.Sunset
import com.sdrplanes.ui.theme.hud
import kotlin.math.log10

/** Cut-corner shape used by all HUD panels: clipped top-left and bottom-right. */
fun hudShape(cut: Dp = 14.dp): Shape = CutCornerShape(topStart = cut, bottomEnd = cut)

/** Translucent night-sky panel with a thin glowing border, the basic HUD surface. */
@Composable
fun HudPanel(
    modifier: Modifier = Modifier,
    accent: Color = Sunset,
    cut: Dp = 14.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = hudShape(cut)
    // Dialogs don't inherit a content colour, so set phosphor text explicitly.
    CompositionLocalProvider(LocalContentColor provides Phosphor) {
        Column(
            modifier
                .shadow(12.dp, shape, ambientColor = accent, spotColor = accent)
                .background(PanelFill, shape)
                .border(1.dp, Brush.linearGradient(listOf(accent, accent.copy(alpha = 0.25f), accent)), shape)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            content = content,
        )
    }
}

/** Small uppercase teal caption above a value. */
@Composable
fun HudLabel(text: String, color: Color = RadarTeal, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    Text(
        text.uppercase(), style = MaterialTheme.typography.labelSmall, color = color, modifier = modifier,
        maxLines = maxLines, overflow = TextOverflow.Ellipsis,
    )
}

/** Label + value pair used in readout grids. */
@Composable
fun Readout(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = Phosphor) {
    Column(modifier) {
        HudLabel(label, maxLines = 1)
        // One line that shrinks to fit its column, so long values never spill into the next one.
        val style = MaterialTheme.typography.bodyLarge
        BasicText(
            value,
            style = style.copy(color = valueColor),
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = style.fontSize, stepSize = 0.5.sp),
            modifier = Modifier.padding(end = 4.dp),
        )
    }
}

/** Status LED; pulses when [blinking]. */
@Composable
fun StatusLed(color: Color, blinking: Boolean, modifier: Modifier = Modifier) {
    // The animation only exists while blinking, and is read in the draw phase, so an idle
    // or steady LED doesn't recompose the HUD every frame.
    val alpha: () -> Float = if (blinking) {
        val pulse = rememberInfiniteTransition(label = "led").animateFloat(
            initialValue = 1f, targetValue = 0.25f,
            animationSpec = infiniteRepeatable(tween(600, easing = LinearEasing), RepeatMode.Reverse),
            label = "led-alpha",
        )
        val read: () -> Float = { pulse.value }
        read
    } else {
        { 1f }
    }
    Box(
        modifier
            .size(10.dp)
            .graphicsLayer { this.alpha = alpha() }
            .shadow(6.dp, CircleShape, ambientColor = color, spotColor = color)
            .background(color, CircleShape)
    )
}

/** Square cut-corner icon button; [filled] for the primary action. */
@Composable
fun HudButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    accent: Color = Sunset,
    size: Dp = 52.dp,
) {
    val shape = CutCornerShape(topStart = size / 4, bottomEnd = size / 4)
    Box(
        modifier
            .size(size)
            .shadow(if (filled) 14.dp else 6.dp, shape, ambientColor = accent, spotColor = accent)
            .background(if (filled) accent else PanelFill, shape)
            .border(1.5.dp, accent, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (filled) NightSky else accent, modifier = Modifier.size(size * 0.5f))
    }
}

/** Text action in dialogs: outlined (secondary) or filled (primary). */
@Composable
fun HudTextButton(text: String, onClick: () -> Unit, filled: Boolean = false, accent: Color = Sunset) {
    val shape = CutCornerShape(topStart = 8.dp, bottomEnd = 8.dp)
    Box(
        Modifier
            .background(if (filled) accent else Color.Transparent, shape)
            .border(1.dp, accent, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(text.uppercase(), style = MaterialTheme.typography.labelLarge, color = if (filled) NightSky else accent)
    }
}

/** 5-segment signal meter; [signal] is linear magnitude 0..1 of full scale. */
@Composable
fun SignalMeter(signal: Float, modifier: Modifier = Modifier) {
    // ~ -40 dBFS (barely decodable) .. -10 dBFS (very strong)
    val db = if (signal > 0f) 20 * log10(signal) else -100f
    val lit = (((db + 40f) / 30f) * 5f).toInt().coerceIn(if (signal > 0f) 1 else 0, 5)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        for (i in 0 until 5) {
            Box(
                Modifier
                    .width(4.dp)
                    .height((6 + i * 3).dp)
                    .background(if (i < lit) RadarTeal else Plum)
            )
        }
    }
}

/** Retro dialog: HUD panel with an Orbitron title and right-aligned actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HudDialog(
    title: String,
    onDismiss: () -> Unit,
    actions: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    BasicAlertDialog(onDismissRequest = onDismiss) {
        HudPanel(Modifier.fillMaxWidth(), cut = 18.dp) {
            Text(
                title.uppercase(), style = MaterialTheme.typography.titleMedium, color = Sunset,
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
            )
            GlowDivider()
            Spacer(Modifier.height(12.dp))
            // Takes only the height left over, so a long (scrolling) body never pushes the
            // actions off the dialog.
            Column(Modifier.weight(1f, fill = false)) { content() }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) { actions() }
        }
    }
}

/** Thin horizontal rule fading out at both ends. */
@Composable
fun GlowDivider(color: Color = Sunset, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Brush.horizontalGradient(listOf(Color.Transparent, color, color.copy(alpha = 0.4f), Color.Transparent)))
    )
}

/** Full-screen CRT scanlines + vignette. Draw-only, so it never intercepts touches. */
@Composable
fun CrtOverlay(modifier: Modifier = Modifier) {
    val p = hud
    // Scanlines: background-coloured on the dark scope, faint ink on the light one.
    val line = if (p.isDark) p.background.copy(alpha = 0.16f) else p.text.copy(alpha = 0.035f)
    val vignette = p.background.copy(alpha = if (p.isDark) 0.55f else 0.25f)
    Spacer(
        modifier
            .fillMaxSize()
            .drawBehind {
                val step = 3.dp.toPx()
                var y = 0f
                while (y < size.height) {
                    drawLine(line, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                    y += step
                }
                drawRect(
                    Brush.radialGradient(
                        0.55f to Color.Transparent,
                        1f to vignette,
                        center = center,
                        radius = size.maxDimension * 0.75f,
                    )
                )
            }
    )
}

/** Small cut-corner toggle used for filters; filled when [selected]. */
@Composable
fun HudChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, accent: Color = Sunset) {
    val shape = CutCornerShape(topStart = 6.dp, bottomEnd = 6.dp)
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = if (selected) NightSky else accent,
        modifier = modifier
            .background(if (selected) accent else Color.Transparent, shape)
            .border(1.dp, accent.copy(alpha = if (selected) 1f else 0.7f), shape)
            .clickable(role = Role.Checkbox, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

/** Centred empty-state text. */
@Composable
fun HudEmpty(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(), style = MaterialTheme.typography.labelMedium, color = RadarTeal,
        textAlign = TextAlign.Center, modifier = modifier.fillMaxWidth().padding(32.dp),
    )
}
