package com.cashu.me.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import coil.decode.DataSource
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.cashu.me.Core.MintLogoBitmapCache
import com.cashu.me.Models.MintInfo

private const val MintAvatarFadeMs = 250

/**
 * Round avatar for a mint. Loads [MintInfo.iconUrl] through Coil into
 * [MintLogoBitmapCache], keeps the last decoded bitmap across recompositions, and
 * crossfades when the visible image appears or changes. Falls back to a
 * deterministic HSL monogram (or bank glyph if the name is empty).
 *
 * The active-mint dot overlay is added by the caller via [Box] sibling, since the
 * dot color depends on theme tokens and the avatar should remain composition-shape-stable.
 */
@Composable
fun MintAvatar(
    mint: MintInfo,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    // Chip/row sizes stay circular; hero sizes (mint detail) square off to the
    // on-scale medium shape token.
    val shape = if (size <= 48.dp) CircleShape else MaterialTheme.shapes.medium
    val context = LocalContext.current
    val iconUrl = mint.iconUrl?.takeIf { it.isNotBlank() }

    Box(
        modifier = modifier
            .size(size)
            .clip(shape),
        contentAlignment = Alignment.Center,
    ) {
        if (iconUrl == null) {
            GeneratedFallback(mint = mint, size = size)
            return@Box
        }

        val sizePx = with(LocalDensity.current) { size.roundToPx().coerceAtLeast(1) }
        // Sync process-wide hit — first frame after tab remount shows the logo,
        // matching iOS `CachedAsyncImage`'s memory seed.
        var bitmap by remember(iconUrl) {
            mutableStateOf(MintLogoBitmapCache.get(iconUrl))
        }

        LaunchedEffect(iconUrl, sizePx) {
            val hadCachedFrame = bitmap != null
            val result = context.imageLoader.execute(
                ImageRequest.Builder(context)
                    .data(iconUrl)
                    .size(sizePx)
                    .memoryCacheKey(iconUrl)
                    .diskCacheKey(iconUrl)
                    .build(),
            )
            if (result is SuccessResult) {
                val decoded = result.drawable.toBitmap()
                // Keep the seeded frame on remount (disk or memory). Only swap when
                // the network returns a fresher image so icon updates still fade in.
                if (hadCachedFrame && result.dataSource != DataSource.NETWORK) {
                    bitmap?.let { MintLogoBitmapCache.put(iconUrl, it) }
                    return@LaunchedEffect
                }
                MintLogoBitmapCache.put(iconUrl, decoded)
                bitmap = decoded
            }
        }

        Crossfade(
            targetState = bitmap,
            animationSpec = tween(MintAvatarFadeMs),
            label = "mintAvatar",
        ) { current: Bitmap? ->
            if (current != null) {
                Image(
                    bitmap = current.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                GeneratedFallback(mint = mint, size = size)
            }
        }
    }
}

@Composable
private fun GeneratedFallback(mint: MintInfo, size: Dp) {
    val initial = mint.name.firstOrNull()?.uppercase()
    val seed = mint.url.hashCode().toLong() and 0xFFFFFFFFL
    val hue = (seed % 360L).toFloat()
    val backgroundColor = remember(hue) { hslToColor(hue, saturation = 0.45f, lightness = 0.40f) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor),
        contentAlignment = Alignment.Center,
    ) {
        if (initial.isNullOrBlank()) {
            Icon(
                imageVector = Icons.Outlined.AccountBalance,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(size * 0.5f),
            )
        } else {
            Text(
                text = initial,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

private fun hslToColor(h: Float, saturation: Float, lightness: Float): Color {
    val c = (1f - kotlin.math.abs(2 * lightness - 1f)) * saturation
    val x = c * (1f - kotlin.math.abs((h / 60f) % 2 - 1f))
    val m = lightness - c / 2f
    val (r1, g1, b1) = when {
        h < 60f -> Triple(c, x, 0f)
        h < 120f -> Triple(x, c, 0f)
        h < 180f -> Triple(0f, c, x)
        h < 240f -> Triple(0f, x, c)
        h < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Color(r1 + m, g1 + m, b1 + m)
}
