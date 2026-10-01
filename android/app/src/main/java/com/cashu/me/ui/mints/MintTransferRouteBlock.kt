package com.cashu.me.ui.mints

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.cashu.me.Core.CDK.mintRemovalUrlsMatch
import com.cashu.me.Core.MintTransferRoute.Slot
import com.cashu.me.Models.MintInfo
import com.cashu.me.ui.components.CashuModalBottomSheet
import com.cashu.me.ui.components.CompactSheetContent
import com.cashu.me.ui.components.FlowSheetTitle
import com.cashu.me.ui.components.MintAvatar
import com.cashu.me.ui.components.bitcoinAmountText
import com.cashu.me.ui.components.morphBlur
import com.cashu.me.ui.components.rememberSheetDismissAction
import com.cashu.me.ui.testing.UiTestTags
import com.cashu.me.ui.theme.CashuTheme
import com.cashu.me.ui.theme.rememberReducedMotion
import com.cashu.me.ui.theme.withMonoDigits

private val IdentityMinHeight = 48.dp
private val SupportMinHeight = 24.dp
private val ChevronSize = 18.dp
private val SwapGlyphSize = 20.dp
private val MinimumTouchTarget = 48.dp
private val MaxProgressSize = 16.dp
private val HairlineThickness = 0.5.dp
private const val DisabledContentAlpha = 0.38f

// A different mint taking a slot: its lines blur out as the new mint's come
// into focus (iOS `.blurReplace` parity).
private val SlotMorphBlur = 4.dp

// Each swap turns the glyph a half turn on a critically damped spring whose
// response matches iOS `.smooth(duration: 0.4)`: no overshoot, so the
// symmetric glyph lands squarely on itself.
private const val SwapHalfTurnDegrees = 180f
private val SwapTurnSpec = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessLow,
)

// The transfer picker keeps the pay flows' viewport: the title and roughly
// four rows. A longer list opens half-height instead and drags to full, so it
// is not read through a four-row slot.
private val PickerAvatarSize = 40.dp
private val PickerSheetHeight = 360.dp
private const val PickerFixedRows = 4

/**
 * The two ends of a transfer, stacked: the mint the ecash leaves, the control
 * that swaps the two, and the mint it arrives at. Top is always From (iOS
 * `MintTransferRouteView` parity).
 *
 * Unlike the centered From/To line on the pay screens, both ends here are the
 * user's own mints and the choice between them is the point of the screen, so
 * each gets its name and balance. No avatar, fill or card: one hairline. When
 * the mints change places each slot's lines morph in place to the new mint's
 * and the glyph makes a half turn (DESIGN.md §6, animation 8); nothing travels
 * between the slots.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun MintTransferRouteBlock(
    source: MintInfo,
    destination: MintInfo,
    sourceBalanceText: String,
    destinationBalanceText: String,
    modifier: Modifier = Modifier,
    // Why an end can't take part as it stands ("too little to cover the fee").
    // Said on that mint's own line, not under the amount: the problem is the
    // mint, not the number.
    sourceProblem: String? = null,
    destinationProblem: String? = null,
    // Short screens keep the destination to its identity line.
    showsDestinationBalance: Boolean = true,
    isFindingMax: Boolean = false,
    onUseMax: (() -> Unit)? = null,
    // Null when there is no other mint to choose; the rows then only inform.
    onChooseSource: (() -> Unit)? = null,
    onChooseDestination: (() -> Unit)? = null,
    // Null when the two mints cannot trade places.
    onSwap: (() -> Unit)? = null,
) {
    // The part of the name row's 48dp target that rises over its caption rather
    // than sitting between the name and its balance, so the balance reads as
    // the name's second line. As text grows the name fills more of the target,
    // until it fills it (iOS parity).
    val nameLine = with(LocalDensity.current) { MaterialTheme.typography.bodyLarge.lineHeight.toDp() }
    val touchOverhang = (IdentityMinHeight - nameLine).coerceAtLeast(0.dp)

    Column(modifier = modifier.fillMaxWidth()) {
        SlotCaption("From")
        Box(modifier = Modifier.trimTop(touchOverhang)) {
            RouteIdentitySlot(mint = source, slot = Slot.Source, onChoose = onChooseSource)
        }
        // The balance line belongs to the mint, so it changes with the name.
        SlotMorph(
            state = SlotLine(source.url, sourceBalanceText, sourceProblem),
            key = SlotLine::mintUrl,
        ) { line ->
            if (line.problem != null) {
                // A mint with a problem has no maximum to offer, so the line
                // is the reason.
                ProblemLine(
                    balanceText = line.balanceText,
                    problem = line.problem,
                    description = "${line.balanceText} available, ${line.problem}",
                )
            } else {
                SourceSupport(
                    balanceText = line.balanceText,
                    isFindingMax = isFindingMax,
                    onUseMax = onUseMax,
                )
            }
        }
        SwapDivider(destination = destination, source = source, onSwap = onSwap)
        SlotCaption("To")
        Box(modifier = Modifier.trimTop(touchOverhang)) {
            RouteIdentitySlot(mint = destination, slot = Slot.Destination, onChoose = onChooseDestination)
        }
        // A short screen drops the balance, never the reason it can't receive.
        if (destinationProblem != null || showsDestinationBalance) {
            SlotMorph(
                state = SlotLine(destination.url, destinationBalanceText, destinationProblem),
                key = SlotLine::mintUrl,
            ) { line ->
                if (line.problem != null) {
                    ProblemLine(
                        balanceText = line.balanceText,
                        problem = line.problem,
                        description = "Balance ${line.balanceText}, ${line.problem}",
                    )
                } else {
                    SupportText(
                        text = line.balanceText,
                        modifier = Modifier
                            .heightIn(min = SupportMinHeight)
                            .clearAndSetSemantics { contentDescription = "Balance ${line.balanceText}" },
                    )
                }
            }
        }
    }
}

/** What a slot's second line shows for the mint in it. */
private data class SlotLine(
    val mintUrl: String,
    val balanceText: String,
    val problem: String?,
)

