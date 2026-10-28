package com.cashu.me.ui.send

import com.cashu.me.Core.CashuPaymentRequestSummary
import com.cashu.me.Core.routeForCashuPaymentRequest
import com.cashu.me.Models.MintInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CashuRequestDisplayMintTest {
    private val active = MintInfo(url = "https://active.example", name = "Active", balance = 100)
    private val requested = MintInfo(url = "https://requested.example", name = "Requested", balance = 50)
    private val mints = listOf(active, requested)

    private fun display(request: CashuPaymentRequestSummary, amount: Long? = null): MintInfo? {
        val route = routeForCashuPaymentRequest(
            rawRequest = request.encoded,
            request = request,
            mints = mints,
            selectedMintUrl = null,
            activeMintUrl = active.url,
            amountSats = amount,
        )
        return cashuRequestDisplayMint(route, request, mints, null, active.url, active)
    }

    @Test
    fun amountlessRequestShowsRequestedMintBeforeAndAfterEnteringAmount() {
        val request = CashuPaymentRequestSummary(
            encoded = "creqA-test", unit = "sat", mints = listOf("https://requested.example/"),
        )

        assertEquals(requested, display(request))
        assertEquals(requested, display(request, amount = 20))
        assertEquals(requested, display(request.copy(amount = 20)))
    }

    @Test
    fun amountlessRequestWithoutTrackedCompatibleMintDoesNotShowUnrelatedMint() {
        val request = CashuPaymentRequestSummary(
            encoded = "creqA-test", unit = "sat", mints = listOf("https://other.example"),
        )

        assertNull(display(request))
    }

    @Test
    fun amountlessAnyMintRequestUsesActiveMint() {
        val request = CashuPaymentRequestSummary(encoded = "creqA-test", unit = "sat")

        assertEquals(active, display(request))
    }
}
