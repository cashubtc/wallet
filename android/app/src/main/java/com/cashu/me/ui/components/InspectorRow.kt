package com.cashu.me.ui.components

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cashu.me.ui.theme.CashuTheme
import com.cashu.me.ui.theme.withMonoDigits

// Inspector leading icon stays at 18dp (a touch smaller than the 20dp body icon size)
// so the inspector reads as denser metadata, not list-row chrome.
private val InspectorLeadingIconSize = 18.dp
private val InspectorEditHintSize = 16.dp

/** Payment flows share an inset column; compact History receipts use the full sheet width. */
internal val PaymentDetailMaxWidth = 320.dp

enum class InspectorRowStyle { Payment, History, Standard }

internal fun Modifier.paymentDetailWidth(): Modifier = fillMaxWidth()
    .wrapContentWidth(Alignment.CenterHorizontally)
    .widthIn(max = PaymentDetailMaxWidth)
    .fillMaxWidth()

/**
 * Two-column metadata row used inside Cashu Request / Transaction Detail inspector
 * groups. Optional leading icon, optional pencil affordance for editable rows
 * (which trigger a sub-sheet on tap), optional quiet trailing affordance for
 * other tappable rows (e.g. tap-to-copy: ContentCopy → green Check).
 *
 * @param loading skeleton fill-in (iOS `.redacted(.placeholder)` on confirm fee
 *   rows): while true a quiet placeholder bar holds the value slot, then the
 *   real value crossfades in place when it lands.
 * @param secondaryValue optional caption beneath the value (iOS mint detail's
 *   fiat conversion under the sat balance), always quiet onSurfaceVariant.
 */
