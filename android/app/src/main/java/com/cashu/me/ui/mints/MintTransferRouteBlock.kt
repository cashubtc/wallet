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
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.cashu.me.Core.MintTransferRoute.Slot
import com.cashu.me.Models.MintInfo
import com.cashu.me.ui.components.CanvasDivider
import com.cashu.me.ui.components.bitcoinAmountText
import com.cashu.me.ui.components.morphBlur
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
// Max's pill around its word, and the least gap it keeps from the balance
// before it drops under it.
private val MaxLabelPadding = 12.dp
private val MaxGap = 12.dp
// A text link's cross-fade mask, as GhostButton's: lighter than a CTA's.
private val MaxMorphBlur = 1.5.dp
private const val MaxLabel = "Max"
private const val UsingMaxLabel = "Using Max"

private val MaxLabelStyle: TextStyle
    @Composable get() = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium)

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
    // The amount is the one Max filled, until it changes.
    isUsingMax: Boolean = false,
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
                    isUsingMax = isUsingMax,
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
    // The chevron follows the name, as on Send's "From {mint}", which leaves
    // the trailing edge to Max on the line below (iOS parity).
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CashuTheme.spacing.micro),
    ) {
        // The name belongs to the mint, the chevron to the slot.
        SlotMorph(state = mint, key = MintInfo::url, modifier = Modifier.weight(1f, fill = false)) { current ->
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
    isUsingMax: Boolean,
    onUseMax: (() -> Unit)?,
) {
    val balance: @Composable () -> Unit = {
        SupportText(
            text = balanceText,
            modifier = Modifier
                .heightIn(min = SupportMinHeight)
                .clearAndSetSemantics { contentDescription = "$balanceText available" },
        )
    }
    if (onUseMax == null) {
        // An empty mint has nothing to take: its balance only informs.
        balance()
        return
    }
    // Max sits at the end of the balance's line, as on Send. Side by side
    // while both fit; at large text Max drops under the balance rather than
    // squeezing it (iOS parity). Whether they fit is judged on the widest
    // label, so "Using Max" landing never moves Max to another line.
    val widestLabel = rememberTextMeasurer()
        .measure(UsingMaxLabel, MaxLabelStyle, maxLines = 1, softWrap = false)
        .size.width
    Layout(
        content = {
            balance()
            MaxControl(isFindingMax = isFindingMax, isUsingMax = isUsingMax, onUseMax = onUseMax)
        },
        modifier = Modifier.fillMaxWidth(),
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val balanceLine = measurables[0].measure(loose)
        val max = measurables[1].measure(loose)
        val padding = MaxLabelPadding.roundToPx()
        val reserved = maxOf(widestLabel + padding * 2, MinimumTouchTarget.roundToPx(), max.width)
        val width = constraints.maxWidth
        // The pill overhangs the content edge by its padding, so the word
        // itself lines up with the edge, as the balance does.
        if (balanceLine.width + MaxGap.roundToPx() + reserved <= width) {
            val height = maxOf(balanceLine.height, max.height)
            layout(width, height) {
                balanceLine.placeRelative(0, (height - balanceLine.height) / 2)
                max.placeRelative(width - max.width + padding, (height - max.height) / 2)
            }
        } else {
            layout(width, balanceLine.height + max.height) {
                balanceLine.placeRelative(0, 0)
                max.placeRelative(-padding, balanceLine.height)
            }
        }
    }
}

/**
 * Fills the largest amount this mint can transfer after fees, a text button
 * like Send's Max. Once used it reads "Using Max", dimmed, until the amount
 * changes: that is why the amount came out under the balance (Family's
 * pattern, iOS parity). The word, the spinner and "Using Max" cross-fade
 * through one another, as a button's label and its spinner do.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MaxControl(
    isFindingMax: Boolean,
    isUsingMax: Boolean,
    onUseMax: () -> Unit,
) {
    val label = if (isUsingMax) UsingMaxLabel else MaxLabel
    // Captured outside the non-composable transitionSpec lambda.
    val enterSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val exitSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val sizeSpec = MaterialTheme.motionScheme.defaultSpatialSpec<IntSize>()
    // The pill claims only the balance's line. The framework still widens its
    // touch to 48dp wherever no other target is hit, but a tap on the name row
    // above stays the name row's: a 48dp box overhanging the line took the
    // bottom of those taps.
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides Dp.Unspecified) {
        TextButton(
            onClick = onUseMax,
            enabled = !isFindingMax && !isUsingMax,
            modifier = Modifier
                .defaultMinSize(minWidth = MinimumTouchTarget, minHeight = SupportMinHeight)
                // Before clearAndSetSemantics, which would clear a tag set after it.
                .testTag(UiTestTags.MintTransferMax)
                // Named by its visible word, so Voice Access can say it; what
                // it does is the action's label.
                .clearAndSetSemantics {
                    contentDescription = label
                    role = Role.Button
                    if (isFindingMax) {
                        stateDescription = "Checking the network fee"
                        disabled()
                    } else if (isUsingMax) {
                        disabled()
                    } else {
                        onClick(label = "Fill the largest amount this mint can transfer after fees") {
                            onUseMax()
                            true
                        }
                    }
                },
            contentPadding = PaddingValues(horizontal = MaxLabelPadding),
        ) {
            AnimatedContent(
                // Null while the fee is checked: the spinner is a morph target,
                // like the label it replaces.
                targetState = if (isFindingMax) null else label,
                transitionSpec = {
                    fadeIn(enterSpec)
                        .togetherWith(fadeOut(exitSpec))
                        // Unclipped and centred, so the wider label is not
                        // sheared or walked sideways while the pill resizes.
                        .using(SizeTransform(clip = false) { _, _ -> sizeSpec })
                },
                contentAlignment = Alignment.Center,
                label = "transfer-max-label",
            ) { current ->
                val morph = morphBlur(MaxMorphBlur, enterSpec = enterSpec, exitSpec = exitSpec)
                if (current == null) {
                    LoadingIndicator(
                        modifier = Modifier.size(MaxProgressSize).then(morph),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = current,
                        style = MaxLabelStyle,
                        color = if (current == UsingMaxLabel) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        modifier = morph,
                    )
                }
            }
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
 * The balance, then the problem in red words with no glyph, as the note under
 * the amount is written (iOS parity). Announced politely when it appears, as
 * `InlineNotice` is.
 */
@Composable
private fun ProblemLine(balanceText: String, problem: String, description: String) {
    val secondary = MaterialTheme.colorScheme.onSurfaceVariant
    val problemColor = MaterialTheme.colorScheme.error
    val line = buildAnnotatedString {
        append(bitcoinAmountText("$balanceText · "))
        withStyle(SpanStyle(color = problemColor)) { append(problem) }
    }
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
            style = MaterialTheme.typography.bodyMedium.withMonoDigits(),
            color = secondary,
        )
    }
}

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
        CanvasDivider(modifier = Modifier.weight(1f), leadingInset = 0.dp, trailingInset = 0.dp)
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
        CanvasDivider(modifier = Modifier.weight(1f), leadingInset = 0.dp, trailingInset = 0.dp)
    }
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
