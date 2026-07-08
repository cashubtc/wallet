package org.cashu.wallet.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Stock Material 3 type scale (Roboto). Roles are used as designed — no
 * cross-platform ramp. Money keeps tabular figures via [withMonoDigits].
 */
val CashuTypography = Typography()

// Monospaced-digit variant of any TextStyle, per the Tabular Figure Rule.
fun TextStyle.withMonoDigits(): TextStyle =
    copy(fontFeatureSettings = listOfNotNull(this.fontFeatureSettings, "tnum").joinToString(", "))

// Letter-spaced uppercase utility for SECTION HEADER overlines.
fun TextStyle.asOverline(): TextStyle =
    copy(letterSpacing = 1.2.sp, fontWeight = FontWeight.SemiBold)
