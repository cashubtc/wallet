package com.cashu.me.Core

import com.cashu.me.Models.SagaTransactionId
import com.cashu.me.Models.TransactionStatus
import kotlinx.serialization.json.*
import kotlinx.coroutines.CancellationException
import org.cashudevkit.FfiException
import org.cashudevkit.PreparedPaymentRequest

class CashuRequestDeliveryReturned : Exception(
    "The payment couldn't be delivered. Unclaimed ecash was returned to your wallet. Mint fees may apply.",
)

internal suspend fun performRecoverablePaymentRequest(
    context: PendingSendRecovery,
    inspect: suspend (List<String>) -> PendingSendRecovery,
    recover: suspend () -> Unit,
    prepare: suspend () -> PreparedPaymentRequest,
    reclaim: suspend (String) -> Unit,
) {
    var deliveryFailed = false
    try {
        performRecoverableSend(context, inspect, recover) { remember ->
            val prepared = prepare()
            val operationId = prepared.operationId()
            remember(operationId)
            try {
                prepared.confirm()
            } catch (error: FfiException.PaymentRequestDeliveryFailed) {
                if (error.operationId != operationId) throw error
                deliveryFailed = true
                // Revoke only this attempt; the mint arbitrates a concurrent recipient claim.
                try { reclaim(operationId) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { /* Resolve through the normal recovery snapshot below. */ }
                throw error
            }
        }
    } catch (error: FfiException.PaymentRequestDeliveryFailed) {
        // The send wrapper rethrows this only once no funds remain pending.
        if (deliveryFailed) throw CashuRequestDeliveryReturned()
        throw error
    }
}

data class PendingSend(val id: String, val amount: Long, val token: String?)

enum class SendRecoveryOutcome { Pending, Unverified, Completed, Returned }

class PendingSendRecovery(
    val mintUrl: String,
    val unit: String,
    val operationIds: List<String> = emptyList(),
    val sends: List<PendingSend> = emptyList(),
    val outcome: SendRecoveryOutcome = SendRecoveryOutcome.Unverified,
) : Exception(when (outcome) {
    SendRecoveryOutcome.Pending -> "Ecash is still pending. Check its status or reclaim it before sending again."
    SendRecoveryOutcome.Unverified -> "The send outcome couldn't be verified. Check its status before sending again."
    SendRecoveryOutcome.Completed -> "The ecash was claimed. This payment is complete."
    SendRecoveryOutcome.Returned -> "No ecash remains pending. Your available balance has been refreshed."
})

internal suspend fun <T> performRecoverableSend(
    context: PendingSendRecovery,
    inspect: suspend (List<String>) -> PendingSendRecovery,
    recover: suspend () -> Unit,
    send: suspend (remember: (String) -> Unit) -> T,
): T {
    val before = try { inspect(emptyList()) } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) { throw context }
    if (before.outcome != SendRecoveryOutcome.Returned) throw before
    val operationIds = mutableListOf<String>()
    try {
        return send { operationIds += it }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (original: Exception) {
        try {
            recover()
            val resolved = inspect(operationIds)
            if (resolved.outcome != SendRecoveryOutcome.Returned) throw resolved
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (resolved: PendingSendRecovery) {
            throw resolved
        } catch (_: Exception) {
            throw PendingSendRecovery(context.mintUrl, context.unit, operationIds.toList())
        }
        throw original
    }
}


internal fun resolvePendingSendRecovery(
    context: PendingSendRecovery,
    mintUrl: String,
    sagaJson: List<String>,
    pendingIds: List<String>,
    operationIds: List<String> = context.operationIds,
    transactions: Map<String, TransactionStatus>,
): PendingSendRecovery {
    val pending = pendingIds.toSet()
    val allRecords = sagaJson.map { Json.parseToJsonElement(it).jsonObject }
    val records = allRecords.filter {
        it.getValue("kind").jsonPrimitive.content == "send" &&
            it.getValue("mint_url").jsonPrimitive.content == mintUrl &&
            it.getValue("unit").jsonPrimitive.content == context.unit
    }
    val ids = (operationIds + records.map { it.getValue("id").jsonPrimitive.content }).distinct()
    val sends = records.filter {
        it.getValue("state").jsonObject.getValue("type").jsonPrimitive.content == "send" &&
            it.getValue("state").jsonObject.getValue("state").jsonPrimitive.content == "token_created" &&
            it.getValue("id").jsonPrimitive.content in pending
    }.map {
        PendingSend(
            id = it.getValue("id").jsonPrimitive.content,
            amount = it.getValue("amount").jsonPrimitive.long,
            token = it.getValue("data").jsonObject.getValue("data").jsonObject["token"]?.jsonPrimitive?.contentOrNull,
        )
    }
    val statuses = ids.mapNotNull(SagaTransactionId::transactionIdHex).mapNotNull(transactions::get)
    val outcome = when {
        !allRecords.map { it.getValue("id").jsonPrimitive.content }.containsAll(pending) -> SendRecoveryOutcome.Unverified
        records.size != sends.size -> SendRecoveryOutcome.Unverified
        sends.isNotEmpty() -> SendRecoveryOutcome.Pending
        TransactionStatus.Pending in statuses -> SendRecoveryOutcome.Unverified
        TransactionStatus.Completed in statuses -> SendRecoveryOutcome.Completed
        else -> SendRecoveryOutcome.Returned
    }
    return PendingSendRecovery(context.mintUrl, context.unit, ids, sends, outcome)
}