/**
 * A slot's lines, morphing when a different mint takes the slot: the outgoing
 * mint's blur out as the incoming mint's come into focus, in place (iOS
 * `.blurReplace` parity). Anything else about the same mint (its balance, a
 * problem, the fee check) changes in place without it. `morphBlur` drops the
 * blur under reduce motion and below API 31, leaving the fade.
 */
@Composable
private fun <T> SlotMorph(
    state: T,
    key: (T) -> Any,
    modifier: Modifier = Modifier,
    content: @Composable (T) -> Unit,
) {
    val enter = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val exit = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    AnimatedContent(
        targetState = state,
        contentKey = key,
        transitionSpec = {
            // Unclipped: the lines' touch targets overhang them.
            (fadeIn(enter) togetherWith fadeOut(exit)).using(SizeTransform(clip = false))
        },
        contentAlignment = Alignment.CenterStart,
        modifier = modifier,
        label = "transfer-slot-morph",
    ) { current ->
        Box(modifier = morphBlur(SlotMorphBlur, enterSpec = enter, exitSpec = exit)) {
            content(current)
        }
    }
}

/**
 * Which end a slot is. It belongs to the slot, not the mint, so it stays put
 * while the identities trade places. TalkBack hears it in the identity's own
 * description.
 */
@Composable
private fun SlotCaption(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = CashuTheme.spacing.micro)
            .clearAndSetSemantics {},
    )
}

/**
 * One slot's identity. The slot's node outlives a swap, so a new mint reaches
 * TalkBack as this node's description changing, which its polite live region
 * announces ("To Y").
 */
@Composable
private fun RouteIdentitySlot(
    mint: MintInfo,
    slot: Slot,
    onChoose: (() -> Unit)?,
) {
    val description = "${if (slot == Slot.Source) "From" else "To"} ${mint.name}"
    Box(
        modifier = Modifier.clearAndSetSemantics {
            contentDescription = description
            liveRegion = LiveRegionMode.Polite
            if (onChoose != null) {
                role = Role.Button
                onClick(label = "Choose a different mint") {
                    onChoose()
                    true
                }
            }
        },
    ) {
        RouteIdentity(mint = mint, onChoose = onChoose)
    }
}

@Composable
private fun RouteIdentity(
    mint: MintInfo,
    onChoose: (() -> Unit)?,
) {
    // The row sits at the bottom of its 48dp target; the spare height is the
    // part that overhangs the caption (see `trimTop`).
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = IdentityMinHeight)
            .then(
                if (onChoose != null) {
                    Modifier.clickable(role = Role.Button, onClick = onChoose)
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.BottomStart,
    ) {
        IdentityRow(mint = mint, showsChevron = onChoose != null)
    }
}

