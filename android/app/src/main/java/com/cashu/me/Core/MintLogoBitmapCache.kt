package com.cashu.me.Core

import android.graphics.Bitmap

/**
 * Process-wide decoded mint logos. Coil's disk cache alone still goes through an
 * async Loading frame when a composable remounts; this seeds the first frame from
 * memory (iOS `MintLogoCache` parity). Cleared on wallet delete.
 *
 * Logos decode at a shared crisp ceiling ([DECODE_CEILING_PX], matching iOS
 * `MintLogoCache`'s 256px ceiling) so a small chip never poisons the cache with
 * a low-resolution bitmap for larger avatars, and the in-memory store is bounded
 * by decoded KiB ([MAX_CACHE_SIZE_KB]) rather than entry count.
 */
object MintLogoBitmapCache {
    /**
     * Upper bound on the shared decoded thumbnail edge, in pixels. Mint logos
     * render up to 72dp (≈216px @3x); 256 keeps every surface crisp while
     * bounding memory when a mint serves an oversized image.
     */
    const val DECODE_CEILING_PX: Int = 256

    /** Total decoded bitmap budget in KiB (4 MiB). */
    const val MAX_CACHE_SIZE_KB: Int = 4 * 1024

    internal data class Entry(
        val bitmap: Bitmap,
        val widthPx: Int,
        val heightPx: Int,
        val decodedMaxPx: Int,
    )

    private val cache = SizedLruCache<String, Entry>(
        maxSizeKb = MAX_CACHE_SIZE_KB,
        sizeOfKb = { bitmapSizeKb(it.bitmap.allocationByteCount) },
    )

    /** Shared decode target for a mint logo displayed at [requestedSizePx]. */
    fun decodeSizePx(requestedSizePx: Int): Int =
        mintLogoDecodeSizePx(requestedSizePx)

    fun get(url: String): Bitmap? = cache.get(url)?.bitmap

    /**
     * Returns the cached bitmap if it is already crisp enough for
     * [requestedSizePx] — either its decoded dimensions cover [requestedSizePx]
     * or it was already decoded at the shared ceiling for this request.
     */
    fun getIfSufficient(url: String, requestedSizePx: Int): Bitmap? {
        val entry = cache.get(url) ?: return null
        return if (
            isCachedMintLogoSufficient(
                widthPx = entry.widthPx,
                heightPx = entry.heightPx,
                decodedMaxPx = entry.decodedMaxPx,
                requestedSizePx = requestedSizePx,
            )
        ) {
            entry.bitmap
        } else {
            null
        }
    }

    fun put(
        url: String,
        bitmap: Bitmap,
        decodedMaxPx: Int = minOf(bitmap.width, bitmap.height),
    ) {
        cache.put(
            url,
            Entry(
                bitmap = bitmap,
                widthPx = bitmap.width,
                heightPx = bitmap.height,
                decodedMaxPx = decodedMaxPx,
            ),
        )
    }

    fun clear() {
        cache.clear()
    }
}

internal fun mintLogoDecodeSizePx(requestedSizePx: Int): Int =
    maxOf(requestedSizePx.coerceAtLeast(1), MintLogoBitmapCache.DECODE_CEILING_PX)

internal fun isCachedMintLogoSufficient(
    widthPx: Int,
    heightPx: Int,
    decodedMaxPx: Int,
    requestedSizePx: Int,
): Boolean {
    if (widthPx <= 0 || heightPx <= 0) return false
    val needed = requestedSizePx.coerceAtLeast(1)
    val ceiling = mintLogoDecodeSizePx(needed)
    return minOf(widthPx, heightPx) >= needed || decodedMaxPx >= ceiling
}

internal fun bitmapSizeKb(allocationByteCount: Int): Int =
    ((allocationByteCount.toLong().coerceAtLeast(1L) + 1023L) / 1024L).toInt().coerceAtLeast(1)

/**
 * Thread-safe access-order LRU cache bounded by KiB budget. Mirrors
 * `android.util.LruCache`'s size-based eviction semantics while remaining pure
 * JVM so unit tests can exercise storage, retrieval, and eviction directly.
 */
internal class SizedLruCache<K : Any, V : Any>(
    private val maxSizeKb: Int,
    private val sizeOfKb: (V) -> Int,
) {
    init {
        require(maxSizeKb > 0) { "maxSizeKb must be > 0" }
    }

    private val lock = Any()
    private val entries = LinkedHashMap<K, Pair<V, Int>>(16, 0.75f, true)
    private var currentSizeKb: Int = 0

    fun get(key: K): V? = synchronized(lock) { entries[key]?.first }

    fun put(key: K, value: V) {
        val itemKb = sizeOfKb(value).coerceAtLeast(1)
        synchronized(lock) {
            val previous = entries.remove(key)
            if (previous != null) {
                currentSizeKb -= previous.second
            }
            if (itemKb > maxSizeKb) {
                return
            }
            entries[key] = value to itemKb
            currentSizeKb += itemKb
            trimToSizeLocked()
        }
    }

    fun clear() {
        synchronized(lock) {
            entries.clear()
            currentSizeKb = 0
        }
    }

    internal fun sizeKb(): Int = synchronized(lock) { currentSizeKb }

    internal fun count(): Int = synchronized(lock) { entries.size }

    private fun trimToSizeLocked() {
        val iterator = entries.entries.iterator()
        while (currentSizeKb > maxSizeKb && iterator.hasNext()) {
            val eldest = iterator.next()
            iterator.remove()
            currentSizeKb -= eldest.value.second
        }
    }
}
