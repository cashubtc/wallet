package com.cashu.me.Core

import android.graphics.Bitmap
import android.util.LruCache

/**
 * Process-wide decoded mint logos. Coil's disk cache alone still goes through an
 * async Loading frame when a composable remounts; this seeds the first frame from
 * memory (iOS `MintLogoCache` parity). Cleared on wallet delete.
 */
object MintLogoBitmapCache {
    private val lock = Any()
    private val cache = LruCache<String, Bitmap>(64)

    fun get(url: String): Bitmap? = synchronized(lock) { cache.get(url) }

    fun put(url: String, bitmap: Bitmap) {
        synchronized(lock) { cache.put(url, bitmap) }
    }

    fun clear() {
        synchronized(lock) { cache.evictAll() }
    }
}
