package com.cashu.me.Core.NfcReceive

import com.cashu.me.Models.CashuRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NfcReceiveTermsTest {
    @Test
    fun `selected mint is received directly`() {
        val request = request(mints = listOf("https://mint.example/"))
        assertEquals(
            NfcSettlementRoute.Direct,
            validateNfcReceiveTerms(request, "https://mint.example", "sat", 21, "https://active.example"),
        )
    }

    @Test
    fun `any mint request routes foreign token to settlement mint`() {
        assertEquals(
            NfcSettlementRoute.Foreign,
            validateNfcReceiveTerms(request(), "https://foreign.example", "sat", 21, "https://active.example"),
        )
    }

    @Test
    fun `fixed amount rejects insufficient token`() {
        val failure = runCatching {
            validateNfcReceiveTerms(request(amount = 21), "https://active.example", "sat", 20, "https://active.example")
        }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("at least 21"))
    }

    @Test
    fun `strict request rejects foreign mint`() {
        val failure = runCatching {
            validateNfcReceiveTerms(
                request(mints = listOf("https://accepted.example")),
                "https://foreign.example",
                "sat",
                21,
                "https://active.example",
            )
        }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("selected mint"))
    }

    private fun request(amount: Long? = null, mints: List<String> = emptyList()) = CashuRequest(
        id = "request",
        encoded = "creqA",
        amount = amount,
        unit = "sat",
        mints = mints,
    )
}
