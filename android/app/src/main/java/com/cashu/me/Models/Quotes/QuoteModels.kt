package com.cashu.me.Models

import kotlinx.serialization.Serializable

@Serializable
enum class MintQuoteState {
    Unpaid,
    Pending,
    Paid,
    Issued,
    Failed,
    Unknown,
}

@Serializable
data class MintQuoteInfo(
    val id: String,
    val request: String,
    val amount: Long?,
    // BOLT12 amountless offers may later display the amount received. Preserve
    // their original shape so they can still be reused after a payment.
    val isAmountless: Boolean = amount == null,
    val paymentMethod: PaymentMethodKind,
    val state: MintQuoteState,
    val expiryEpochSeconds: Long?,
    val mintUrl: String? = null,
    val amountPaid: Long = 0,
    val amountIssued: Long = 0,
    // Unit the quote mints into; poll/redeem must resolve the same-unit wallet.
    val unit: String = "sat",
) {
    val isExpired: Boolean
        get() = expiryEpochSeconds != null &&
            expiryEpochSeconds > 0 &&
            System.currentTimeMillis() / 1000 > expiryEpochSeconds
}

@Serializable
enum class MeltQuoteState {
    Unpaid,
    Pending,
    Paid,
    Failed,
    Unknown,
}

@Serializable
data class MeltQuoteInfo(
    val id: String,
    val mintUrl: String,
    val amount: Long,
    val feeReserve: Long,
    val paymentMethod: PaymentMethodKind,
    val state: MeltQuoteState,
    val expiryEpochSeconds: Long?,
    val request: String? = null,
    val paymentProof: String? = null,
) {
    val totalAmount: Long get() = amount + feeReserve
    val isExpired: Boolean
        get() = expiryEpochSeconds != null &&
            expiryEpochSeconds > 0 &&
            System.currentTimeMillis() / 1000 > expiryEpochSeconds
}

@Serializable
data class MeltPaymentResult(
    val preimage: String?,
    val amount: Long,
    val feePaid: Long,
    val mintUrl: String,
    val paymentMethod: PaymentMethodKind? = null,
    val request: String? = null,
)
