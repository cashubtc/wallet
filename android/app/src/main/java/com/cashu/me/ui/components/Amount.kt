package com.cashu.me.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.cashu.me.Core.AmountFormatter
import com.cashu.me.ui.theme.CashuFonts
import com.cashu.me.ui.theme.CashuTheme
import com.cashu.me.ui.theme.withMonoDigits
import com.cashu.me.ui.theme.BitcoinSymbol
import com.cashu.me.ui.theme.rememberReducedMotion

/** Keep the existing Bitcoin face at the surrounding text's size and weight. */
internal fun bitcoinAmountText(text: String): AnnotatedString = buildAnnotatedString {
    append(text)
    text.forEachIndexed { index, character ->
        if (character == '₿') {
            addStyle(SpanStyle(fontFamily = BitcoinSymbol), index, index + 1)
        }
    }
}

/**
 * Lowers each run of private mode's asterisks onto the digits' centre line.
 *
 * Faces draw `*` up near the cap line, so unlowered asterisks read as a hidden
 * amount jumping up when they replace the digits. `BaselineShift` is a multiple
 * of the ascent (see [CashuFonts.ascentRatio]) and moves the glyphs without
 * changing the line's height, so hiding never reflows a row. A sign beside the
 * asterisks keeps its own position.
 */
internal fun AnnotatedString.withLoweredHiddenDigits(fonts: CashuFonts): AnnotatedString {
    val mask = AmountFormatter.HiddenDigits
    var start = text.indexOf(mask)
    if (start < 0) return this
    val lowered = SpanStyle(baselineShift = BaselineShift(-fonts.asteriskDropRatio / fonts.ascentRatio))
    return buildAnnotatedString {
        append(this@withLoweredHiddenDigits)
        while (start >= 0) {
            addStyle(lowered, start, start + mask.length)
            start = text.indexOf(mask, start + mask.length)
        }
    }
}

/**
 * Monospaced-digit amount text. Use everywhere balances, amounts, and fees
 * appear. Cross-fades on change — the same quiet, no-slide transition the
 * app's other amount swaps already use (see [AmountFlipDisplay], [BalanceDisplay]).
 *
 * @param annotated a pre-composed styled string to render in place of [text],
 *   used by [AmountHero] to set the value and its unit as two runs of one
 *   string. [text] still supplies the cross-fade key and the fallback
 *   accessibility reading, so the animation keys on the value rather than on
 *   incidental styling.
 * @param semanticsLabel replaces the node's reading entirely. Amount strings
 *   contain glyphs like `₿` that announce as nothing useful, so a hero should
 *   always pass a spoken form.
 * @param rollDigits roll changed digits instead of cross-fading the whole
 *   amount (iOS `.numericText`); see [RollingAmountText]. Reduced motion keeps
 *   the cross-fade, which [animated] then governs.
 */
@Composable
fun AmountText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = Color.Unspecified,
    animated: Boolean = true,
    rollDigits: Boolean = false,
    maxLines: Int = Int.MAX_VALUE,
    // Ellipsis, not Clip: a hard cut mid-glyph reads as a rendering fault,
    // and every caller that had thought about it was already overriding this.
    overflow: TextOverflow = TextOverflow.Ellipsis,
    autoSize: TextAutoSize? = null,
    annotated: AnnotatedString? = null,
    semanticsLabel: String? = null,
) {
    val resolvedColor = if (color == Color.Unspecified) LocalContentColor.current else color
    val finalStyle = style.withMonoDigits().copy(color = resolvedColor)
    val styledText = (annotated ?: bitcoinAmountText(text)).withLoweredHiddenDigits(CashuTheme.fonts)
    val contentAlignment = when (finalStyle.textAlign) {
        TextAlign.Center -> Alignment.Center
        TextAlign.End, TextAlign.Right -> Alignment.CenterEnd
        else -> Alignment.CenterStart
    }
    val semantics = semanticsLabel?.let { label ->
        Modifier.clearAndSetSemantics { contentDescription = label }
    } ?: Modifier
    // Auto-size needs a bounded width; fill so the Text sees the parent's max.
    val textModifier = (if (autoSize != null) Modifier.fillMaxWidth() else Modifier).then(semantics)

    if (rollDigits && !rememberReducedMotion()) {
        RollingAmountText(
            text = styledText,
            style = finalStyle,
            modifier = modifier.then(textModifier),
            maxLines = maxLines,
            overflow = overflow,
            autoSize = autoSize,
        )
        return
    }
    if (!animated) {
        Text(
            text = styledText,
            style = finalStyle,
            modifier = modifier.then(textModifier),
            maxLines = maxLines,
            overflow = overflow,
            autoSize = autoSize,
        )
        return
    }
    AnimatedContent(
        // Each state carries the styled string it was composed with, and
        // `contentKey` keys the transition on the plain value alone — so a
        // styling change still does not trigger a fade.
        //
        // Reading `annotated` from the enclosing scope instead was a flicker on
        // every keypress. AnimatedContent keeps the outgoing content composed
        // while it fades and re-invokes this lambda for it, passing the *old*
        // value while `text` and `annotated` have already advanced — so an
        // `targetText == text` test fails for the outgoing number every time and
        // drops it to an unstyled string. The unit snapped from half-size,
        // one-weight-down, secondary ink up to full size and full ink at the
        // instant it began to disappear, and the wider unstyled string could
        // take a different autosize step on the way out.
        targetState = text to styledText,
        contentKey = { it.first },
        transitionSpec = {
            fadeIn(spring(stiffness = Spring.StiffnessMedium))
                .togetherWith(fadeOut(spring(stiffness = Spring.StiffnessMedium)))
        },
        modifier = modifier,
        contentAlignment = contentAlignment,
        label = "amount-text",
    ) { (_, styled) ->
        Text(
            text = styled,
            style = finalStyle,
            modifier = textModifier,
            maxLines = maxLines,
            overflow = overflow,
            autoSize = autoSize,
        )
    }
}
