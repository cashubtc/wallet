package org.cashu.wallet.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import org.cashu.wallet.ui.theme.CashuTheme

private val NoticeIconSize = 18.dp

enum class NoticeSeverity { Error, Warning, Info, Success }

/**
 * The single in-context notice surface (iOS InlineNotice / ErrorBannerView).
 * Screens never render raw red text: every inline error, caution, or
 * confirmation goes through this — severity-tinted container, leading glyph,
 * quiet copy that says what broke and what to try next.
 */
@Composable
fun InlineNotice(
    text: String,
    modifier: Modifier = Modifier,
    severity: NoticeSeverity = NoticeSeverity.Error,
) {
    val (icon, tint, container) = noticeColors(severity)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(container, MaterialTheme.shapes.small)
            .padding(
                horizontal = CashuTheme.spacing.default,
                vertical = CashuTheme.spacing.default,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(NoticeIconSize),
        )
        Spacer(Modifier.width(CashuTheme.spacing.snug))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = tint,
        )
    }
}

/**
 * Show/hide wrapper with the canonical entrance (slide up + fade) and quiet exit
 * (fade only — exits are subtler than entrances).
 */
@Composable
fun InlineNoticeHost(
    text: String?,
    modifier: Modifier = Modifier,
    severity: NoticeSeverity = NoticeSeverity.Error,
) {
    // Keep the last non-null text so the exit fade shows content, not a blank.
    var lastText = text
    AnimatedVisibility(
        visible = text != null,
        modifier = modifier,
        enter = slideInVertically(tween(220)) { it / 2 } + fadeIn(tween(220)),
        exit = fadeOut(tween(180)),
    ) {
        text?.let { lastText = it }
        InlineNotice(text = lastText.orEmpty(), severity = severity)
    }
}

@Composable
private fun noticeColors(severity: NoticeSeverity): Triple<ImageVector, Color, Color> = when (severity) {
    NoticeSeverity.Error -> Triple(
        Icons.Outlined.ErrorOutline,
        MaterialTheme.colorScheme.error,
        MaterialTheme.colorScheme.error.copy(alpha = 0.12f),
    )
    NoticeSeverity.Warning -> Triple(
        Icons.Outlined.Schedule,
        CashuTheme.colors.pending,
        CashuTheme.colors.pendingContainer,
    )
    NoticeSeverity.Info -> Triple(
        Icons.Outlined.Info,
        MaterialTheme.colorScheme.onSurfaceVariant,
        MaterialTheme.colorScheme.surfaceContainerHigh,
    )
    NoticeSeverity.Success -> Triple(
        Icons.Outlined.CheckCircle,
        CashuTheme.colors.received,
        CashuTheme.colors.receivedContainer,
    )
}
