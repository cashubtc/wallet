package com.cashu.me.Core.CDK

import com.cashu.me.Core.LOCAL_NEVER_EXPIRES_EPOCH_SECONDS
import com.cashu.me.Models.PaymentMethodKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.cashudevkit.Amount as CdkAmount
import org.cashudevkit.CurrencyUnit as CdkCurrencyUnit
import org.cashudevkit.MintQuote as CdkMintQuote
import org.cashudevkit.MintUrl as CdkMintUrl
import org.cashudevkit.PaymentMethod as CdkPaymentMethod
import org.cashudevkit.QuoteState as CdkQuoteState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MintQuoteCdkMetadataTest {
    @Test
    fun paidStatusReloadUsesCurrentVersionAndAllowsMetadataWrite() = runBlocking {
        var stored = quote(amount = null, paymentMethod = CdkPaymentMethod.Onchain, amountPaid = CdkAmount(1_000uL))
        repeat(3) {
            val checked = stored
            val refreshed = refreshPersistedMintQuote(
                check = {
                    stored = checked.copy(version = checked.version + 1u)
                    checked
                },
                reload = { stored },
            )
            assertTrue(refreshed.version > checked.version)
            val normalized = persistMintQuoteMetadata(
                refreshed, PaymentMethodKind.Onchain, null,
                save = {
                    check(it.version == stored.version) { "Concurrent update" }
                    stored = it.copy(version = it.version + 1u)
                },
                reload = { stored },
            )
            assertEquals(stored, normalized)
            assertEquals(1_000uL, normalized.amountPaid.value)
            assertTrue(normalized.hasUnissuedOnchainCredit())
        }
    }

    @Test
    fun statusReloadKeepsRecoveryCountersAndClearedReservation() = runBlocking {
        val old = quote(usedByOperation = "operation", amountPaid = CdkAmount(21uL))
        val recovered = old.copy(version = 2u, usedByOperation = null, amountIssued = CdkAmount(21uL))
        val refreshed = refreshPersistedMintQuote(check = { old }, reload = { recovered })
        assertEquals(recovered, refreshed)
        assertNull(refreshed.usedByOperation)
        assertFalse(refreshed.hasUnissuedOnchainCredit())
    }

    @Test
    fun metadataWritePreservesActiveReservationAndSigningKey() = runBlocking {
        val reserved = quote(amount = null, paymentMethod = CdkPaymentMethod.Onchain,
            amountPaid = CdkAmount(21uL), usedByOperation = "operation", secretKey = "secret")
        var saved = reserved
        persistMintQuoteMetadata(reserved, PaymentMethodKind.Onchain, null,
            save = { saved = it.copy(version = it.version + 1u) }, reload = { saved })
        assertEquals("operation", saved.usedByOperation)
        assertEquals("secret", saved.secretKey)
    }

    @Test
    fun metadataWriteFailureIsNotSwallowedOrRetried() = runBlocking {
        val paid = quote(amount = null, paymentMethod = CdkPaymentMethod.Onchain, amountPaid = CdkAmount(21uL))
        var attempts = 0
        val failure = IllegalStateException("Concurrent update")
        try {
            persistMintQuoteMetadata(paid, PaymentMethodKind.Onchain, null,
                save = { attempts++; throw failure }, reload = { fail("Must not continue after a failed write"); null })
            fail("Expected write failure")
        } catch (error: IllegalStateException) {
            assertTrue(error === failure)
        }
        assertEquals(1, attempts)
    }

    @Test
    fun statusReloadFailsClosedForMissingQuoteOrReadFailure() = runBlocking {
        try {
            refreshPersistedMintQuote(check = { quote() }, reload = { null })
            fail("Expected missing quote failure")
        } catch (_: CdkGatewayUnavailable) { }
        val failure = IllegalStateException("Database unavailable")
        try {
            refreshPersistedMintQuote(check = { quote() }, reload = { throw failure })
            fail("Expected read failure")
        } catch (error: IllegalStateException) {
            assertTrue(error === failure)
        }
    }

    @Test
    fun cancelledStatusCheckDoesNotReloadOrContinue() = runBlocking {
        try {
            refreshPersistedMintQuote(check = { throw CancellationException() },
                reload = { fail("Must not reload after cancellation"); null })
            fail("Expected cancellation")
        } catch (_: CancellationException) { }
    }

    @Test
    fun bolt12ZeroExpiryIsNormalizedToLocalNeverExpiresSentinelForStorage() {
        val quote = quote(expiry = 0uL, paymentMethod = CdkPaymentMethod.Bolt12)

        val normalized = quote.withLocalMintQuoteMetadata(PaymentMethodKind.Bolt12)

        assertEquals(LOCAL_NEVER_EXPIRES_EPOCH_SECONDS.toULong(), normalized.expiry)
    }

    @Test
    fun onchainLocalMetadataStoresFallbackAmountWhenCdkOmitsAmount() {
        val quote = quote(amount = null, paymentMethod = CdkPaymentMethod.Onchain)

        val normalized = quote.withLocalMintQuoteMetadata(PaymentMethodKind.Onchain, fallbackAmount = 42)

        assertEquals(42uL, normalized.amount?.value)
    }

    @Test
    fun onchainLocalMetadataPrefersCreditedAmountBeforeFallback() {
        val quote = quote(
            amount = null,
            paymentMethod = CdkPaymentMethod.Onchain,
            amountPaid = CdkAmount(21uL),
        )

        val normalized = quote.withLocalMintQuoteMetadata(PaymentMethodKind.Onchain, fallbackAmount = 42)

        assertEquals(21uL, normalized.amount?.value)
    }

    @Test
    fun onchainCreditIsMintableOnlyWhenPaidExceedsIssued() {
        assertFalse(
            quote(
                paymentMethod = CdkPaymentMethod.Onchain,
                amountPaid = CdkAmount(21uL),
                amountIssued = CdkAmount(21uL),
            ).hasUnissuedOnchainCredit(),
        )
        assertTrue(
            quote(
                paymentMethod = CdkPaymentMethod.Onchain,
                amountPaid = CdkAmount(21uL),
                amountIssued = CdkAmount(0uL),
            ).hasUnissuedOnchainCredit(),
        )
    }

    private fun quote(
        request: String = "request",
        amount: CdkAmount? = CdkAmount(1uL),
        expiry: ULong = 100uL,
        paymentMethod: CdkPaymentMethod = CdkPaymentMethod.Bolt11,
        amountPaid: CdkAmount = CdkAmount(0uL),
        amountIssued: CdkAmount = CdkAmount(0uL),
        estimatedBlocks: UInt? = null,
        secretKey: String? = null,
        usedByOperation: String? = null,
    ) = CdkMintQuote(
        id = "quote-id",
        amount = amount,
        unit = CdkCurrencyUnit.Sat,
        request = request,
        state = CdkQuoteState.UNPAID,
        expiry = expiry,
        mintUrl = CdkMintUrl("https://mint.example.com"),
        amountIssued = amountIssued,
        amountPaid = amountPaid,
        updatedAt = 0uL,
        estimatedBlocks = estimatedBlocks,
        paymentMethod = paymentMethod,
        secretKey = secretKey,
        usedByOperation = usedByOperation,
        version = 0u,
    )
}
