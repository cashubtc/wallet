package com.cashu.me.Core

import com.cashu.me.Models.MintInfo
import com.cashu.me.Models.PaymentMethodKind

/**
 * Why an amountless BOLT11 invoice can't be paid from the mints held (iOS
 * `AmountlessMeltBlock` parity). Send's amount step shows a caution face for
 * it instead of a keypad nothing typed into could pay.
 */
internal sealed interface AmountlessMeltBlock {
    /** No held mint advertises NUT-05 `amountless`. */
    data object NoCapableMint : AmountlessMeltBlock

    /**
     * The mints that can pay one hold nothing. [transferDestinationUrl] is the
     * one a Transfer can fund from another held mint, null when none can.
     */
    data class CapableMintsEmpty(
        val capableMintNames: List<String>,
        val transferDestinationUrl: String?,
    ) : AmountlessMeltBlock
}

/** What the caution face says about an [AmountlessMeltBlock], and its one action. */
internal data class AmountlessMeltBlockPresentation(
    val headline: String,
    val detail: String,
    /** Null when there is nothing to transfer from or to. */
    val actionTitle: String?,
)

/**
 * The block an amountless BOLT11 runs into, or null when a held mint can pay
 * it (or [decoded] isn't one). A mint whose capability was never fetched
 * stays eligible, as everywhere else ([MintInfo.supportsAmountlessBolt11Melt]).
 */
internal fun amountlessMeltBlock(
    decoded: PaymentRequestDecodeResult?,
    mints: List<MintInfo>,
): AmountlessMeltBlock? {
    if (decoded?.isAmountlessBolt11 != true) return null
    val capable = mints.filter { it.canMelt(PaymentMethodKind.Bolt11, amountless = true) }
    if (capable.isEmpty()) return AmountlessMeltBlock.NoCapableMint
    if (capable.any { it.balance > 0L }) return null
    val destination = capable.firstOrNull { candidate ->
        MintTransferEligibility.canReceive(candidate) && mints.any { source ->
            source.balance > 0L && MintTransferEligibility.eligible(source, candidate)
        }
    }
    return AmountlessMeltBlock.CapableMintsEmpty(
        capableMintNames = capable.map { it.name },
        transferDestinationUrl = destination?.url,
    )
}

/** [zero] is the wallet's own formatting of 0 sat (₿0 or 0 sat). */
internal fun AmountlessMeltBlock.presentation(zero: String): AmountlessMeltBlockPresentation {
    val headline = "Can't pay this invoice."
    val askForAmount = "Ask for one with the amount set."
    return when (this) {
        AmountlessMeltBlock.NoCapableMint -> AmountlessMeltBlockPresentation(
            headline = headline,
            detail = "None of your mints can pay invoices without an amount. $askForAmount",
            actionTitle = null,
        )
        is AmountlessMeltBlock.CapableMintsEmpty -> {
            val canTransfer = transferDestinationUrl != null
            val only = capableMintNames.singleOrNull()
            val reason = if (only != null) {
                "Only $only can pay invoices without an amount, and it holds $zero."
            } else {
                "The mints that can pay invoices without an amount are empty."
            }
            AmountlessMeltBlockPresentation(
                headline = headline,
                detail = if (canTransfer) reason else "$reason $askForAmount",
                actionTitle = when {
                    !canTransfer -> null
                    only != null -> "Transfer to $only"
                    else -> "Transfer ecash"
                },
            )
        }
    }
}

/**
 * Why [mint] can't pay a melt over [method], or null when it can. Send's mint
 * picker lists such a mint dimmed with this in place of its balance (iOS
 * `MintSelectorSheet` parity).
 */
internal fun meltUnavailableReason(
    mint: MintInfo,
    method: PaymentMethodKind,
    amountless: Boolean = false,
): String? {
    if (mint.canMelt(method, amountless)) return null
    return when (method) {
        PaymentMethodKind.Bolt11 -> if (amountless) {
            "Can't pay invoices without an amount"
        } else {
            "Can't pay Lightning invoices"
        }
        PaymentMethodKind.Bolt12 -> "Can't pay reusable invoices"
        PaymentMethodKind.Onchain -> "Can't send on-chain"
    }
}
