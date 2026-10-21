package com.cashu.me.Models

import com.cashu.me.Core.CDK.mintRemovalUrlsMatch
import com.cashu.me.Core.MintTransferEligibility
import kotlinx.serialization.Serializable

/** Which proofs a melt spends. */
sealed interface MeltProofSelection {
    /** CDK picks the proofs, swapping first when it needs exact inputs. */
    data object Automatic : MeltProofSelection

    /**
     * Every unspent proof goes in directly, with no pre-melt swap. A max
     * cross-mint quote sizes its amount on the input fee of exactly this proof
     * set, so a swap would charge a second fee the balance no longer covers.
     */
    data class AllUnspentSkippingSwap(val expectedInputFee: Long) : MeltProofSelection
}

/**
 * A quoted move of ecash from one held mint to another, ready to show for
 * review and then execute (iOS `MintTransfer.swift` parity). The amount is
 * what arrives at the destination; the source pays it plus the fees.
 *
 * A plan owns a BOLT11 mint quote at the destination and the melt quote at the
 * source that pays it. Both exist at their mints from the moment the plan is
 * made, so a plan that is not executed must be discarded, not dropped.
 */
data class MintTransferPlan(
    val id: String,
    val mode: Mode,
    val sourceMintUrl: String,
    val destinationMintUrl: String,
    val mintQuoteId: String,
    val meltQuoteId: String,
    /** Amount that arrives at the destination. */
    val amount: Long,
    /**
     * Lightning fee reserve at the source. An upper bound: what the payment
     * does not use comes back as change.
     */
    val feeReserve: Long,
    /**
     * Input fee the melt is planned to pay. Non-zero only when the melt spends
     * every proof directly, which is the one case where it is known up front.
     */
    val inputFee: Long,
    val selection: MeltProofSelection,
    /** When the quotes stop being payable. Null when neither mint set one. */
    val expiresAtEpochSeconds: Long?,
) {
    enum class Mode {
        /** The user typed the amount. */
        Exact,

        /** The largest amount the source can move in one payment. */
        Max,
    }

    val feeUpperBound: Long get() = feeReserve.plusClamped(inputFee)
    val total: Long get() = amount.plusClamped(feeUpperBound)

    fun isExpired(nowEpochMillis: Long = System.currentTimeMillis()): Boolean =
        expiresAtEpochSeconds != null && nowEpochMillis / 1_000 >= expiresAtEpochSeconds

    companion object {
        /**
         * How a max plan's melt must pick its proofs.
         *
         * CDK sizes a max quote on the input fee of every unspent proof. When
         * that leaves no room to spare, only melting exactly those proofs fits:
         * an ordinary melt may swap first and pay a second input fee the
         * balance no longer covers. When the amount was capped by a mint limit
         * instead there is room for that, and an ordinary melt avoids routing
         * the whole balance through one payment. Mints without input fees never
         * need the special path.
         */
        fun maxMeltSelection(
            balance: Long,
            amount: Long,
            feeReserve: Long,
            inputFee: Long,
        ): MeltProofSelection {
            if (inputFee <= 0) return MeltProofSelection.Automatic
            val committed = amount.plusClamped(feeReserve).plusClamped(inputFee)
            val slack = if (balance > committed) balance - committed else 0
            // A swap and the melt after it can each cost up to the fee of
            // spending every proof.
            return if (slack < inputFee.plusClamped(inputFee)) {
                MeltProofSelection.AllUnspentSkippingSwap(expectedInputFee = inputFee)
            } else {
                MeltProofSelection.Automatic
            }
        }
    }
}

/**
 * The durable link between a transfer's two quotes. CDK records each leg in
 * its own mint's ledger and knows nothing connects them; this record is what
 * lets the wallet treat them as one transfer across relaunches.
 */
@Serializable
data class MintTransferRecord(
    val id: String,
    val sourceMintUrl: String,
    val destinationMintUrl: String,
    val mintQuoteId: String,
    /** Null only in the moment between the two quotes being created. */
    val meltQuoteId: String? = null,
    val amount: Long,
    val createdAtEpochMillis: Long,
    val state: State = State.Draft,
) {
    @Serializable
    enum class State {
        /** Quoted and under review. No money has moved. */
        Draft,

        /**
         * The melt was started. From here the destination quote is the only
         * handle on the payment and is never discarded.
         */
        Committed,

        /** The destination issued the ecash. */
        Completed,

        /** The melt ended without paying. */
        Failed,
    }

    val isFinished: Boolean get() = state == State.Completed || state == State.Failed

    companion object {
        /**
         * Records kept. Old finished transfers fall back to reading as the two
         * Lightning rows CDK holds for them.
         */
        const val LIMIT = 500
    }
}

