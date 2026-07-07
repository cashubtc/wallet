package org.cashu.wallet.Core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.cashu.wallet.Models.CashuRequest
import org.cashu.wallet.Models.CashuRequestPayment

data class CashuRequestStoreState(
    val requests: List<CashuRequest> = emptyList(),
    val currentRequestId: String? = null,
) {
    val currentRequest: CashuRequest?
        get() = currentRequestId?.let { id -> requests.firstOrNull { it.id == id } }
}

class CashuRequestStore(
    private val walletStore: WalletStore,
) {
    private val mutableState = MutableStateFlow(loadState())
    val state: StateFlow<CashuRequestStoreState> = mutableState.asStateFlow()

    fun createNew(
        id: String = CashuRequest.newId(),
        amount: Long? = null,
        unit: String = "sat",
        mints: List<String> = emptyList(),
        memo: String? = null,
        encoded: String,
    ): CashuRequest {
        val request = CashuRequest(
            id = id,
            encoded = encoded,
            amount = amount,
            unit = unit,
            mints = mints,
            memo = memo?.takeIf { it.isNotBlank() },
        )
        val updated = listOf(request) + mutableState.value.requests.filterNot { it.id == request.id }
        persist(updated, request.id)
        return request
    }

    fun upsert(request: CashuRequest, makeCurrent: Boolean = true): CashuRequest {
        val updated = listOf(request) + mutableState.value.requests.filterNot { it.id == request.id }
        persist(updated, if (makeCurrent) request.id else mutableState.value.currentRequestId)
        return request
    }

    fun update(
        id: String,
        amount: Long?,
        unit: String,
        mints: List<String>,
        memo: String?,
        encoded: String,
    ): CashuRequest? {
        val existing = request(id) ?: return null
        val updatedRequest = existing.copy(
            amount = amount,
            unit = unit,
            mints = mints,
            memo = memo?.takeIf { it.isNotBlank() },
            encoded = encoded,
        )
        upsert(updatedRequest, makeCurrent = mutableState.value.currentRequestId == id)
        return updatedRequest
    }

    fun upsertQuoteIntent(
        id: String = CashuRequest.newId(),
        quoteId: String,
        quoteKind: String,
        amount: Long?,
        unit: String = "sat",
        mints: List<String> = emptyList(),
        memo: String? = null,
        encoded: String,
    ): CashuRequest =
        upsert(
            CashuRequest(
                id = id,
                encoded = encoded,
                amount = amount,
                unit = unit,
                mints = mints,
                memo = memo?.takeIf { it.isNotBlank() },
                quoteId = quoteId,
                quoteKind = quoteKind,
            ),
        )

    fun attachPayment(requestId: String, transactionId: String, amount: Long) {
        val current = mutableState.value
        val updated = current.requests.map { request ->
            if (request.id != requestId || request.receivedPayments.any { it.transactionId == transactionId }) {
                request
            } else {
                request.copy(
                    receivedPayments = request.receivedPayments + CashuRequestPayment(
                        transactionId = transactionId,
                        amount = amount,
                        receivedAtEpochMillis = System.currentTimeMillis(),
                    ),
                )
            }
        }
        persist(updated, current.currentRequestId)
    }

    fun attachPaymentByQuoteId(quoteId: String, transactionId: String, amount: Long) {
        val request = mutableState.value.requests.firstOrNull { it.quoteId == quoteId } ?: return
        attachPayment(request.id, transactionId, amount)
    }

    fun delete(id: String) {
        val current = mutableState.value
        val updated = current.requests.filterNot { it.id == id }
        val nextCurrent = current.currentRequestId.takeUnless { it == id }
        persist(updated, nextCurrent)
    }

    fun request(id: String): CashuRequest? =
        mutableState.value.requests.firstOrNull { it.id == id }

    fun reload() {
        mutableState.value = loadState()
    }

    fun reset() {
        persist(emptyList(), null)
    }

    private fun loadState(): CashuRequestStoreState {
        val requests = walletStore.loadCashuRequests()
        val currentId = walletStore.currentCashuRequestId
            ?.takeIf { id -> requests.any { it.id == id } }
        if (currentId != walletStore.currentCashuRequestId) {
            walletStore.currentCashuRequestId = currentId
        }
        return CashuRequestStoreState(
            requests = requests.sortedByDescending { it.createdAtEpochMillis },
            currentRequestId = currentId,
        )
    }

    private fun persist(requests: List<CashuRequest>, currentRequestId: String?) {
        val normalized = requests.map { it.withLegacyPaymentFallback() }
            .sortedByDescending { it.createdAtEpochMillis }
        walletStore.saveCashuRequests(normalized)
        walletStore.currentCashuRequestId = currentRequestId
        mutableState.value = CashuRequestStoreState(
            requests = normalized,
            currentRequestId = currentRequestId,
        )
    }
}