@Composable
fun InspectorRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    editable: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailingIcon: ImageVector? = null,
    trailingIconTint: Color? = null,
    valueMonospaced: Boolean = false,
    valueColor: Color? = null,
    secondaryValue: String? = null,
    loading: Boolean = false,
    style: InspectorRowStyle = InspectorRowStyle.Payment,
) {
    val paymentDetails = style == InspectorRowStyle.Payment
    val rowStyle = if (style == InspectorRowStyle.Standard) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium
    val columnModifier = when (style) {
        InspectorRowStyle.Payment -> modifier.paymentDetailWidth()
        InspectorRowStyle.History -> modifier.fillMaxWidth().heightIn(min = 48.dp)
        InspectorRowStyle.Standard -> modifier.fillMaxWidth()
    }
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(modifier = columnModifier) {
        val gapCount = 1 + (if (leadingIcon != null) 1 else 0) + (if (editable || trailingIcon != null) 1 else 0)
        val decorationWidth = CashuTheme.spacing.default * gapCount +
            (if (leadingIcon != null) InspectorLeadingIconSize else 0.dp) +
            (if (editable || trailingIcon != null) InspectorEditHintSize else 0.dp)
        val availableWidth = with(density) {
            (maxWidth - CashuTheme.spacing.comfortable * 2 - decorationWidth).roundToPx()
        }
        val labelWidth = textMeasurer.measure(AnnotatedString(label), rowStyle, maxLines = 1, softWrap = false).size.width
        val valueWidth = textMeasurer.measure(AnnotatedString(value),
            if (valueMonospaced) rowStyle.withMonoDigits() else rowStyle, maxLines = 1, softWrap = false).size.width
        val stacked = style != InspectorRowStyle.Standard &&
            (density.fontScale > 1.3f || labelWidth + valueWidth > availableWidth)
        val rowMod = if (onClick != null) {
            Modifier.heightIn(min = 48.dp).clickable(onClick = onClick)
        } else {
            Modifier
        }
        Column(
            modifier = rowMod.padding(
                horizontal = CashuTheme.spacing.comfortable,
                vertical = if (paymentDetails) CashuTheme.spacing.snug else CashuTheme.spacing.default,
            ),
            verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.snug, Alignment.CenterVertically),
        ) {
            if (stacked) {
                Text(label, style = rowStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(CashuTheme.spacing.default),
            ) {
                if (leadingIcon != null) {
                    Icon(
                        imageVector = leadingIcon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(InspectorLeadingIconSize),
                    )
                }
                if (!stacked) {
                    Text(
                        text = label,
                        style = rowStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // Values align to the trailing edge in columns, or to the label
                // above when a row stacks for long values or accessibility text.
                Box(modifier = Modifier.weight(1f), contentAlignment = if (stacked) Alignment.CenterStart else Alignment.CenterEnd) {
                    SkeletonValue(loading = loading) {
                        Column(horizontalAlignment = if (stacked) Alignment.Start else Alignment.End) {
                            Text(
                                text = value,
                                modifier = if (stacked) Modifier.fillMaxWidth() else Modifier,
                                style = if (valueMonospaced) {
                                    rowStyle.withMonoDigits()
                                } else rowStyle,
                                color = valueColor ?: MaterialTheme.colorScheme.onSurface,
                                maxLines = if (stacked) Int.MAX_VALUE else if (style == InspectorRowStyle.Standard) 1 else 2,
                                overflow = if (stacked) TextOverflow.Clip else TextOverflow.MiddleEllipsis,
                                textAlign = if (stacked) TextAlign.Start else TextAlign.End,
                            )
                            if (secondaryValue != null) {
                                Text(
                                    text = secondaryValue,
                                    modifier = if (stacked) Modifier.fillMaxWidth() else Modifier,
                                    style = if (valueMonospaced) {
                                        MaterialTheme.typography.bodyMedium.withMonoDigits()
                                    } else MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = if (stacked) Int.MAX_VALUE else 1,
                                    overflow = if (stacked) TextOverflow.Clip else TextOverflow.MiddleEllipsis,
                                    textAlign = if (stacked) TextAlign.Start else TextAlign.End,
                                )
                            }
                        }
                    }
                }
                if (editable) {
                    Icon(
                        imageVector = Icons.Outlined.Edit,
                        contentDescription = "Edit $label",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(InspectorEditHintSize),
                    )
                } else if (trailingIcon != null) {
                    Icon(
                        imageVector = trailingIcon,
                        contentDescription = null,
                        tint = trailingIconTint ?: MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(InspectorEditHintSize),
                    )
                }
            }
        }
    }
}


/** Prose belongs to the inspector, with a native reader for longer descriptions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DescriptionDetailRow(
    description: String,
    label: String = "Description",
    style: InspectorRowStyle = InspectorRowStyle.Payment,
) {
    var overflowing by remember(description) { mutableStateOf(false) }
    var showFullDescription by remember(description) { mutableStateOf(false) }
    val configuration = LocalConfiguration.current
    val previewLines = if (LocalCompactPaymentDetails.current || configuration.orientation == Configuration.ORIENTATION_LANDSCAPE ||
        configuration.fontScale > 1.3f) 1 else 3

    Column(
        modifier = (if (style == InspectorRowStyle.Payment) Modifier.paymentDetailWidth() else Modifier.fillMaxWidth()).padding(
            horizontal = CashuTheme.spacing.comfortable,
            vertical = if (style == InspectorRowStyle.Payment) CashuTheme.spacing.snug else CashuTheme.spacing.default,
        ),
        verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.snug),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (overflowing) {
                TextButton(onClick = { showFullDescription = true }) { Text("Read more") }
            }
        }
        SelectionContainer {
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = previewLines,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { overflowing = it.hasVisualOverflow },
                modifier = Modifier.testTag("payment-description-preview"),
            )
        }
    }
    if (showFullDescription) {
        ModalBottomSheet(
            onDismissRequest = { showFullDescription = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(horizontal = CashuTheme.spacing.comfortable)
                    .padding(bottom = CashuTheme.spacing.section),
                verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.section),
            ) {
                SheetHeader(title = label)
                SelectionContainer {
                    Text(description, style = MaterialTheme.typography.bodyLarge)
                }
                PrimaryButton("Done", onClick = { showFullDescription = false })
            }
        }
    }
}
