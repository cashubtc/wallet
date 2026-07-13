package com.cashu.me.Core

import com.cashu.me.Core.Protocols.CurrencyAmount
import com.cashu.me.Core.Protocols.PaymentRequest
import com.cashu.me.Core.Protocols.PaymentStatus
import com.cashu.me.Core.Protocols.capabilityLabel
import com.cashu.me.Core.Protocols.iconName
import com.cashu.me.Models.PaymentMethodKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentMethodProtocolTest {
    @Test
    fun paymentMethodKindsExposeStableIconAndCapabilityLabels() {
        assertEquals("bolt", PaymentMethodKind.Bolt11.iconName)
        assertEquals("bolt12", PaymentMethodKind.Bolt12.iconName)
        assertEquals("bitcoin", PaymentMethodKind.Onchain.iconName)

        // capabilityLabel aliases friendlyTitle (iOS receive picker copy).
        assertEquals("Lightning invoice", PaymentMethodKind.Bolt11.capabilityLabel)
        assertEquals("Reusable invoice", PaymentMethodKind.Bolt12.capabilityLabel)
        assertEquals("On-chain address", PaymentMethodKind.Onchain.capabilityLabel)
    }

    @Test
    fun paymentMethodKindsExposeIosParityFriendlyCopy() {
        assertEquals("Lightning invoice", PaymentMethodKind.Bolt11.friendlyTitle)
        assertEquals("Reusable invoice", PaymentMethodKind.Bolt12.friendlyTitle)
        assertEquals("On-chain address", PaymentMethodKind.Onchain.friendlyTitle)

        assertEquals("One-time, instant", PaymentMethodKind.Bolt11.friendlyDescriptor)
        assertEquals("Any amount, paid many times", PaymentMethodKind.Bolt12.friendlyDescriptor)
        assertEquals("Slower, for larger amounts", PaymentMethodKind.Onchain.friendlyDescriptor)

        assertEquals("Create invoice", PaymentMethodKind.Bolt11.createActionTitle)
        assertEquals("Create invoice", PaymentMethodKind.Bolt12.createActionTitle)
        assertEquals("Create address", PaymentMethodKind.Onchain.createActionTitle)
    }

    @Test
    fun amountlessRailsDoNotRequireMintAmount() {
        assertTrue(PaymentMethodKind.Bolt11.requiresMintAmount)
        assertFalse(PaymentMethodKind.Bolt12.requiresMintAmount)
        assertFalse(PaymentMethodKind.Onchain.requiresMintAmount)
        assertTrue(PaymentMethodKind.Bolt12.supportsOptionalMintAmount)
        assertFalse(PaymentMethodKind.Bolt11.supportsOptionalMintAmount)
    }

    @Test
    fun paymentRequestExpiryMatchesSwiftNilAndDateBehavior() {
        val amount = CurrencyAmount.sats(21)
        val noExpiry = PaymentRequest(
            id = "request-1",
            paymentRail = PaymentMethodKind.Bolt11.rawValue,
            amount = amount,
            encodedRequest = "lnbc",
        )
        val expired = noExpiry.copy(expiresAtEpochMillis = 1_000)
        val active = noExpiry.copy(expiresAtEpochMillis = 3_000)

        assertFalse(noExpiry.isExpired(nowEpochMillis = 2_000))
        assertTrue(expired.isExpired(nowEpochMillis = 2_000))
        assertFalse(active.isExpired(nowEpochMillis = 2_000))
    }

    @Test
    fun paymentStatusConvenienceFlagsMatchSwiftCases() {
        assertTrue(PaymentStatus.Pending.isPending)
        assertFalse(PaymentStatus.Pending.isCompleted)
        assertTrue(PaymentStatus.Completed(preimage = "abc").isCompleted)
        assertFalse(PaymentStatus.Failed("no route").isCompleted)
        assertFalse(PaymentStatus.Expired.isPending)
    }
}
