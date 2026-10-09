package com.sdrplanes.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.sdrplanes.R

/** Orbitron (variable weight): headings, callsigns, buttons. SIL OFL, see assets/licenses. */
@OptIn(ExperimentalTextApi::class)
val Orbitron = FontFamily(
    Font(R.font.orbitron, FontWeight.Medium, variationSettings = FontVariation.Settings(FontVariation.weight(500))),
    Font(R.font.orbitron, FontWeight.Bold, variationSettings = FontVariation.Settings(FontVariation.weight(700))),
    Font(R.font.orbitron, FontWeight.Black, variationSettings = FontVariation.Settings(FontVariation.weight(900))),
)

/** Share Tech Mono: all data readouts. SIL OFL, see assets/licenses. */
val TechMono = FontFamily(Font(R.font.share_tech_mono, FontWeight.Normal))

private fun display(size: Int, weight: FontWeight, spacing: Double) = TextStyle(
    fontFamily = Orbitron, fontWeight = weight, fontSize = size.sp, letterSpacing = spacing.sp,
)

private fun mono(size: Int, line: Int, spacing: Double = 0.0) = TextStyle(
    fontFamily = TechMono, fontSize = size.sp, lineHeight = line.sp, letterSpacing = spacing.sp,
)

val Typography = Typography(
    displaySmall = display(32, FontWeight.Black, 2.0),
    headlineSmall = display(22, FontWeight.Bold, 2.0),
    titleLarge = display(20, FontWeight.Bold, 1.5),
    titleMedium = display(15, FontWeight.Bold, 1.5),
    titleSmall = display(12, FontWeight.Bold, 1.8),
    bodyLarge = mono(17, 24),
    bodyMedium = mono(15, 20),
    bodySmall = mono(13, 18),
    labelLarge = display(12, FontWeight.Bold, 1.5),
    labelMedium = mono(12, 16, 1.5),
    labelSmall = mono(11, 14, 1.8),
)
