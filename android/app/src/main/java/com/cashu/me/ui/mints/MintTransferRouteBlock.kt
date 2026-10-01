package com.cashu.me.ui.mints

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.SwapVert
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalInspectionMode
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.zIndex
import com.cashu.me.Core.CDK.mintRemovalUrlsMatch
import com.cashu.me.Core.MintTransferRoute.Slot
import com.cashu.me.Models.MintInfo
import com.cashu.me.ui.components.CashuModalBottomSheet
import com.cashu.me.ui.components.CompactSheetContent
import com.cashu.me.ui.components.FlowSheetTitle
import com.cashu.me.ui.components.MintAvatar
import com.cashu.me.ui.components.bitcoinAmountText
import com.cashu.me.ui.components.rememberSheetDismissAction
import com.cashu.me.ui.theme.CashuTheme
import com.cashu.me.ui.theme.rememberReducedMotion
import com.cashu.me.ui.theme.withMonoDigits

private val RouteAvatarSize = 32.dp
private val IdentityMinHeight = 48.dp
private val SupportMinHeight = 24.dp
private val ChevronSize = 18.dp
private val SwapGlyphSize = 20.dp
private val MinimumTouchTarget = 48.dp
private val MaxProgressSize = 16.dp
private val HairlineThickness = 0.5.dp
private const val DisabledContentAlpha = 0.38f
// Where the app's other rows switch to their stacked, large-text layout.
private const val LargeTextScale = 1.3f

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
 * each gets its avatar, name and balance. No fill or card: one hairline.
 *
 * When the mints trade slots the two identities travel to each other's place
 * on the spatial spring, while the From/To captions and the balance lines stay
 * with their slots and change in place. Reduced motion cross-fades the
 * identities where they sit.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun MintTransferRouteBlock(
    source: MintInfo,
    destination: MintInfo,
    sourceBalanceText: String,
    destinationBalanceText: String,
    modifier: Modifier = Modifier,
    // What the destination will hold once the amount arrives; null while there
    // is no amount ready to send.
    destinationAfterText: String? = null,
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
    val travels = !rememberReducedMotion() && !LocalInspectionMode.current
    val pair = source.url to destination.url
    val previousPair = remember { arrayOf(pair) }
    val tradedPlaces = previousPair[0] != pair &&
        previousPair[0].first == pair.second && previousPair[0].second == pair.first
    SideEffect { previousPair[0] = pair }

    // Seeded at the old positions in the same composition the mints trade
    // places in, so no frame ever shows them already swapped and at rest.
    val travel = remember(pair) { Animatable(if (tradedPlaces && travels) 1f else 0f) }
    val travelSpec = MaterialTheme.motionScheme.defaultSpatialSpec<Float>()
    LaunchedEffect(travel) { travel.animateTo(0f, travelSpec) }
    // A mint picked from the list has no old place to travel from: it fades in.
    val identityFade = if (tradedPlaces && travels) snap() else MaterialTheme.motionScheme.defaultEffectsSpec<Float>()

    // Measured on wrappers outside the travelling layers, so the offset being
    // animated never feeds back into the distance it is derived from.
    var sourceIdentityY by remember { mutableFloatStateOf(0f) }
    var destinationIdentityY by remember { mutableFloatStateOf(0f) }
    // Travelling rows pass over the lines between them. At rest they sit
    // below, so Max keeps the part of its touch target that overhangs them.
    val identityZ = if (travel.isRunning) 1f else 0f

    Column(modifier = modifier.fillMaxWidth()) {
        SlotCaption("From")
        Box(
            modifier = Modifier
                .zIndex(identityZ)
                .onGloballyPositioned { sourceIdentityY = it.positionInParent().y },
        ) {
            RouteIdentitySlot(
                mint = source,
                slot = Slot.Source,
                fade = identityFade,
                onChoose = onChooseSource,
                modifier = Modifier.graphicsLayer {
                    translationY = travel.value * (destinationIdentityY - sourceIdentityY)
                },
            )
        }
        SourceSupport(
            balanceText = sourceBalanceText,
            isFindingMax = isFindingMax,
            onUseMax = onUseMax,
        )
        SwapDivider(destination = destination, source = source, animates = travels, onSwap = onSwap)
        SlotCaption("To")
        Box(
            modifier = Modifier
                .zIndex(identityZ)
                .onGloballyPositioned { destinationIdentityY = it.positionInParent().y },
        ) {
            RouteIdentitySlot(
                mint = destination,
                slot = Slot.Destination,
                fade = identityFade,
                onChoose = onChooseDestination,
                modifier = Modifier.graphicsLayer {
                    translationY = travel.value * (sourceIdentityY - destinationIdentityY)
                },
            )
        }
        if (showsDestinationBalance) {
            DestinationSupport(balanceText = destinationBalanceText, afterText = destinationAfterText)
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
            .clearAndSetSemantics {},
    )
}

/**
 * One slot's identity, keyed on the mint so a new name or logo updates in place.
 *
 * The slot, not the faded content, carries the semantics: it outlives the swap,
 * so a new mint reaches TalkBack as this node's description changing, which its
 * polite live region announces ("To Y"). A node that faded in fresh would not be.
 */
@Composable
private fun RouteIdentitySlot(
    mint: MintInfo,
    slot: Slot,
    fade: FiniteAnimationSpec<Float>,
    onChoose: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val description = "${if (slot == Slot.Source) "From" else "To"} ${mint.name}"
    AnimatedContent(
        targetState = mint,
        modifier = modifier.clearAndSetSemantics {
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
        transitionSpec = { fadeIn(fade) togetherWith fadeOut(fade) },
        contentKey = { it.url },
        label = "transfer-route-identity",
    ) { current ->
        RouteIdentity(mint = current, onChoose = onChoose)
    }
}

@Composable
private fun RouteIdentity(
    mint: MintInfo,
    onChoose: (() -> Unit)?,
) {
    Row(
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
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CashuTheme.spacing.default),
    ) {
        MintAvatar(mint = mint, size = RouteAvatarSize)
        Text(
            text = mint.name,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (onChoose != null) {
            Icon(
                imageVector = Icons.Outlined.KeyboardArrowDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(ChevronSize),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourceSupport(
    balanceText: String,
    isFindingMax: Boolean,
    onUseMax: (() -> Unit)?,
) {
    val supportInset = Modifier
        .fillMaxWidth()
        .padding(start = RouteAvatarSize + CashuTheme.spacing.default)
    val balance: @Composable () -> Unit = {
        SupportText(
            text = "$balanceText available",
            modifier = Modifier.heightIn(min = SupportMinHeight),
        )
    }
    // Side by side at ordinary text sizes; at large text Max drops under the
    // balance rather than truncating it.
    if (LocalDensity.current.fontScale >= LargeTextScale) {
        Column(modifier = supportInset) {
            balance()
            if (onUseMax != null) MaxControl(isFindingMax, onUseMax, Alignment.CenterStart)
        }
    } else {
        FlowRow(
            modifier = supportInset,
            horizontalArrangement = Arrangement.SpaceBetween,
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            balance()
            if (onUseMax != null) {
                MaxControl(
                    isFindingMax = isFindingMax,
                    onUseMax = onUseMax,
                    alignment = Alignment.CenterEnd,
                    // The 48dp target must not grow the line it shares.
                    modifier = Modifier.trimVertical((MinimumTouchTarget - SupportMinHeight) / 2),
                )
            }
        }
    }
}

@Composable
private fun MaxControl(
    isFindingMax: Boolean,
    onUseMax: () -> Unit,
    alignment: Alignment,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = MinimumTouchTarget, minHeight = MinimumTouchTarget)
            .clickable(enabled = !isFindingMax, role = Role.Button, onClick = onUseMax)
            .clearAndSetSemantics {
                contentDescription = "Transfer maximum"
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
            },
        contentAlignment = alignment,
    ) {
        // The word keeps its place under the spinner, so the line never shifts.
        Text(
            text = "Max",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.alpha(if (isFindingMax) 0f else 1f),
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
 * What the destination holds, and what it will hold once the amount arrives:
 * the transfer read as a change in place, not a separate sum.
 */
@Composable
private fun DestinationSupport(balanceText: String, afterText: String?) {
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    val line = buildAnnotatedString {
        append(bitcoinAmountText("Balance $balanceText"))
        if (afterText != null) {
            append(' ')
            appendInlineContent(AfterArrowId, "→")
            append(' ')
            withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurface)) {
                append(bitcoinAmountText(afterText))
            }
        }
    }
    val arrow = mapOf(
        AfterArrowId to InlineTextContent(
            Placeholder(width = 1.em, height = 1.em, placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = secondary,
            )
        },
    )
    Box(
        modifier = Modifier
            .padding(start = RouteAvatarSize + CashuTheme.spacing.default)
            .heightIn(min = SupportMinHeight)
            .clearAndSetSemantics {
                contentDescription = if (afterText == null) {
                    "Balance $balanceText"
                } else {
                    "Balance $balanceText, $afterText after transfer"
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = line,
            inlineContent = arrow,
            style = MaterialTheme.typography.bodyMedium.withMonoDigits(),
            color = secondary,
        )
    }
}

private const val AfterArrowId = "after-arrow"

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SwapDivider(
    destination: MintInfo,
    source: MintInfo,
    animates: Boolean,
    onSwap: (() -> Unit)?,
) {
    var turns by remember { mutableIntStateOf(0) }
    // A half turn per tap: the captions carry the direction, so the glyph is
    // symmetric and half a turn reads as the flip it is. Fast-spatial stiffness
    // on the critically damped effects spring: Expressive's spatial springs
    // overshoot, and the glyph has to land square.
    val rotation by animateFloatAsState(
        targetValue = turns * 180f,
        animationSpec = if (animates) MaterialTheme.motionScheme.slowEffectsSpec() else snap(),
        label = "transfer-route-swap-turn",
    )
    val swap = {
        turns += 1
        onSwap?.invoke()
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
