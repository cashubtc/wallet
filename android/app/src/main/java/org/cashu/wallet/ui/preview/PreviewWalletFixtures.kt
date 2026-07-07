package org.cashu.wallet.ui.preview

import org.cashu.wallet.Core.SettingsState
import org.cashu.wallet.Core.WalletState
import org.cashu.wallet.Models.CashuRequest
import org.cashu.wallet.Models.CashuRequestPayment
import org.cashu.wallet.Models.MintInfo
import org.cashu.wallet.Models.PaymentMethodKind
import org.cashu.wallet.Models.P2PKKeyInfo
import org.cashu.wallet.Models.TransactionKind
import org.cashu.wallet.Models.TransactionStatus
import org.cashu.wallet.Models.TransactionType
import org.cashu.wallet.Models.WalletTransaction

object PreviewWalletFixtures {
    private const val Now = 1_720_000_000_000L

    val mints: List<MintInfo> = listOf(
        MintInfo(
            url = "https://mint.example.com",
            name = "Example Mint",
            description = "Preview mint with Lightning, BOLT12, and on-chain methods.",
            isActive = true,
            balance = 42_500,
            units = listOf("sat", "usd"),
            mintUnits = listOf("sat", "usd"),
            supportedMintMethods = listOf(
                PaymentMethodKind.Bolt11,
                PaymentMethodKind.Bolt12,
                PaymentMethodKind.Onchain,
            ),
            supportedMeltMethods = listOf(
                PaymentMethodKind.Bolt11,
                PaymentMethodKind.Onchain,
            ),
            lastUpdatedEpochMillis = Now,
        ),
        MintInfo(
            url = "https://backup-mint.example.com",
            name = "Backup Mint",
            isActive = false,
            balance = 12_000,
            units = listOf("sat"),
            supportedMintMethods = listOf(PaymentMethodKind.Bolt11),
            supportedMeltMethods = listOf(PaymentMethodKind.Bolt11),
            lastUpdatedEpochMillis = Now - 3_600_000,
        ),
    )

    val transactions: List<WalletTransaction> = listOf(
        WalletTransaction(
            id = "preview-incoming-lightning",
            amount = 21_000,
            type = TransactionType.Incoming,
            kind = TransactionKind.Lightning,
            dateEpochMillis = Now - 5 * 60_000,
            memo = "Lightning receive",
            status = TransactionStatus.Completed,
            mintUrl = mints.first().url,
            quoteId = "preview-mint-quote",
        ),
        WalletTransaction(
            id = "preview-outgoing-ecash",
            amount = 8_000,
            type = TransactionType.Outgoing,
            kind = TransactionKind.Ecash,
            dateEpochMillis = Now - 60 * 60_000,
            memo = "Coffee",
            status = TransactionStatus.Pending,
            statusNote = "Waiting for recipient",
            mintUrl = mints.first().url,
            isPendingToken = true,
        ),
        WalletTransaction(
            id = "preview-onchain",
            amount = 30_000,
            type = TransactionType.Incoming,
            kind = TransactionKind.Onchain,
            dateEpochMillis = Now - 24 * 60 * 60_000,
            memo = "On-chain deposit",
            status = TransactionStatus.Completed,
            mintUrl = mints.first().url,
        ),
    )

    val cashuRequests: List<CashuRequest> = listOf(
        CashuRequest(
            id = "preview-request",
            encoded = "creqA1previewrequest",
            amount = 5_000,
            unit = "sat",
            mints = listOf(mints.first().url),
            memo = "Preview request",
            createdAtEpochMillis = Now - 10 * 60_000,
            receivedPayments = listOf(
                CashuRequestPayment(
                    transactionId = "preview-incoming-lightning",
                    amount = 5_000,
                    receivedAtEpochMillis = Now - 4 * 60_000,
                ),
            ),
        ),
    )

    val settings: SettingsState = SettingsState(
        useBitcoinSymbol = true,
        showFiatBalance = true,
        bitcoinPriceCurrency = "USD",
        checkPendingOnStartup = true,
        checkSentTokens = true,
        autoPasteEcashReceive = true,
        useWebsockets = true,
        amountDisplayPrimary = "fiat",
        homeBalanceUnit = "sat",
        sentryEnabled = false,
        checkIncomingInvoices = true,
        periodicallyCheckIncomingInvoices = true,
        nostrSignerType = "SEED",
        nostrRelays = listOf("wss://relay.example.com"),
        p2pkKeys = listOf(
            P2PKKeyInfo(
                id = "preview-p2pk",
                publicKey = "02".padEnd(66, '1'),
                label = "Preview spending key",
                used = true,
                usedCount = 2,
            ),
        ),
    )

    val walletState: WalletState = WalletState(
        balance = 54_500,
        pendingBalance = 8_000,
        isInitialized = true,
        needsOnboarding = false,
        canExitOnboarding = true,
        balancesByUnit = mapOf("sat" to 54_500, "usd" to 12_34),
        mints = mints,
        activeMint = mints.first(),
        transactions = transactions,
    )
}