/**
 * Destination quotes that belong to a transfer in any state. They are never an
 * invoice the user is waiting on, and their issuance is not a payment received.
 */
val List<MintTransferRecord>.ownedMintQuoteIds: Set<String>
    get() = mapTo(mutableSetOf()) { it.mintQuoteId }

/**
 * Destination quotes still under review. Nothing will pay them until the user
 * confirms, so maintenance leaves them alone.
 */
val List<MintTransferRecord>.draftMintQuoteIds: Set<String>
    get() = filter { it.state == MintTransferRecord.State.Draft }.mapTo(mutableSetOf()) { it.mintQuoteId }

/**
 * Destination quotes whose melt was started and has not been seen to end. A
 * payment can settle after its invoice expired, so these keep being checked
 * until they issue.
 */
val List<MintTransferRecord>.awaitingIssuanceMintQuoteIds: Set<String>
    get() = filter { it.state == MintTransferRecord.State.Committed }.mapTo(mutableSetOf()) { it.mintQuoteId }

fun List<MintTransferRecord>.hasUnfinishedTransfer(referencing: String): Boolean = any {
    it.state == MintTransferRecord.State.Committed &&
        (mintRemovalUrlsMatch(it.sourceMintUrl, referencing) ||
            mintRemovalUrlsMatch(it.destinationMintUrl, referencing))
}

/**
 * Insert or replace by id, newest first, dropping the oldest finished records
 * past the limit. Unfinished ones are never dropped.
 */
fun List<MintTransferRecord>.upserting(record: MintTransferRecord): List<MintTransferRecord> {
    val records = (listOf(record) + filter { it.id != record.id })
        .sortedByDescending { it.createdAtEpochMillis }
    val kept = mutableListOf<MintTransferRecord>()
    for (candidate in records) {
        if (!candidate.isFinished || kept.size < MintTransferRecord.LIMIT) kept += candidate
    }
    return kept
}

fun List<MintTransferRecord>.setting(
    state: MintTransferRecord.State,
    forId: String,
): List<MintTransferRecord> = map { if (it.id == forId) it.copy(state = state) else it }

/** How an executed transfer ended. */
sealed interface MintTransferOutcome {
    /**
     * The destination issued [amount]. [feePaid] is what the source actually
     * paid on top, including any input fee.
     */
    data class Completed(val amount: Long, val feePaid: Long) : MintTransferOutcome

    /** Money is in flight and will finish on its own. */
    data class Settling(val leg: Leg) : MintTransferOutcome

    enum class Leg {
        /** The source has not confirmed the Lightning payment yet. */
        Payment,

        /** The payment went out; the destination has not issued the ecash yet. */
        Issuance,
    }
}

enum class MintTransferStage { Paying, Issuing }

/**
 * Why a transfer could not be quoted or did not go through (iOS
 * `MintTransferError`). The user-facing copy lives in `WalletErrorMessages`.
 */
internal sealed class MintTransferException(message: String) : Exception(message) {
    class NotEligible(val blocker: MintTransferEligibility.Blocker) :
        MintTransferException("Mint transfer pair is not eligible: $blocker.")

    /** The source cannot cover the amount plus the fee reserve. */
    class InsufficientBalance(val required: Long, val available: Long) :
        MintTransferException("Mint transfer exceeds the source balance.")

    /** The source holds too little to move anything once fees are paid. */
    class NothingToTransfer : MintTransferException("Nothing to transfer after fees.")

    /** The mints returned quotes that do not describe the requested transfer. */
    class QuoteMismatch : MintTransferException("Mint transfer quotes do not match the request.")

    /** The quotes expired before the transfer started. */
    class PlanExpired : MintTransferException("Mint transfer plan expired.")

    /**
     * The wallet changed since the plan was made, so it would not pay what it
     * promised.
     */
    class PlanStale : MintTransferException("Mint transfer plan is stale.")

    /** The melt ended without paying. The funds are still at the source. */
    class PaymentReturned : MintTransferException("Mint transfer payment was returned.")
}

// Operands are never negative, so overflow can only run past the maximum.
private fun Long.plusClamped(other: Long): Long =
    if (other > Long.MAX_VALUE - this) Long.MAX_VALUE else this + other
