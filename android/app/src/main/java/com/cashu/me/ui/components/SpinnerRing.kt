package com.cashu.me.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cashu.me.ui.theme.CashuMotion
import com.cashu.me.ui.theme.rememberReducedMotion

private val SpinnerRingSize = 40.dp
private val SpinnerRingStroke = 6.dp

// iOS SpinnerRing trims the circle from 0.1 → 1.0: a 324° arc with a 36° gap.
private const val SpinnerSweepDegrees = 324f

/**
 * The processing spinner shared by every pay flow — a Canvas port of the iOS
 * `SpinnerRing` (AuthorizingOverlay.swift): a trimmed circle stroke with round
 * caps rotating once per [CashuMotion.SpinnerPeriodMs], linear, forever.
 *
 * Under reduce-motion the custom loop is replaced by the platform
 * [CircularProgressIndicator], mirroring the iOS fallback to a plain
 * `ProgressView`.
 */
@Composable
fun SpinnerRing(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    size: Dp = SpinnerRingSize,
    strokeWidth: Dp = SpinnerRingStroke,
) {
    if (rememberReducedMotion()) {
        CircularProgressIndicator(
            modifier = modifier.size(size),
            color = color,
            strokeWidth = strokeWidth,
        )
        return
    }
    val transition = rememberInfiniteTransition(label = "spinner-ring")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(CashuMotion.SpinnerPeriodMs, easing = LinearEasing),
        ),
        label = "spinner-ring-rotation",
    )
    Canvas(modifier = modifier.size(size)) {
        val stroke = strokeWidth.toPx()
        val inset = stroke / 2f
        drawArc(
            color = color,
            startAngle = rotation,
            sweepAngle = SpinnerSweepDegrees,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = Size(this.size.width - stroke, this.size.height - stroke),
            style = Stroke(width = stroke, cap = StrokeCap.Round),
        )
    }
}
