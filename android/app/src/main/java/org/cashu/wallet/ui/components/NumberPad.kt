package org.cashu.wallet.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.dp
import org.cashu.wallet.Core.UnitAmountEntry

// Minimal keypad: no background boxes, just numbers with subtle press feedback.
// Tighter vertical spacing for a cleaner iOS-style appearance.
private val KeyGap = 4.dp
private val KeyHeight = 52.dp

/**
 * Minimal numeric keypad for amount entry — no background boxes, just numbers
 * with opacity-based press feedback (iOS-style). With [decimals] == 0 the output
 * is the plain digit-only String; with decimals > 0 keys route through
 * [UnitAmountEntry]'s minor-unit accumulator ("5" → "0.05" → "0.50" → "5.00")
 * for unit-native fiat-ecash entry. Long-press delete clears all.
 */
@Composable
fun NumberPad(
    amount: String,
    onAmountChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    decimals: Int = 0,
) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("", "0", "delete"),
    )
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(KeyGap),
    ) {
        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(KeyGap),
            ) {
                row.forEach { key ->
                    when (key) {
                        "" -> Box(modifier = Modifier.weight(1f).height(KeyHeight))
                        "delete" -> NumberPadKey(
                            modifier = Modifier.weight(1f),
                            contentDescription = "Delete",
                            onClick = {
                                if (amount.isNotEmpty()) {
                                    onAmountChange(UnitAmountEntry.backspace(amount, decimals))
                                }
                            },
                            onLongClick = {
                                if (amount.isNotEmpty()) onAmountChange("")
                            },
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Backspace,
                                contentDescription = null,
                            )
                        }
                        else -> NumberPadKey(
                            modifier = Modifier.weight(1f),
                            contentDescription = key,
                            onClick = {
                                onAmountChange(UnitAmountEntry.append(key, amount, decimals))
                            },
                        ) {
                            Text(
                                text = key,
                                style = MaterialTheme.typography.headlineSmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NumberPadKey(
    contentDescription: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    // Opacity-based press feedback: subtle dim on press (iOS-style, no background).
    val alpha by animateFloatAsState(
        targetValue = if (pressed) 0.4f else 1f,
        animationSpec = tween(durationMillis = 100),
        label = "key-press-alpha",
    )
    Box(
        modifier = modifier
            .height(KeyHeight)
            .graphicsLayer { this.alpha = alpha }
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null, // No ripple — opacity handles feedback
                onClickLabel = contentDescription,
                onLongClickLabel = onLongClick?.let { "Clear" },
                onLongClick = onLongClick?.let {
                    {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        it()
                    }
                },
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onClick()
                },
            ),
        contentAlignment = Alignment.Center,
        content = { content() },
    )
}
