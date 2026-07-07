package org.cashu.wallet.ui.mints

import org.cashu.wallet.Models.MintInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class MintDetailScreenTest {
    @Test
    fun refreshWithNewerMetadataMarksConnectionOnline() {
        val before = 1_000L
        val refreshed = MintInfo(
            url = "https://mint.example",
            lastUpdatedEpochMillis = 2_000L,
        )

        assertEquals(MintConnectionState.Online, mintConnectionStateAfterRefresh(before, refreshed))
    }

    @Test
    fun missingOrStaleRefreshMarksConnectionOffline() {
        val before = 1_000L

        assertEquals(MintConnectionState.Offline, mintConnectionStateAfterRefresh(before, null))
        assertEquals(
            MintConnectionState.Offline,
            mintConnectionStateAfterRefresh(
                before,
                MintInfo(url = "https://mint.example", lastUpdatedEpochMillis = before),
            ),
        )
    }
}
