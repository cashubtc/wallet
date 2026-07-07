package org.cashu.wallet.Core

import org.cashu.wallet.Models.CashuRequest
import org.cashu.wallet.Models.CashuRequestPayment
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentRequestBuilderTest {
    @Test
    fun nprofileEncodesPubkeyAndRelays() {
        val pubkeyHex = NostrService.publicKeyHex(PRIVATE_KEY_HEX)
        val nprofile = PaymentRequestBuilder.makeNprofile(
            pubkeyHex = pubkeyHex,
            relays = listOf("wss://relay.example"),
        )
        val decoded = Bech32.decode("nprofile", nprofile)

        assertEquals(0, decoded[0].toInt())
        assertEquals(32, decoded[1].toInt())
        assertEquals(pubkeyHex, decoded.copyOfRange(2, 34).hex())
        assertEquals(1, decoded[34].toInt())
        assertEquals("wss://relay.example".length, decoded[35].toInt())
    }

    @Test
    fun builderCreatesSwiftCompatibleCashuRequestEncoding() {
        val encoded = PaymentRequestBuilder.build(
            id = "request-id",
            amount = 42,
            unit = "sat",
            mints = listOf("https://mint.example"),
            description = "coffee",
            nostrPubkeyHex = NostrService.publicKeyHex(PRIVATE_KEY_HEX),
            relays = listOf("wss://relay.example"),
        )

        assertEquals(
            "creqApmFpanJlcXVlc3QtaWRhYRgqYXVjc2F0YW2BdGh0dHBzOi8vbWludC5leGFtcGxlYWRmY29mZmVlYXSBo2F0ZW5vc3RyYWF4Z25wcm9maWxlMXFxczhuMG54MG11YWV3YXYya3N4OTl3d3N1OXN3cTVtbG5kam1uM2dtOXZsOXEybXptdXAweHFwemRtaHh1ZTY5dWhoeWV0dnY5dWp1ZXRjdjlraHFtcjk5bXZ0NTRhZ4GCYW5iMTc",
            encoded,
        )
        val cbor = Base64.getUrlDecoder().decode(encoded.removePrefix("creqA"))
        assertEquals(0xA6.toByte(), cbor.first())
    }

    @Test
    fun lockedReceiveRequestEncodesNut10AndParses() {
        val p2pkPubkey = "02${"a".repeat(64)}"
        val encoded = LockedReceiveRequest.build(
            id = "testid01",
            amount = 21,
            primaryP2pkPubkeyHex = p2pkPubkey,
            nostrPubkeyHex = "b".repeat(64),
            relays = listOf("wss://relay.example.com"),
        )

        assertNotNull(encoded)
        val request = encoded!!
        assertTrue(request.startsWith("creqA"))
        val cbor = Base64.getUrlDecoder().decode(request.removePrefix("creqA"))
        assertTrue(cbor.bytesContain("nut10"))
        assertTrue(cbor.bytesContain("P2PK"))
        assertTrue(cbor.bytesContain(p2pkPubkey))

        val parsed = PaymentRequestDecoder.cashuPaymentRequestSummary(request)
        assertNotNull(parsed)
        assertEquals(21L, parsed?.amount)
        assertEquals("sat", parsed?.unit)
    }

    @Test
    fun cashuRequestLegacyPaymentIdsArePreservedAsZeroAmountPayments() {
        val request = CashuRequest(
            id = "abc",
            encoded = "creqA-test",
            createdAtEpochMillis = 1234,
            receivedPaymentIds = listOf("legacy-tx"),
        ).withLegacyPaymentFallback()

        assertEquals(
            listOf(CashuRequestPayment("legacy-tx", amount = 0, receivedAtEpochMillis = 1234)),
            request.receivedPayments,
        )
    }

    private companion object {
        private const val PRIVATE_KEY_HEX = "0000000000000000000000000000000000000000000000000000000000000001"

        private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

        private fun ByteArray.bytesContain(needle: String): Boolean {
            val pin = needle.toByteArray(Charsets.UTF_8)
            if (pin.isEmpty() || size < pin.size) return false
            return indices.any { start ->
                start + pin.size <= size && pin.indices.all { offset -> this[start + offset] == pin[offset] }
            }
        }
    }
}
