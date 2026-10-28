package com.cashu.me.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * How far a changed digit travels as it rolls, as a fraction of the line box.
 * Short enough that the old and new digit overlap mid-roll, as `.numericText`'s
 * do, rather than reading as two separate slides.
 */
private const val RollTravel = 0.4f

/**
 * The iOS `.snappy` response without its bounce: a balance that overshoots
 * reads as a miscount.
 */
private val RollSpec = spring<Float>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow,
)

/** [AmountText]'s cross-fade, for changes that are not a value change within one format. */
private val CrossFadeSpec = spring<Float>(stiffness = Spring.StiffnessMedium)

/**
 * [AmountText] whose digits roll on a value change, the Android twin of iOS
 * `.contentTransition(.numericText(value:))`: digits that changed roll up as the
 * amount grows and down as it shrinks, everything else holds and slides as the
 * centred amount re-centres, and a glyph the amount gains or loses on the left
 * fades. A change of format (sats ↔ fiat, another unit) cross-fades as before.
 *
 * The one [Text] stays the single source of truth: it lays out (including
 * [TextAutoSize]), owns the semantics and draws the amount at rest. Only while a
 * change animates does this draw instead — the outgoing and incoming layouts it
 * captured, clipped glyph by glyph to the same positions the [Text] put them —
 * so the resting render is pixel-for-pixel the plain [Text].
 */
@Composable
internal fun RollingAmountText(
    text: AnnotatedString,
    style: TextStyle,
    modifier: Modifier,
    maxLines: Int,
    overflow: TextOverflow,
    autoSize: TextAutoSize?,
) {
    val scope = rememberCoroutineScope()
    val roll = remember { DigitRollState(scope) }
    Text(
        text = text,
        style = style,
        modifier = modifier
            .onPlaced { roll.coordinates = it }
            .drawWithContent { roll.draw(this) },
        maxLines = maxLines,
        overflow = overflow,
        autoSize = autoSize,
        onTextLayout = roll::onTextLayout,
    )
}

private sealed interface AmountChange {
    data class Roll(val plan: DigitRollPlan) : AmountChange
    data object CrossFade : AmountChange
}

private class AmountTransition(
    val from: TextLayoutResult,
    val to: TextLayoutResult,
    val change: AmountChange,
    /** Where the outgoing layout was drawn, in root coordinates. */
    val fromRootX: Float,
    val progress: Animatable<Float, *>,
)

private class DigitRollState(private val scope: CoroutineScope) {
    var coordinates: LayoutCoordinates? = null
    private var laidOut: TextLayoutResult? = null
    private var lastDrawnRootX = Float.NaN
    private var transition by mutableStateOf<AmountTransition?>(null)
    private var job: Job? = null

    fun onTextLayout(result: TextLayoutResult) {
        val previous = laidOut
        laidOut = result
        if (previous == null) return
        if (previous.layoutInput.text.text == result.layoutInput.text.text) {
            // Same amount, new constraints: keep animating toward the fresh layout.
            transition?.let { transition = AmountTransition(it.from, result, it.change, it.fromRootX, it.progress) }
            return
        }
        // A new amount mid-roll starts from the one that was arriving.
        start(from = previous, to = result)
    }

    private fun start(from: TextLayoutResult, to: TextLayoutResult) {
        val plan = digitRollPlan(from.layoutInput.text.text, to.layoutInput.text.text)
        // A layout that auto-sized to another step, or ran out of room, has
        // glyphs of another size or none at all: cross-fade it.
        val rollable = plan != null &&
            from.lineCount == 1 && to.lineCount == 1 &&
            !from.isLineEllipsized(0) && !to.isLineEllipsized(0) &&
            from.getLineBaseline(0) == to.getLineBaseline(0) &&
            from.size.height == to.size.height
        val change = if (rollable) AmountChange.Roll(plan!!) else AmountChange.CrossFade
        val progress = Animatable(0f)
        transition = AmountTransition(from, to, change, lastDrawnRootX, progress)
        job?.cancel()
        job = scope.launch {
            progress.animateTo(1f, if (rollable) RollSpec else CrossFadeSpec)
            transition = null
        }
    }

