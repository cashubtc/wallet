package com.cashu.me.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cashu.me.Core.AmountDisplayPrimary
import com.cashu.me.Core.AmountDisplayText
import com.cashu.me.Core.AmountFormatter
import com.cashu.me.Core.displayText
import com.cashu.me.ui.theme.AmountScale
import com.cashu.me.ui.theme.CashuTheme
import com.cashu.me.ui.theme.withMonoDigits

/**
 * Hero amount whose whole primary/secondary pair toggles units, matching Home.
 * The swap cross-fades through [AmountText], and the pair stays visually plain
 * rather than assigning the affordance to a separate icon or support-line button.
 *
 * When [entryRaw] is set, the primary line follows the typed keypad string
 * (partial decimals included) while the secondary line keeps the mint-unit
 * alternate — matching iOS live entry on Receive / Send.
 *
 * When no fiat price is available the control is omitted and the amount renders
 * plain in sats.
 * A [note] about the amount being entered takes the secondary line while it
 * shows (iOS `CurrencyAmountDisplay` parity).
 * [primaryScale] uses the shared amount lockup for display-only payment heroes,
 * including unit alignment and proportional sizing for long amounts.
 */
@Composable
fun AmountFlipDisplay(
    amountSats: Long,
    primary: AmountDisplayPrimary,
    onFlip: (AmountDisplayPrimary) -> Unit,
    btcPrice: Double?,
    currencyCode: String,
    useBitcoinSymbol: Boolean,
    modifier: Modifier = Modifier,
    entryRaw: String? = null,
    // The rung the live entry is set at. Screens that share the amount's
    // space with other content step it down rather than crowd them.
    entryScale: AmountScale = AmountScale.Hero,
    primaryTextStyle: TextStyle? = null,
    primaryScale: AmountScale? = null,
    primaryAccessibilityPrefix: String? = null,
    color: Color = MaterialTheme.colorScheme.onSurface,
    // Said in the secondary line's place: the line is there already, so the
    // note costs no room.
    note: AmountNote? = null,
    // Holds the secondary line's height when there is no price to show, so a
    // note appearing never moves the amount. For screens that pass [note].
    holdsNoteLine: Boolean = false,
) {
    val haptics = LocalHapticFeedback.current
    val formatter = remember { AmountFormatter() }
    val priceAvailable = btcPrice != null && btcPrice > 0
    val display = if (entryRaw != null) {
        formatter.entryDisplayText(
            entryRaw = entryRaw,
            amountSats = amountSats,
            preferredPrimary = primary,
            btcPrice = btcPrice,
            currencyCode = currencyCode,
            useBitcoinSymbol = useBitcoinSymbol,
        )
    } else {
        formatter.displayText(
            amountSats = amountSats,
            preferredPrimary = primary.rawValue,
            showFiat = priceAvailable,
            btcPrice = btcPrice,
            currencyCode = currencyCode,
            useBitcoinSymbol = useBitcoinSymbol,
        )
    }
    val secondary = display.secondary
    val flip = {
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        onFlip(
            if (display.effectivePrimary == AmountDisplayPrimary.Fiat) {
                AmountDisplayPrimary.Sats
            } else {
                AmountDisplayPrimary.Fiat
            },
        )
    }
    val toggleModifier = if (secondary != null) {
        Modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClickLabel = "Make $secondary primary",
                onClick = flip,
            )
            .clearAndSetSemantics {
                role = Role.Button
                contentDescription =
                    "Amount: ${display.primary}. Tap to make $secondary primary."
                onClick(label = "Make $secondary primary") {
                    flip()
                    true
                }
            }
            .sizeIn(minHeight = 48.dp)
    } else {
        Modifier
    }
    // Geist's entry hero has a tighter line box than iOS's SF amount.
    // Compensate here so the visible conversion gap matches, without
    // changing the hero's metrics or the smaller receipt amount pairs.
    val lineGap = Arrangement.spacedBy(
        if (entryRaw != null) CashuTheme.spacing.default else CashuTheme.spacing.micro,
    )
    // Set after the first frame. The conversion line composed after it (back
    // from under a note, or a price arriving) fades in rather than popping.
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { settled = true }
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = lineGap,
    ) {
        Column(
            modifier = Modifier.then(toggleModifier),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = lineGap,
        ) {
            val primaryStyle =
                (primaryTextStyle ?: MaterialTheme.typography.displayMedium).withMonoDigits()
            if (entryRaw != null) {
                // Entry mode: the parent re-expresses [entryRaw] on flip, so animate
                // the resulting string directly. Re-deriving the same raw under the
                // opposite unit would briefly mint-unit-misread fiat digits as sats.
                AmountHero(
                    parts = display.primaryParts,
                    scale = entryScale,
                    color = color,
                    accessibilityPrefix = primaryAccessibilityPrefix,
                )
            } else {
                // Display mode: amountSats is unit-agnostic, so cross-fade units
                // while each side keeps its own formatting.
                AnimatedContent(
                    targetState = display.effectivePrimary,
                    transitionSpec = {
                        fadeIn(spring(stiffness = Spring.StiffnessMedium))
                            .togetherWith(fadeOut(spring(stiffness = Spring.StiffnessMedium)))
                    },
                    label = "amount-flip-hero",
                ) { primaryState ->
                    val stateDisplay = formatter.displayText(
                        amountSats = amountSats,
                        preferredPrimary = primaryState.rawValue,
                        showFiat = priceAvailable,
                        btcPrice = btcPrice,
                        currencyCode = currencyCode,
                        useBitcoinSymbol = useBitcoinSymbol,
                    )
                    if (primaryScale != null) {
                        AmountHero(
                            parts = stateDisplay.primaryParts,
                            scale = primaryScale,
                            color = color,
                            accessibilityPrefix = primaryAccessibilityPrefix,
                        )
                    } else {
                        AmountText(
                            text = stateDisplay.primary,
                            modifier = if (primaryAccessibilityPrefix != null) {
                                Modifier.semantics {
                                    contentDescription =
                                        "$primaryAccessibilityPrefix: ${stateDisplay.primary}"
                                }
                            } else {
                                Modifier
                            },
                            style = primaryStyle,
                            color = color,
                        )
                    }
                }
            }
            if (secondary != null && note == null) {
                // AnimatedContent never animates its first content, so a line
                // coming back when a note clears would cut in. It fades in on
                // the note's own spec instead (iOS `CurrencyAmountDisplay`);
                // at first composition it is simply there.
                val entrance = remember { Animatable(if (settled) 0f else 1f) }
                LaunchedEffect(Unit) {
                    entrance.animateTo(1f, spring(stiffness = Spring.StiffnessMedium))
                }
                AnimatedContent(
                    targetState = secondary,
                    modifier = Modifier.graphicsLayer { alpha = entrance.value },
                    transitionSpec = {
                        fadeIn(spring(stiffness = Spring.StiffnessMedium))
                            .togetherWith(fadeOut(spring(stiffness = Spring.StiffnessMedium)))
                    },
                    label = "amount-flip-control",
                ) { text ->
                    Text(
                        text = bitcoinAmountText(text),
                        style = CashuTheme.type.amountSecondary,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        // Outside the toggle, whose semantics are cleared, so TalkBack reads it
        // as its own line. The two lines trade places without overlapping, so
        // the amount holds still.
        if (note != null) {
            AmountNoteLine(note)
        } else if (holdsNoteLine && secondary == null) {
            AmountNoteLinePlaceholder()
        }
    }
}

/** A note about an amount being entered: why it can't go as typed (iOS `AmountNote`). */
data class AmountNote(
    val text: String,
    val severity: NoticeSeverity = NoticeSeverity.Error,
    // What TalkBack reads in place of [text], when the line is a shortened
    // form of a longer message (a wallet error's {what broke} sentence).
    val spoken: String? = null,
)

/**
 * A note in the secondary line's place under an amount (Family's pattern):
 * plain words, red for a problem and secondary otherwise, with no glyph. It
 * wraps rather than truncating. Announced politely when it appears or changes,
 * as `InlineNotice` is.
 */
@Composable
fun AmountNoteLine(note: AmountNote, modifier: Modifier = Modifier) {
    // A problem is red whether it is an error or a caution: orange text fails
    // contrast on a light background.
    val color = when (note.severity) {
        NoticeSeverity.Error, NoticeSeverity.Caution -> MaterialTheme.colorScheme.error
        NoticeSeverity.Info, NoticeSeverity.Success -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val alpha by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "amount-note",
    )
    Text(
        text = note.text,
        style = AmountNoteStyle,
        color = color,
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer { this.alpha = alpha }
            .semantics {
                liveRegion = LiveRegionMode.Polite
                note.spoken?.let { contentDescription = it }
            },
    )
}

/** The note line's height with nothing in it. */
@Composable
fun AmountNoteLinePlaceholder(modifier: Modifier = Modifier) {
    Text(
        text = " ",
        style = AmountNoteStyle,
        modifier = modifier.alpha(0f).clearAndSetSemantics {},
    )
}

/**
 * The secondary line's size and leading, at regular weight (iOS `.body`). For
 * screens that measure how many lines a note will take.
 */
internal val AmountNoteStyle: TextStyle
    @Composable get() = CashuTheme.type.amountSecondary.copy(fontWeight = FontWeight.Normal)

/**
 * The room under an entry amount: the gap and its note or conversion line,
 * plus the lines a note adds when it wraps at [width].
 */
@Composable
fun entryNoteRoom(note: AmountNote?, width: Dp): Dp {
    val density = LocalDensity.current
    val noteStyle = AmountNoteStyle
    val textMeasurer = rememberTextMeasurer()
    val noteLines = note?.let {
        textMeasurer.measure(
            text = it.text,
            style = noteStyle,
            constraints = Constraints(maxWidth = with(density) { width.roundToPx() }.coerceAtLeast(0)),
        ).lineCount
    } ?: 1
    return CashuTheme.spacing.default + with(density) { noteStyle.lineHeight.toDp() } * noteLines
}

/**
 * The largest entry rung whose amount and [noteRoom] fit in [height], for a
 * screen whose amount cell is all the room it has. A short cell steps the
 * amount down rather than squeezing its note line out (Transfer budgets the
 * same way; iOS picks with ViewThatFits). Below Compact the keypad gives up
 * height instead: see [leavingRoomAbove].
 */
@Composable
fun fittingEntryScale(height: Dp, noteRoom: Dp): AmountScale =
    listOf(AmountScale.Hero, AmountScale.Confirm)
        .firstOrNull { amountHeroHeight(it) + noteRoom <= height }
        ?: AmountScale.Compact

/**
 * Live keypad presentation for sat-denominated entry. Primary follows the typed
 * raw string; secondary always keeps the mint-unit alternate when a BTC price
 * is loaded (including "$0.00"), matching iOS `CurrencyAmountDisplay` entry mode.
 */
internal fun AmountFormatter.entryDisplayText(
    entryRaw: String,
    amountSats: Long,
    preferredPrimary: AmountDisplayPrimary,
    btcPrice: Double?,
    currencyCode: String,
    useBitcoinSymbol: Boolean,
): AmountDisplayText {
    val priceAvailable = btcPrice != null && btcPrice > 0
    val effective = if (preferredPrimary == AmountDisplayPrimary.Fiat && priceAvailable) {
        AmountDisplayPrimary.Fiat
    } else {
        AmountDisplayPrimary.Sats
    }
    val satsText = formatWalletSats(amountSats, useBitcoinSymbol = useBitcoinSymbol)
    val fiatSecondary = if (priceAvailable) {
        formatFiat(amountSats, btcPrice, currencyCode)
            ?: formatFiatZero(currencyCode)
    } else {
        null
    }
    // Whole-number-first entry: an untouched pad is "0" in both units. The
    // secondary line below is a settled amount, so it keeps its full fraction.
    val primaryRaw = entryRaw.ifEmpty { "0" }
    return when (effective) {
        AmountDisplayPrimary.Fiat -> AmountDisplayText(
            primary = entryFiatDisplay(primaryRaw, currencyCode),
            secondary = satsText,
            effectivePrimary = effective,
        )
        AmountDisplayPrimary.Sats -> AmountDisplayText(
            primary = entryDisplay(
                raw = primaryRaw,
                isSat = true,
                unit = "sat",
                useBitcoinSymbol = useBitcoinSymbol,
            ),
            secondary = fiatSecondary,
            effectivePrimary = effective,
        )
    }
}

private fun AmountFormatter.formatFiatZero(currencyCode: String): String =
    entryFiatDisplay("0.00", currencyCode)
