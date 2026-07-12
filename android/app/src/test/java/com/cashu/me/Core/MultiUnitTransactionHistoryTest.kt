package com.cashu.me.Core

import com.cashu.me.Models.MintInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class MultiUnitTransactionHistoryTest {
    @Test
    fun historyEnumeratesSatAndEveryAdvertisedMintUnit() {
        val unitsByMint = transactionUnitsByMint(
            listOf(
                MintInfo(
                    url = "https://mint.example.com",
                    units = listOf("usd", "EUR", "points"),
                ),
            ),
        )

        assertEquals(
            listOf("sat", "usd", "EUR", "points"),
            unitsByMint.getValue("https://mint.example.com"),
        )
    }

    @Test
    fun historyUnitEnumerationDropsEmptyAndCaseInsensitiveDuplicates() {
        val unitsByMint = transactionUnitsByMint(
            listOf(
                MintInfo(
                    url = "https://mint.example.com",
                    units = listOf(" SAT ", "usd", "USD", " "),
                ),
            ),
        )

        assertEquals(
            listOf("sat", "usd"),
            unitsByMint.getValue("https://mint.example.com"),
        )
    }
}