    fun draw(scope: ContentDrawScope) = with(scope) {
        val rootX = coordinates?.takeIf { it.isAttached }?.positionInRoot()?.x ?: 0f
        val current = transition
        if (current == null) {
            drawContent()
        } else {
            // The outgoing layout was drawn where this node used to sit; a
            // wrap-content amount re-centres its node the moment it re-measures.
            val shift = if (current.fromRootX.isNaN()) 0f else current.fromRootX - rootX
            val p = current.progress.value
            when (val change = current.change) {
                AmountChange.CrossFade -> {
                    translate(left = shift) { drawLayer(current.from, alpha = 1f - p) }
                    drawLayer(current.to, alpha = p)
                }
                is AmountChange.Roll -> drawRoll(current, change.plan, shift, p)
            }
        }
        lastDrawnRootX = rootX
    }

    private fun DrawScope.drawRoll(t: AmountTransition, plan: DigitRollPlan, shift: Float, p: Float) {
        val travel = (t.to.getLineBottom(0) - t.to.getLineTop(0)) * RollTravel
        // Up: the new digit rises from below while the old one leaves upward.
        val sign = if (plan.direction == DigitRollDirection.Up) 1f else -1f
        for (piece in plan.pieces) {
            when (piece) {
                is DigitRollPiece.Hold -> {
                    val from = t.from.band(piece.from)
                    val to = t.to.band(piece.to)
                    val x = lerp(from.left + shift, to.left, p)
                    drawGlyphs(t.to, to, dx = x - to.left)
                }
                is DigitRollPiece.Roll -> {
                    val from = t.from.band(piece.from..piece.from)
                    val to = t.to.band(piece.to..piece.to)
                    val x = lerp(from.left + shift, to.left, p)
                    drawGlyphs(t.from, from, dx = x - from.left, dy = -sign * travel * p, alpha = 1f - p)
                    drawGlyphs(t.to, to, dx = x - to.left, dy = sign * travel * (1f - p), alpha = p)
                }
                is DigitRollPiece.Enter -> {
                    val anchorFrom = t.from.band(piece.anchor.from..piece.anchor.from)
                    val anchorTo = t.to.band(piece.anchor.to..piece.anchor.to)
                    val dx = (anchorFrom.left + shift - anchorTo.left) * (1f - p)
                    drawGlyphs(t.to, t.to.band(piece.to..piece.to), dx = dx, alpha = p)
                }
                is DigitRollPiece.Exit -> {
                    val anchorFrom = t.from.band(piece.anchor.from..piece.anchor.from)
                    val anchorTo = t.to.band(piece.anchor.to..piece.anchor.to)
                    val x = lerp(anchorFrom.left + shift, anchorTo.left, p)
                    drawGlyphs(t.from, t.from.band(piece.from..piece.from), dx = x - anchorFrom.left, alpha = 1f - p)
                }
            }
        }
    }

    /**
     * Paints only [band] of [layout], moved by [dx], [dy]. Clipping the whole
     * layout keeps the glyphs exactly as the [Text] shaped them — kerning, the
     * `₿` face, the unit's styled run — rather than re-measuring each one.
     */
    private fun DrawScope.drawGlyphs(
        layout: TextLayoutResult,
        band: Rect,
        dx: Float,
        dy: Float = 0f,
        alpha: Float = Float.NaN,
    ) {
        translate(left = dx, top = dy) {
            clipRect(left = band.left, top = -size.height, right = band.right, bottom = size.height * 2) {
                // The value's own glyphs carry only the base colour, which the
                // alpha modulates; held runs (the unit) draw untouched.
                drawText(layout, alpha = alpha)
            }
        }
    }

    /**
     * A whole layout at [alpha], through a layer so styled runs fade with the
     * rest: `drawText`'s alpha reaches only the base colour, not a span's.
     */
    private fun DrawScope.drawLayer(layout: TextLayoutResult, alpha: Float) {
        val bounds = Rect(0f, -size.height, size.width.coerceAtLeast(layout.size.width.toFloat()), size.height * 2)
        drawIntoCanvas { canvas ->
            canvas.saveLayer(bounds, Paint().apply { this.alpha = alpha.coerceIn(0f, 1f) })
            drawText(layout)
            canvas.restore()
        }
    }
}

/** The horizontal stretch [range] occupies in this layout. */
private fun TextLayoutResult.band(range: IntRange): Rect {
    val first = getBoundingBox(range.first)
    val last = getBoundingBox(range.last)
    return Rect(first.left, first.top, last.right, last.bottom)
}
