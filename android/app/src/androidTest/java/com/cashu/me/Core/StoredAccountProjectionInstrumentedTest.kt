package com.cashu.me.Core

import androidx.test.platform.app.InstrumentationRegistry
import com.cashu.me.Core.CDK.CdkWalletGateway
import com.cashu.me.Core.CDK.CdkWalletGatewayImpl
import com.cashu.me.Core.CDK.WalletAccountReference
import com.cashu.me.Models.MintInfo
import com.cashu.me.Models.MintQuoteInfo
import com.cashu.me.Models.MintQuoteState
import com.cashu.me.Models.PaymentMethodKind
import com.cashu.me.Models.PendingReceiveToken
import com.cashu.me.Models.TransactionKind
import com.cashu.me.Models.TransactionType
import com.cashu.me.Models.WalletTransaction
import com.cashu.me.Models.TransactionStatus as AppTransactionStatus
import com.cashu.me.test.fixtures.FakeWalletGateway
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.cashudevkit.*
import org.junit.Assert.*
import org.junit.Test

class StoredAccountProjectionInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun fulfilledOnchainPaymentsRemainInHistoryWithEquivalentMintUrls() = runBlocking {
        val directory = File(context.cacheDir, UUID.randomUUID().toString()).apply { mkdirs() }
        val path = File(directory, "wallet.db").path
        val storedMint = MintUrl("https://offline.example:443")
        val gateway = CdkWalletGatewayImpl()
        try {
            val mnemonic = gateway.generateMnemonic()
            WalletSqliteDatabase(path).use { db ->
                for (direction in listOf(TransactionDirection.INCOMING, TransactionDirection.OUTGOING)) {
                    db.addTransaction(Transaction(
                        id = TransactionId("a".repeat(64)), mintUrl = storedMint, direction = direction,
                        amount = Amount(2_100u), fee = Amount(10u), unit = CurrencyUnit.Sat,
                        ys = emptyList(), timestamp = 1u, memo = null, metadata = emptyMap(),
                        quoteId = UUID.randomUUID().toString(), paymentRequest = "bc1qfulfilledfixture",
                        paymentProof = "b".repeat(64), paymentMethod = PaymentMethod.Onchain,
                        sagaId = UUID.randomUUID().toString(), status = TransactionStatus.COMPLETED,
                    ))
                }
            }
            repeat(2) {
                // Prove fulfillment is durably recorded before asking the UI loader for history.
                val storedIds = WalletSqliteDatabase(path).use { db ->
                    val records = db.listTransactions(null, null, null)
                    assertEquals(2, records.size)
                    assertTrue(records.all { it.status == TransactionStatus.COMPLETED })
                    assertTrue(records.all { it.mintUrl.url == storedMint.url })
                    assertTrue(db.getUnissuedMintQuotes().isEmpty())
                    records.map { it.id.hex }.toSet()
                }
                gateway.openWalletRepository(mnemonic, path)
                // An empty cache prevents a previous in-memory receipt from masking data loss.
                val store = WalletStore(context, "fulfilled_onchain_" + UUID.randomUUID())
                val loader = WalletTransactionLoader(store, gateway)
                val canonical = loader.load(listOf(MintInfo(storedMint.url)), false).transactions
                assertEquals(storedIds, canonical.map { it.id }.toSet())
                store.saveTransactions(emptyList())
                val rows = loader.load(listOf(MintInfo("https://offline.example/")), false).transactions
                assertEquals("Fulfilled payments must survive equivalent mint URL spelling", storedIds, rows.map { it.id }.toSet())
                assertTrue(rows.all { it.status == AppTransactionStatus.Completed && it.kind == TransactionKind.Onchain })
                assertTrue(rows.all { it.amount == 2_100L && it.preimage == "b".repeat(64) })
                for (filter in listOf(HistoryFilter.All, HistoryFilter.Completed)) {
                    val visible = com.cashu.me.ui.history.unifiedFiltered(rows, emptyList(), filter, "bitcoin")
                    assertEquals(2, visible.size)
                }
                gateway.closeWalletRepository()
            }
        } finally {
            gateway.closeWalletRepository()
            directory.deleteRecursively()
        }
    }

    @Test fun onchainDirectionsStatesAndLegacyMethodSurviveDatabaseReopen() = runBlocking {
        val directory = File(context.cacheDir, UUID.randomUUID().toString()).apply { mkdirs() }
        val path = File(directory, "wallet.db").path
        val mint = MintUrl("https://offline.example")
        val gateway = CdkWalletGatewayImpl()
        try {
            val mnemonic = gateway.generateMnemonic()
            WalletSqliteDatabase(path).use { db ->
                for ((index, status) in listOf(TransactionStatus.PENDING, TransactionStatus.COMPLETED, TransactionStatus.FAILED).withIndex()) {
                    for (direction in listOf(TransactionDirection.INCOMING, TransactionDirection.OUTGOING)) {
                        db.addTransaction(Transaction(
                            id = TransactionId("a".repeat(64)), mintUrl = mint, direction = direction,
                            amount = Amount(2_100u), fee = Amount(10u), unit = CurrencyUnit.Sat,
                            ys = emptyList(), timestamp = (index + 1).toULong(), memo = null,
                            metadata = emptyMap(), quoteId = UUID.randomUUID().toString(),
                            paymentRequest = "bc1qhistoryfixture", paymentProof = "b".repeat(64),
                            paymentMethod = if (index == 0) PaymentMethod.Custom("onchain") else PaymentMethod.Onchain,
                            sagaId = UUID.randomUUID().toString(), status = status,
                        ))
                    }
                }
            }
            val store = WalletStore(context, "onchain_history_" + UUID.randomUUID())
            repeat(2) {
                gateway.openWalletRepository(mnemonic, path)
                val rows = WalletTransactionLoader(store, gateway)
                    .load(listOf(MintInfo(mint.url)), false).transactions
                assertEquals(6, rows.size)
                assertEquals(6, rows.map { it.id }.toSet().size)
                rows.forEach { row ->
                    assertEquals(TransactionKind.Onchain, row.kind)
                    assertEquals(2_100L, row.amount)
                    assertEquals("bc1qhistoryfixture", row.invoice)
                    assertEquals("b".repeat(64), row.preimage)
                    assertEquals(if (row.type == TransactionType.Incoming) "Bitcoin received" else "Bitcoin sent", TransactionDisplay.title(row))
                }
                for (status in listOf(AppTransactionStatus.Pending, AppTransactionStatus.Completed, AppTransactionStatus.Failed)) {
                    assertEquals(2, rows.count { it.status == status })
                }
                val timeline = com.cashu.me.ui.history.unifiedFiltered(rows, emptyList(), HistoryFilter.All, "bitcoin")
                assertEquals(6, timeline.size)
                assertEquals(2, com.cashu.me.ui.history.unifiedFiltered(rows, emptyList(), HistoryFilter.Pending, "").size)
                assertEquals(2, com.cashu.me.ui.history.unifiedFiltered(rows, emptyList(), HistoryFilter.Completed, "").size)
                gateway.closeWalletRepository()
            }
        } finally {
            gateway.closeWalletRepository()
            directory.deleteRecursively()
        }
    }

    @Test fun onchainDepositTransitionsFromPaidQuoteToSingleCompletedReceipt() = runBlocking {
        val directory = File(context.cacheDir, UUID.randomUUID().toString()).apply { mkdirs() }
        val path = File(directory, "wallet.db").path
        val mint = MintUrl("https://offline.example:443")
        val gateway = CdkWalletGatewayImpl()
        try {
            val mnemonic = gateway.generateMnemonic()
            val quote = MintQuote(
                id = "onchain-deposit", amount = null, unit = CurrencyUnit.Sat, request = "bc1qhistoryfixture",
                state = QuoteState.PAID, expiry = 1u, mintUrl = mint, amountIssued = Amount(0u),
                amountPaid = Amount(2_100u), updatedAt = 1u, estimatedBlocks = null,
                paymentMethod = PaymentMethod.Onchain, secretKey = null, usedByOperation = null, version = 0u,
            )
            WalletSqliteDatabase(path).use { it.addMintQuote(quote) }
            val store = WalletStore(context, "onchain_transition_" + UUID.randomUUID())
            val mints = listOf(MintInfo("https://offline.example"))
            suspend fun load(): List<WalletTransaction> {
                gateway.openWalletRepository(mnemonic, path)
                return try { WalletTransactionLoader(store, gateway).load(mints, false).transactions }
                finally { gateway.closeWalletRepository() }
            }
            val pending = load().single()
            assertEquals(TransactionKind.Onchain, pending.kind)
            assertEquals(2_100L, pending.amount)
            assertEquals(AppTransactionStatus.Pending, pending.status)
            assertFalse(pending.isUnpaidInvoice)

            val transaction = Transaction(
                id = TransactionId("a".repeat(64)), mintUrl = mint, direction = TransactionDirection.INCOMING,
                amount = Amount(2_100u), fee = Amount(0u), unit = CurrencyUnit.Sat, ys = emptyList(),
                timestamp = 2u, memo = null, metadata = emptyMap(), quoteId = quote.id,
                paymentRequest = quote.request, paymentProof = null, paymentMethod = PaymentMethod.Onchain,
                sagaId = UUID.randomUUID().toString(), status = TransactionStatus.PENDING,
            )
            WalletSqliteDatabase(path).use { it.addTransaction(transaction) }
            val minting = load().single()
            assertNotEquals(quote.id, minting.id)
            assertEquals(AppTransactionStatus.Pending, minting.status)
            WalletSqliteDatabase(path).use {
                it.addTransaction(transaction.copy(status = TransactionStatus.COMPLETED))
                it.addMintQuote(quote.copy(state = QuoteState.ISSUED, amountIssued = Amount(2_100u)))
            }
            val completed = load().single()
            assertEquals(minting.id, completed.id)
            assertEquals(AppTransactionStatus.Completed, completed.status)
        } finally {
            gateway.closeWalletRepository()
            directory.deleteRecursively()
        }
    }

    @Test fun bolt11RetriesBecomeOneReceiptWithoutDeletingStoredAttempts() = runBlocking {
        val directory = File(context.cacheDir, UUID.randomUUID().toString()).apply { mkdirs() }
        val path = File(directory, "wallet.db").path
        val mint = MintUrl("https://offline.example")
        val gateway = CdkWalletGatewayImpl()
        try {
            val mnemonic = gateway.generateMnemonic()
            WalletSqliteDatabase(path).use { db ->
                repeat(6) { index ->
                    db.addTransaction(Transaction(
                        id = TransactionId("a".repeat(64)), mintUrl = mint,
                        direction = TransactionDirection.INCOMING, amount = Amount(64u), fee = Amount(0u),
                        unit = CurrencyUnit.Sat, ys = emptyList(), timestamp = (index + 1).toULong(),
                        memo = null, metadata = emptyMap(), quoteId = "retry-quote",
                        paymentRequest = "lnbc-fixture", paymentProof = null, paymentMethod = PaymentMethod.Bolt11,
                        sagaId = UUID.randomUUID().toString(),
                        status = if (index == 5) TransactionStatus.COMPLETED else TransactionStatus.FAILED,
                    ))
                }
            }
            repeat(2) {
                val stored = WalletSqliteDatabase(path).use { it.listTransactions(null, null, null) }
                assertEquals(6, stored.size)
                val completed = stored.single { it.status == TransactionStatus.COMPLETED }
                gateway.openWalletRepository(mnemonic, path)
                val store = WalletStore(context, "retry_history_" + UUID.randomUUID())
                val rows = WalletTransactionLoader(store, gateway).load(listOf(MintInfo(mint.url)), false).transactions
                assertEquals(listOf(completed.id.hex), rows.map { it.id })
                assertEquals(64L, rows.single().amount)
                assertEquals(AppTransactionStatus.Completed, rows.single().status)
                assertEquals(0L, gateway.unitBalance(mint.url, "sat"))
                gateway.closeWalletRepository()
            }
            WalletSqliteDatabase(path).use { assertEquals(6, it.listTransactions(null, null, null).size) }
        } finally {
            gateway.closeWalletRepository()
            directory.deleteRecursively()
        }
    }

    @Test fun discontinuedCurrencyHistorySurvivesDatabaseReopenWithoutAnAdvertisedWallet() = runBlocking {
        val directory = File(context.cacheDir, UUID.randomUUID().toString()).apply { mkdirs() }
        val path = File(directory, "wallet.db").path
        val mint = MintUrl("https://offline.example:443")
        val gateway = CdkWalletGatewayImpl()
        try {
            val mnemonic = gateway.generateMnemonic()
            WalletSqliteDatabase(path).use { db ->
                db.addMint(mint, null)
                db.addTransaction(Transaction(
                    id = TransactionId("a".repeat(64)), mintUrl = mint,
                    direction = TransactionDirection.INCOMING, amount = Amount(250u), fee = Amount(0u),
                    unit = CurrencyUnit.Usd, ys = emptyList(), timestamp = 1u, memo = null,
                    metadata = emptyMap(), quoteId = null, paymentRequest = null, paymentProof = null,
                    paymentMethod = null, sagaId = null, status = TransactionStatus.COMPLETED,
                ))
            }
            gateway.openWalletRepository(mnemonic, path)
            val account = gateway.storedAccounts().single { it.unit == "usd" }
            assertEquals(0L, gateway.storedAccountBalance(account))
            assertEquals(0L, gateway.unitBalance(mint.url, "usd"))
            val store = WalletStore(context, "stored_accounts_" + UUID.randomUUID())
            val loader = WalletTransactionLoader(store, gateway)
            val mints = listOf(MintInfo(url = mint.url, units = listOf("sat")))
            assertEquals(listOf("usd"), loader.load(mints, false).transactions.map { it.unit })
            gateway.closeWalletRepository()
            gateway.openWalletRepository(mnemonic, path)
            assertEquals(listOf("usd"), loader.load(mints, false).transactions.map { it.unit })
        } finally {
            gateway.closeWalletRepository()
            directory.deleteRecursively()
        }
    }

    @Test fun failedHistoryReadPreservesAffectedAccountWhileRefreshingOthers() = runBlocking {
        val fake = FakeWalletGateway()
        fake.openWalletRepository("fixture", "unused")
        fake.ensureWallet("https://a.example", "usd")
        fake.ensureWallet("https://b.example", "sat")
        fun tx(id: String, mint: String, unit: String) = com.cashu.me.Models.WalletTransaction(
            id = id, amount = 10, type = com.cashu.me.Models.TransactionType.Incoming,
            kind = com.cashu.me.Models.TransactionKind.Ecash, dateEpochMillis = 1,
            status = com.cashu.me.Models.TransactionStatus.Completed, mintUrl = mint, unit = unit,
        )
        fake.addTransaction(tx("usd-old", "https://a.example", "usd"))
        val store = WalletStore(context, "history_failure_" + UUID.randomUUID())
        val mints = listOf(MintInfo("https://a.example"), MintInfo("https://b.example"))
        WalletTransactionLoader(store, fake).load(mints, false)
        fake.addTransaction(tx("sat-new", "https://b.example", "sat"))
        val failing = object : CdkWalletGateway by fake {
            override suspend fun listTransactions(unitsByMint: Map<String, List<String>>): List<com.cashu.me.Models.WalletTransaction> {
                if (unitsByMint.containsKey("https://a.example")) error("Storage unavailable")
                return fake.listTransactions(unitsByMint)
            }
        }
        val result = WalletTransactionLoader(store, failing).load(mints, false)
        assertEquals(setOf("usd-old", "sat-new"), result.transactions.map { it.id }.toSet())
    }

    @Test fun accountAndDiscoveryFailuresDoNotSuppressFreshQuotes() = runBlocking {
        val fake = FakeWalletGateway()
        fake.openWalletRepository("fixture", "unused")
        val mint = "https://offline.example"
        fake.ensureWallet(mint, "sat")
        val quote = MintQuoteInfo(
            id = "invoice", request = "invoice-request", amount = 8,
            paymentMethod = PaymentMethodKind.Bolt11, state = MintQuoteState.Unpaid,
            expiryEpochSeconds = 1, mintUrl = "$mint:443/",
        )
        val staleInvoice = WalletTransaction(
            id = quote.id, quoteId = quote.id, amount = 8, type = TransactionType.Incoming,
            kind = TransactionKind.Lightning, dateEpochMillis = 1,
            status = AppTransactionStatus.Pending, mintUrl = quote.mintUrl,
        )
        val completed = staleInvoice.copy(
            id = "cdk-transaction", quoteId = "settled-quote", status = AppTransactionStatus.Completed,
        )
        var failDiscovery = false
        var failQuotes = false
        val failing = object : CdkWalletGateway by fake {
            override suspend fun storedAccounts(): List<WalletAccountReference> {
                if (failDiscovery) error("Discovery unavailable")
                return fake.storedAccounts()
            }
            override suspend fun listTransactions(unitsByMint: Map<String, List<String>>): List<WalletTransaction> =
                error("Account unavailable")
            override suspend fun listUnissuedMintQuotes(): List<MintQuoteInfo> {
                if (failQuotes) error("Quotes unavailable")
                return listOf(quote)
            }
        }
        val store = WalletStore(context, "quote_failure_" + UUID.randomUUID())
        val loader = WalletTransactionLoader(store, failing)
        for (discoveryFails in listOf(false, true)) {
            failDiscovery = discoveryFails
            failQuotes = false
            store.saveTransactions(listOf(staleInvoice, completed))
            val refreshed = loader.load(listOf(MintInfo(mint)), false).transactions
            assertEquals(AppTransactionStatus.Expired, refreshed.single { it.id == quote.id }.status)
            assertEquals(AppTransactionStatus.Completed, refreshed.single { it.id == completed.id }.status)
            assertEquals(2, refreshed.size)

            failQuotes = true
            store.saveTransactions(listOf(staleInvoice, completed))
            val retained = loader.load(listOf(MintInfo(mint)), false).transactions
            assertEquals(AppTransactionStatus.Pending, retained.single { it.id == quote.id }.status)
            assertEquals(2, retained.size)
        }
    }

    @Test fun discoveryFailureStillRebuildsLocallyParkedTokensAfterRelaunch() = runBlocking {
        val fake = FakeWalletGateway()
        fake.openWalletRepository("fixture", "unused")
        val failing = object : CdkWalletGateway by fake {
            override suspend fun storedAccounts(): List<WalletAccountReference> = error("Discovery unavailable")
        }
        val storeName = "pending_failure_" + UUID.randomUUID()
        val store = WalletStore(context, storeName)
        val pending = PendingReceiveToken(
            tokenId = "parked", token = "cashuAparked", amount = 8,
            dateEpochMillis = 1, mintUrl = "https://offline.example",
        )
        store.savePendingReceiveTokens(listOf(pending))
        val first = WalletTransactionLoader(store, failing).load(emptyList(), false).transactions
        assertEquals(listOf(pending.tokenId), first.map { it.id })
        assertTrue(first.single().isPendingReceiveToken)

        val reopenedStore = WalletStore(context, storeName)
        val relaunched = WalletTransactionLoader(reopenedStore, failing)
        assertEquals(listOf(pending.tokenId), relaunched.load(emptyList(), false).transactions.map { it.id })
        reopenedStore.savePendingReceiveTokens(emptyList())
        assertTrue(relaunched.load(emptyList(), false).transactions.isEmpty())
    }
}
