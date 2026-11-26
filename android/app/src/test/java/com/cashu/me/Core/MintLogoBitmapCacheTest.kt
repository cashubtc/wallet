package com.cashu.me.Core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MintLogoBitmapCacheTest {
    @Test
    fun decodeSizePolicyUsesShared256pxCeiling() {
        assertEquals(256, mintLogoDecodeSizePx(0))
        assertEquals(256, mintLogoDecodeSizePx(1))
        assertEquals(256, mintLogoDecodeSizePx(66))
        assertEquals(256, mintLogoDecodeSizePx(120))
        assertEquals(256, mintLogoDecodeSizePx(216))
        assertEquals(256, mintLogoDecodeSizePx(256))
        assertEquals(320, mintLogoDecodeSizePx(320))
        assertEquals(512, mintLogoDecodeSizePx(512))
    }

    @Test
    fun cachedLogoSufficiencyCoversCrispHitsAndRejectsUndersizedBitmaps() {
        // 256x256 decoded at ceiling satisfies every standard surface (chip → hero).
        assertTrue(isCachedMintLogoSufficient(widthPx = 256, heightPx = 256, decodedMaxPx = 256, requestedSizePx = 66))
        assertTrue(isCachedMintLogoSufficient(widthPx = 256, heightPx = 256, decodedMaxPx = 256, requestedSizePx = 216))
        assertTrue(isCachedMintLogoSufficient(widthPx = 256, heightPx = 256, decodedMaxPx = 256, requestedSizePx = 256))

        // Smaller or rectangular source already decoded at the 256px ceiling avoids repeat Coil decodes.
        assertTrue(isCachedMintLogoSufficient(widthPx = 128, heightPx = 128, decodedMaxPx = 256, requestedSizePx = 216))
        assertTrue(isCachedMintLogoSufficient(widthPx = 256, heightPx = 128, decodedMaxPx = 256, requestedSizePx = 216))

        // Tiny legacy-sized entry (not decoded at ceiling) must re-decode for larger surfaces.
        assertFalse(isCachedMintLogoSufficient(widthPx = 66, heightPx = 66, decodedMaxPx = 66, requestedSizePx = 216))
        assertTrue(isCachedMintLogoSufficient(widthPx = 66, heightPx = 66, decodedMaxPx = 66, requestedSizePx = 66))

        // Surface larger than 256px upgrades when cached entry is capped at 256px.
        assertFalse(isCachedMintLogoSufficient(widthPx = 256, heightPx = 256, decodedMaxPx = 256, requestedSizePx = 320))
        assertTrue(isCachedMintLogoSufficient(widthPx = 320, heightPx = 320, decodedMaxPx = 320, requestedSizePx = 320))

        // Zero-dimension entries are never sufficient.
        assertFalse(isCachedMintLogoSufficient(widthPx = 0, heightPx = 256, decodedMaxPx = 256, requestedSizePx = 66))
        assertFalse(isCachedMintLogoSufficient(widthPx = 256, heightPx = 0, decodedMaxPx = 256, requestedSizePx = 66))
    }

    @Test
    fun bitmapSizeKbRoundsUpWithOneKbFloor() {
        assertEquals(1, bitmapSizeKb(0))
        assertEquals(1, bitmapSizeKb(1))
        assertEquals(1, bitmapSizeKb(1024))
        assertEquals(2, bitmapSizeKb(1025))
        // 256 * 256 * 4 bytes (ARGB_8888) = 256 KiB.
        assertEquals(256, bitmapSizeKb(256 * 256 * 4))
    }

    @Test
    fun sizedLruCacheStoresRetrievesEvictsLeastRecentlyUsedAndClears() {
        val cache = SizedLruCache<String, Pair<String, Int>>(
            maxSizeKb = 512,
            sizeOfKb = { it.second },
        )

        cache.put("mint-a", "logo-a" to 256)
        cache.put("mint-b", "logo-b" to 256)
        assertEquals("logo-a" to 256, cache.get("mint-a"))
        assertEquals(512, cache.sizeKb())
        assertEquals(2, cache.count())

        // Accessing mint-a promoted it, so inserting mint-c evicts mint-b.
        cache.put("mint-c", "logo-c" to 256)
        assertEquals("logo-a" to 256, cache.get("mint-a"))
        assertNull(cache.get("mint-b"))
        assertEquals("logo-c" to 256, cache.get("mint-c"))
        assertEquals(512, cache.sizeKb())

        // Replacing an existing key updates size accounting cleanly.
        cache.put("mint-c", "logo-c-small" to 128)
        assertEquals("logo-c-small" to 128, cache.get("mint-c"))
        assertEquals(384, cache.sizeKb())

        // Oversize entry does not evict existing cache entries, and removes stale key if overwritten.
        cache.put("mint-a", "too-big" to 1024)
        assertNull(cache.get("mint-a"))
        assertEquals("logo-c-small" to 128, cache.get("mint-c"))
        assertEquals(128, cache.sizeKb())

        cache.clear()
        assertNull(cache.get("mint-c"))
        assertEquals(0, cache.sizeKb())
        assertEquals(0, cache.count())
    }
}
