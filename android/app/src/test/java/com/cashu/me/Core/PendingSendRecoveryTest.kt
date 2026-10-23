package com.cashu.me.Core

import com.cashu.me.Models.SagaTransactionId
import com.cashu.me.Models.TransactionStatus
import com.cashu.me.Core.Wallet.walletMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PendingSendRecoveryTest {
    private val context = PendingSendRecovery("https://mint.example", "sat")
    private fun snapshot(outcome: SendRecoveryOutcome, ids: List<String> = emptyList()) = PendingSendRecovery(
        context.mintUrl, context.unit, ids,
        if (outcome == SendRecoveryOutcome.Pending) listOf(PendingSend("send-1", 21, "test-token")) else emptyList(), outcome,
    )

    @Test fun pendingSendBlocksPreparationAndPreservesToken() = runBlocking {
        var prepares = 0
        val failure = runCatching {
            performRecoverableSend(context, { snapshot(SendRecoveryOutcome.Pending, listOf("send-1")) }, { fail("Must not reclaim") }) {
                prepares++; 21
            }
        }.exceptionOrNull() as PendingSendRecovery
        assertEquals(0, prepares)
        assertEquals("test-token", failure.sends.single().token)
        assertFalse(failure.toString().contains("test-token"))
        assertEquals(listOf("send-1"), failure.operationIds)
    }

    @Test fun failureAfterTokenCreationAndRepeatedRetryPrepareOnlyOnce() = runBlocking {
        var pending = false
        var prepares = 0
        repeat(2) {
            val failure = runCatching {
                performRecoverableSend(context, {
                    snapshot(if (pending) SendRecoveryOutcome.Pending else SendRecoveryOutcome.Returned, if (pending) listOf("send-1") else it)
                }, {}) { remember ->
                    prepares++
                    remember("send-1")
                    pending = true
                    error("delivery failed")
                }
            }.exceptionOrNull() as PendingSendRecovery
            assertEquals(SendRecoveryOutcome.Pending, failure.outcome)
            assertEquals(listOf("send-1"), failure.operationIds)
        }
        assertEquals(1, prepares)
    }

    @Test fun compensatedProofReservationAllowsFreshAttempt() = runBlocking {
        var prepares = 0
        var recovered = false
        val failure = runCatching {
            performRecoverableSend(context, { snapshot(SendRecoveryOutcome.Returned) }, { recovered = true }) { remember ->
                prepares++; remember("reserved"); error("transport")
            }
        }.exceptionOrNull()
        assertEquals("transport", failure?.message)
        assertTrue(recovered)
        val result = performRecoverableSend(context, { snapshot(SendRecoveryOutcome.Returned) }, {}) { prepares++; 21 }
        assertEquals(21, result)
        assertEquals(2, prepares)
    }

    @Test fun claimedOperationIsTerminalAfterDeliveryError() = runBlocking {
        var attempted = false
        val failure = runCatching {
            performRecoverableSend(context, {
                snapshot(if (attempted) SendRecoveryOutcome.Completed else SendRecoveryOutcome.Returned, it)
            }, {}) { remember -> remember("delivered"); attempted = true; error("delivery") }
        }.exceptionOrNull() as PendingSendRecovery
        assertEquals(SendRecoveryOutcome.Completed, failure.outcome)
        assertEquals(listOf("delivered"), failure.operationIds)
        assertTrue(failure.walletMessage.isTerminal)
    }

    @Test fun failedPreflightReadNeverPrepares() = runBlocking {
        val failure = runCatching {
            performRecoverableSend(context, { error("database") }, {}) { fail("Must not prepare") }
        }.exceptionOrNull() as PendingSendRecovery
        assertEquals(SendRecoveryOutcome.Unverified, failure.outcome)
    }

    @Test fun recoveryFailureRetainsPreparedOperation() = runBlocking {
        val failure = runCatching {
            performRecoverableSend(context, { snapshot(SendRecoveryOutcome.Returned) }, { error("database") }) { remember ->
                remember("uncertain"); error("transport")
            }
        }.exceptionOrNull() as PendingSendRecovery
        assertEquals(SendRecoveryOutcome.Unverified, failure.outcome)
        assertEquals(listOf("uncertain"), failure.operationIds)
    }

    @Test fun failedPostRecoveryReadRetainsPreparedOperation() = runBlocking {
        var attempted = false
        val failure = runCatching {
            performRecoverableSend(context, { if (attempted) error("database") else snapshot(SendRecoveryOutcome.Returned) }, {}) { remember ->
                remember("uncertain"); attempted = true; error("transport")
            }
        }.exceptionOrNull() as PendingSendRecovery
        assertEquals(SendRecoveryOutcome.Unverified, failure.outcome)
        assertEquals(listOf("uncertain"), failure.operationIds)
    }

    @Test fun successfulSendDoesNotRunRecovery() = runBlocking {
        assertEquals(21, performRecoverableSend(context, { snapshot(SendRecoveryOutcome.Returned) }, { fail("Successful send") }) { 21 })
    }

    @Test fun cancellationIsNotConvertedToRetryableFailure() = runBlocking {
        val failure = runCatching {
            performRecoverableSend(context, { snapshot(SendRecoveryOutcome.Returned) }, { fail("Cancelled task") }) {
                throw CancellationException("cancelled")
            }
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
    }
    private val operationId = "11111111-1111-4111-8111-111111111111"
    private fun saga(unit: String = "sat", state: String = "token_created", token: String = "\"test-token\"") =
        """{"id":"$operationId","kind":"send","mint_url":"https://mint.example","unit":"$unit",
           "amount":21,"state":{"type":"send","state":"$state"},"data":{"kind":"send","data":{"token":$token}}}"""

    @Test fun persistedPendingTokenIsRecoveredAfterReopening() {
        val result = resolvePendingSendRecovery(context, context.mintUrl, listOf(saga()), listOf(operationId), transactions = emptyMap())
        assertEquals(SendRecoveryOutcome.Pending, result.outcome)
        assertEquals(listOf(PendingSend(operationId, 21, "test-token")), result.sends)
    }

    @Test fun otherCurrencyDoesNotBlockThisAccount() {
        val result = resolvePendingSendRecovery(context, context.mintUrl, listOf(saga(unit = "usd")), listOf(operationId), transactions = emptyMap())
        assertEquals(SendRecoveryOutcome.Returned, result.outcome)
        assertTrue(result.sends.isEmpty())
    }

    @Test fun unfinishedReservationAndRollbackRemainUnverified() {
        listOf("proofs_reserved", "rolling_back").forEach { state ->
            val result = resolvePendingSendRecovery(context, context.mintUrl, listOf(saga(state = state)), emptyList(), transactions = emptyMap())
            assertEquals(SendRecoveryOutcome.Unverified, result.outcome)
            assertTrue(result.sends.isEmpty())
        }
    }

    @Test fun missingTokenPreviewStillOffersReclaim() {
        val result = resolvePendingSendRecovery(context, context.mintUrl, listOf(saga(token = "null")), listOf(operationId), transactions = emptyMap())
        assertEquals(SendRecoveryOutcome.Pending, result.outcome)
        assertEquals(operationId, result.sends.single().id)
        assertNull(result.sends.single().token)
    }

    @Test fun claimedAndReclaimedTransactionsRemainDistinct() {
        val known = PendingSendRecovery(context.mintUrl, context.unit, listOf(operationId))
        val txId = requireNotNull(SagaTransactionId.transactionIdHex(operationId))
        listOf(TransactionStatus.Completed to SendRecoveryOutcome.Completed,
            TransactionStatus.Failed to SendRecoveryOutcome.Returned, TransactionStatus.Pending to SendRecoveryOutcome.Unverified).forEach { (status, expected) ->
            val result = resolvePendingSendRecovery(known, context.mintUrl, emptyList(), emptyList(), transactions = mapOf(txId to status))
            assertEquals(expected, result.outcome)
        }
    }

    @Test fun missingOrCorruptSagaCannotAuthorizeRetry() {
        val result = resolvePendingSendRecovery(context, context.mintUrl, emptyList(), listOf(operationId), transactions = emptyMap())
        assertEquals(SendRecoveryOutcome.Unverified, result.outcome)
        assertTrue(runCatching {
            resolvePendingSendRecovery(context, context.mintUrl, listOf("invalid"), emptyList(), transactions = emptyMap())
        }.isFailure)
    }

}
