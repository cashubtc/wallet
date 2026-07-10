package com.cashu.me.Core

import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CashuRequestListenerTest {
    @Test
    fun paymentPayloadBuildsCashuATokenAndPreservesRequestId() {
        val result = CashuRequestListener.paymentPayloadToToken(
            """
            {
              "id": "request-1",
              "memo": "Thanks",
              "mint": "https://mint.example.com",
              "unit": "sat",
              "proofs": [{"amount":1,"id":"keyset","secret":"secret","C":"commitment"}]
            }
            """.trimIndent(),
        )

        assertEquals("request-1", result.requestId)
        assertEquals("https://mint.example.com", result.mintUrl)
        assertEquals(1L, result.amount)
        assertEquals("sat", result.unit)
        assertEquals("Thanks", result.memo)
        assertTrue(result.token.startsWith("cashuA"))
        val payload = String(Base64.getUrlDecoder().decode(result.token.removePrefix("cashuA")), Charsets.UTF_8)
        val fields = Json.parseToJsonElement(payload).jsonObject
        val entry = fields["token"]!!.jsonArray.first().jsonObject
        assertEquals("https://mint.example.com", entry["mint"]!!.jsonPrimitive.content)
        assertEquals("sat", fields["unit"]!!.jsonPrimitive.content)
        assertEquals("Thanks", fields["memo"]!!.jsonPrimitive.content)
        assertEquals(1, entry["proofs"]!!.jsonArray.size)
    }

    @Test
    fun onlyKnownMintsAutoClaimWithoutConsent() {
        assertEquals(
            CashuRequestClaimAction.ClaimSilently,
            CashuRequestApprovalPolicy.action(autoClaim = true, mintKnown = true),
        )
        assertEquals(
            CashuRequestClaimAction.HoldForApproval,
            CashuRequestApprovalPolicy.action(autoClaim = false, mintKnown = true),
        )
        assertEquals(
            CashuRequestClaimAction.HoldForApproval,
            CashuRequestApprovalPolicy.action(autoClaim = true, mintKnown = false),
        )
    }
}
