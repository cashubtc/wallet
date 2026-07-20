package com.cashu.me.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.cashu.me.ui.theme.CashuTheme
import com.cashu.me.Core.Errors.AppError

// iOS InlineNotice: caption icon, 6pt gap, 10pt pad when tinted, 12pt radius.
private val NoticeIconSize = 14.dp
private val NoticeTintPadding = 10.dp
private val NoticeCorner = RoundedCornerShape(12.dp)

enum class NoticeSeverity { Error, Warning, Info, Success }

/**
 * The single in-context notice surface (iOS `InlineNotice`).
 *
 * Screens never render raw red text: every inline error, caution, or
 * confirmation goes through this — optional severity-tinted container, leading
 * glyph, quiet copy that says what broke and what to try next.
 *
 * @param detail optional secondary line (iOS `detail`), always onSurfaceVariant
 * @param tinted wrap in a tint surface (iOS `tinted`); on by default to match
 *   prior Android call sites
 */
@Composable
fun InlineNotice(
    text: String,
    modifier: Modifier = Modifier,
    severity: NoticeSeverity = NoticeSeverity.Error,
    detail: String? = null,
    tinted: Boolean = true,
) {
    val (icon, tint, container) = noticeColors(severity)
    val content = @Composable {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(NoticeIconSize),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelMedium,
                    color = tint,
                )
                if (detail != null) {
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    if (tinted) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .background(container, NoticeCorner)
                .padding(NoticeTintPadding),
        ) {
            content()
        }
    } else {
        Column(modifier = modifier.fillMaxWidth()) {
            content()
        }
    }
}

/** Failure-only overload. Validation/caution strings keep using the non-clickable overload above. */
@Composable
fun InlineNotice(
    error: AppError,
    modifier: Modifier = Modifier,
    tinted: Boolean = true,
) {
    var showsDetails by rememberSaveable(error.reportId) { mutableStateOf(false) }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clickable(
                role = Role.Button,
                onClickLabel = "Open error details",
            ) { showsDetails = true }
            .semantics {
                contentDescription = "${error.info.userMessage}. Open error details and reporting."
            },
    ) {
        InlineNotice(
            text = error.info.userMessage,
            detail = "Tap for details and reporting",
            severity = NoticeSeverity.Error,
            tinted = tinted,
        )
    }
    if (showsDetails) {
        ErrorDetailsSheet(error = error, onDismiss = { showsDetails = false })
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
    detail: String? = null,
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
        InlineNotice(
            text = lastText.orEmpty(),
            severity = severity,
            detail = detail,
        )
    }
}

@Composable
private fun noticeColors(severity: NoticeSeverity): Triple<ImageVector, Color, Color> = when (severity) {
    NoticeSeverity.Error -> Triple(
        Icons.Outlined.ErrorOutline,
        MaterialTheme.colorScheme.error,
        MaterialTheme.colorScheme.error.copy(alpha = 0.18f),
    )
    // iOS caution: exclamationmark.circle.fill + orange / orange@10%.
    NoticeSeverity.Warning -> Triple(
        Icons.Filled.Error,
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