@Composable
private fun IdentityRow(mint: MintInfo, showsChevron: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CashuTheme.spacing.default),
    ) {
        // The name belongs to the mint, the chevron to the slot.
        SlotMorph(state = mint, key = MintInfo::url, modifier = Modifier.weight(1f)) { current ->
            Text(
                text = current.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (showsChevron) {
            Icon(
                imageVector = Icons.Outlined.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(ChevronSize),
            )
        }
    }
}

@Composable
private fun SourceSupport(
    balanceText: String,
    isFindingMax: Boolean,
    onUseMax: (() -> Unit)?,
) {
    if (onUseMax != null) {
        AvailableBalance(
            balanceText = balanceText,
            isFindingMax = isFindingMax,
            onUseMax = onUseMax,
        )
    } else {
        // An empty mint has nothing to take: its balance only informs.
        SupportText(
            text = balanceText,
            modifier = Modifier
                .heightIn(min = SupportMinHeight)
                .clearAndSetSemantics { contentDescription = "$balanceText available" },
        )
    }
}

/**
 * The balance is the maximum: tapping it fills the largest amount this mint
 * can transfer after fees (iOS parity). It reads as the plain balance; the
 * whole-balance hint is what teaches the tap. The 48dp target overhangs the
 * line rather than growing it.
 */
@Composable
private fun AvailableBalance(
    balanceText: String,
    isFindingMax: Boolean,
    onUseMax: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .trimVertical((MinimumTouchTarget - SupportMinHeight) / 2)
            .defaultMinSize(minHeight = MinimumTouchTarget)
            .clickable(enabled = !isFindingMax, role = Role.Button, onClick = onUseMax)
            .clearAndSetSemantics {
                contentDescription = "$balanceText available"
                role = Role.Button
                if (isFindingMax) {
                    stateDescription = "Checking the network fee"
                    disabled()
                } else {
                    onClick(label = "Fill the largest amount this mint can transfer after fees") {
                        onUseMax()
                        true
                    }
                }
            }
            .testTag(UiTestTags.MintTransferMax),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CashuTheme.spacing.tight),
    ) {
        Text(
            text = bitcoinAmountText(balanceText),
            style = MaterialTheme.typography.bodyMedium.withMonoDigits(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (isFindingMax) {
            CircularProgressIndicator(
                modifier = Modifier.size(MaxProgressSize),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SupportText(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.CenterStart) {
        Text(
            text = bitcoinAmountText(text),
            // A balance is a money value: at large text it wraps rather than
            // truncating or being clipped at the edge.
            style = MaterialTheme.typography.bodyMedium.withMonoDigits(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}


/**
 * An inline notice moved into the row: the caution glyph carries the colour
 * and the words stay secondary, so the line keeps its contrast. Announced
 * politely when it appears, as `InlineNotice` is.
 */
@Composable
private fun ProblemLine(balanceText: String, problem: String, description: String) {
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    val line = buildAnnotatedString {
        appendInlineContent(CautionGlyphId, "!")
        append(' ')
        append(bitcoinAmountText("$balanceText · $problem"))
    }
    val glyph = mapOf(
        CautionGlyphId to InlineTextContent(
            Placeholder(width = 1.em, height = 1.em, placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter),
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = CashuTheme.colors.pending,
            )
        },
    )
    Box(
        modifier = Modifier
            .heightIn(min = SupportMinHeight)
            .clearAndSetSemantics {
                contentDescription = description
                liveRegion = LiveRegionMode.Polite
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = line,
            inlineContent = glyph,
            style = MaterialTheme.typography.bodyMedium.withMonoDigits(),
            color = secondary,
        )
    }
}

private const val CautionGlyphId = "caution-glyph"

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SwapDivider(
    destination: MintInfo,
    source: MintInfo,
    onSwap: (() -> Unit)?,
) {
    // The captions carry the direction, so the glyph is symmetric and a half
    // turn lands it on itself: beside the press morph, it reads as the flip
    // the tap makes (iOS parity). The container stays still; only the arrows
    // turn.
    val reducedMotion = rememberReducedMotion()
    var turns by remember { mutableIntStateOf(0) }
    // A second tap mid-turn retargets from wherever the glyph is.
    val rotation by animateFloatAsState(
        targetValue = turns * SwapHalfTurnDegrees,
        animationSpec = SwapTurnSpec,
        label = "transfer-swap-turn",
    )
    val swap = {
        if (onSwap != null) {
            onSwap()
            if (!reducedMotion) turns += 1
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CashuTheme.spacing.default),
    ) {
        Hairline(Modifier.weight(1f))
        FilledTonalIconButton(
            onClick = { swap() },
            shapes = IconButtonDefaults.shapes(),
            enabled = onSwap != null,
            modifier = Modifier.semantics {
                contentDescription = "Swap mints"
                if (onSwap != null) {
                    onClick(label = "Transfer from ${destination.name} to ${source.name} instead") {
                        swap()
                        true
                    }
                }
            },
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
        ) {
            Icon(
                imageVector = Icons.Filled.SwapVert,
                contentDescription = null,
                modifier = Modifier
                    .size(SwapGlyphSize)
                    .graphicsLayer { rotationZ = rotation },
            )
        }
        Hairline(Modifier.weight(1f))
    }
}

@Composable
private fun Hairline(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier,
        thickness = HairlineThickness,
        color = CashuTheme.colors.canvasDivider,
    )
}

/**
 * Report a box shorter by [trim] at the top, placing the content so its top
 * overhangs the line above. The touch target keeps its full size; only the
 * height it claims in the column shrinks.
 */
private fun Modifier.trimTop(trim: Dp) = this.layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val trimPx = trim.roundToPx()
    val height = (placeable.height - trimPx).coerceAtLeast(0)
    layout(placeable.width, height) { placeable.place(0, -trimPx) }
}

/**
 * Report a shorter box than was measured, placing the content centred so it
 * overhangs above and below. The touch target keeps its full size; only the
 * height it claims in the line shrinks.
 */
private fun Modifier.trimVertical(trim: Dp) = this.layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val trimPx = trim.roundToPx()
    val height = (placeable.height - trimPx * 2).coerceAtLeast(0)
    layout(placeable.width, height) { placeable.place(0, -trimPx) }
}

/** One held mint offered for an end of a transfer. */
internal data class MintTransferPickerOption(
    val mint: MintInfo,
    val balanceText: String,
    /** Why this mint cannot take the slot, or null when it can. */
    val unavailableReason: String?,
)

/**
 * Chooses the mint for one end of a transfer. Every held mint is listed so the
 * user can see why one is unavailable; those rows carry the reason and do
 * nothing (iOS `MintTransferMintPicker` parity). `MintPickerSheet` has no
 * disabled rows, and gaining them would change it for every pay flow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MintTransferMintPicker(
    slot: Slot,
    options: List<MintTransferPickerOption>,
    selectedMintUrl: String,
    onSelect: (MintInfo) -> Unit,
    onDismiss: () -> Unit,
) {
    val tall = options.size > PickerFixedRows
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = !tall)
    val dismiss = rememberSheetDismissAction(sheetState)
    val haptics = LocalHapticFeedback.current
    CashuModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        sheetGesturesEnabled = !dismiss.isDismissing,
        containerColor = CashuTheme.colors.compactSheetContainer,
    ) {
        CompactSheetContent {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (tall) Modifier.fillMaxHeight() else Modifier.height(PickerSheetHeight))
                    .padding(horizontal = CashuTheme.spacing.comfortable)
                    .navigationBarsPadding(),
            ) {
                FlowSheetTitle(title = if (slot == Slot.Source) "Transfer from" else "Transfer to")
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(options, key = { it.mint.url }) { option ->
                        PickerRow(
                            option = option,
                            selected = mintRemovalUrlsMatch(option.mint.url, selectedMintUrl),
                            onClick = {
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                dismiss { onSelect(option.mint) }
                            },
                        )
                    }
                }
                Spacer(Modifier.height(CashuTheme.spacing.default))
            }
        }
    }
}

@Composable
private fun PickerRow(
    option: MintTransferPickerOption,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val available = option.unavailableReason == null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = available, role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { this.selected = selected }
            .padding(
                horizontal = CashuTheme.spacing.snug,
                vertical = CashuTheme.spacing.default,
            )
            .alpha(if (available) 1f else DisabledContentAlpha),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CashuTheme.spacing.default),
    ) {
        MintAvatar(mint = option.mint, size = PickerAvatarSize)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = option.mint.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = bitcoinAmountText(option.unavailableReason ?: option.balanceText),
                style = MaterialTheme.typography.bodySmall.withMonoDigits(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        if (selected) {
            // The row's selected state already says it.
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(CashuTheme.spacing.loose),
            )
        }
    }
}
