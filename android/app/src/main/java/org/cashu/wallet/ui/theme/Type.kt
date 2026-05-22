package org.cashu.wallet.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp

private val sansSerif = FontFamily.SansSerif

private val DefaultLineHeightStyle = LineHeightStyle(
    alignment = LineHeightStyle.Alignment.Center,
    trim = LineHeightStyle.Trim.None,
)

private fun textStyle(
    fontSize: Int,
    lineHeight: Int,
    weight: FontWeight = FontWeight.Normal,
    letterSpacing: Double = 0.0,
    monoDigits: Boolean = false,
): TextStyle = TextStyle(
    fontFamily = sansSerif,
    fontWeight = weight,
    fontSize = fontSize.sp,
    lineHeight = lineHeight.sp,
    letterSpacing = letterSpacing.sp,
    lineHeightStyle = DefaultLineHeightStyle,
    fontFeatureSettings = if (monoDigits) "tnum" else null,
)

val CashuTypography = Typography(
    displayLarge = textStyle(56, 64, FontWeight.Bold, monoDigits = true),
    displayMedium = textStyle(44, 52, FontWeight.Bold, monoDigits = true),
    displaySmall = textStyle(36, 44, FontWeight.SemiBold, monoDigits = true),

    headlineLarge = textStyle(32, 40, FontWeight.SemiBold),
    headlineMedium = textStyle(28, 36, FontWeight.SemiBold),
    headlineSmall = textStyle(22, 28, FontWeight.SemiBold),

    titleLarge = textStyle(22, 28, FontWeight.SemiBold),
    titleMedium = textStyle(17, 22, FontWeight.SemiBold),
    titleSmall = textStyle(15, 20, FontWeight.SemiBold),

    bodyLarge = textStyle(17, 24, FontWeight.Normal),
    bodyMedium = textStyle(15, 21, FontWeight.Normal),
    bodySmall = textStyle(13, 18, FontWeight.Normal),

    labelLarge = textStyle(16, 20, FontWeight.SemiBold, letterSpacing = 0.1),
    labelMedium = textStyle(12, 16, FontWeight.SemiBold, letterSpacing = 0.7),
    labelSmall = textStyle(11, 16, FontWeight.SemiBold, letterSpacing = 0.5),
)

// Monospaced-digit variant of any TextStyle, per the Tabular Figure Rule.
fun TextStyle.withMonoDigits(): TextStyle =
    copy(fontFeatureSettings = listOfNotNull(this.fontFeatureSettings, "tnum").joinToString(", "))

// Letter-spaced uppercase utility for SECTION HEADER overlines.
fun TextStyle.asOverline(): TextStyle =
    copy(letterSpacing = 1.2.sp, fontWeight = FontWeight.SemiBold)
