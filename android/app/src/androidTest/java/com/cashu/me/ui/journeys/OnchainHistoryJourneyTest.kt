package com.cashu.me.ui.journeys

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.cashu.me.Models.MintInfo
import com.cashu.me.Models.TransactionKind
import com.cashu.me.Models.TransactionStatus
import com.cashu.me.Models.TransactionType
import com.cashu.me.Models.WalletTransaction
import com.cashu.me.test.WalletJourneyRobot
import com.cashu.me.test.fixtures.AppTestFixture
import com.cashu.me.test.fixtures.FakeWalletGateway
import com.cashu.me.test.fixtures.FixtureMode
import com.cashu.me.ui.testing.UiTestTags
import kotlinx.coroutines.runBlocking
import java.util.UUID
import org.cashudevkit.Amount
import org.cashudevkit.CurrencyUnit
import org.cashudevkit.MintUrl
import org.cashudevkit.PaymentMethod
import org.cashudevkit.Transaction
import org.cashudevkit.TransactionDirection
import org.cashudevkit.TransactionId
import org.cashudevkit.WalletSqliteDatabase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class OnchainHistoryJourneyTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun fulfilledPaymentsFromRealDatabaseAppearInCompletedHistoryAndAfterReopen() {
        AppTestFixture.launch(FixtureMode.LiveSeededWithoutMint).use { fixture ->
            val robot = WalletJourneyRobot(compose).awaitTag(UiTestTags.WalletScreen)
            val container = fixture.container
            val path = container.walletDatabasePathManager.databaseFile.path
            runBlocking {
                WalletSqliteDatabase(path).use { db ->
                    for (direction in listOf(TransactionDirection.INCOMING, TransactionDirection.OUTGOING)) {
                        db.addTransaction(Transaction(
                            id = TransactionId("a".repeat(64)), mintUrl = MintUrl("https://offline.example:443"),
                            direction = direction, amount = Amount(2_100u), fee = Amount(10u), unit = CurrencyUnit.Sat,
                            ys = emptyList(), timestamp = (System.currentTimeMillis() / 1_000).toULong(),
                            memo = null, metadata = emptyMap(), quoteId = UUID.randomUUID().toString(),
                            paymentRequest = "bc1qfulfilledfixture", paymentProof = "b".repeat(64),
                            paymentMethod = PaymentMethod.Onchain, sagaId = UUID.randomUUID().toString(),
                            status = org.cashudevkit.TransactionStatus.COMPLETED,
                        ))
                    }
                    assertEquals(2, db.listTransactions(null, null, null).size)
                    assertEquals(0, db.getUnissuedMintQuotes().size)
                }
                val mint = MintInfo("https://offline.example/")
                container.walletStore.saveMints(listOf(mint))
                container.walletManager.setActiveMint(mint)
                container.walletManager.loadTransactions(includeRemoteObservations = false)
            }
            robot.tapText("History").awaitText("Bitcoin sent").awaitText("Bitcoin received")
                .tapDescription("Filter transactions").tapText("Completed")
                .awaitText("Bitcoin sent").awaitText("Bitcoin received")
                .tapText("Bitcoin sent").awaitText("Confirmed").awaitText("Transaction ID")
                .pressSystemBack()
            runBlocking {
                container.cdkGateway.closeWalletRepository()
                container.cdkGateway.openWalletRepository(FakeWalletGateway.FixedMnemonic, path)
                container.walletStore.saveTransactions(emptyList())
                container.walletManager.loadTransactions(includeRemoteObservations = false)
            }
            fixture.scenario.recreate()
            robot.awaitTag(UiTestTags.HistoryScreen)
                .awaitText("Bitcoin sent").awaitText("Bitcoin received")
                .tapText("Bitcoin received").awaitText("Confirmed").awaitText("Address")
                .awaitText("Transaction ID")
        }
    }

    @Test fun sentAndReceivedBitcoinRemainVisibleThroughSettlementAndRecreation() {
        AppTestFixture.launch(FixtureMode.SeededWithMint).use { fixture ->
            val robot = WalletJourneyRobot(compose).awaitTag(UiTestTags.WalletScreen)
            val sent = WalletTransaction(
                id = "onchain-sent", amount = 2_100, type = TransactionType.Outgoing,
                kind = TransactionKind.Onchain, dateEpochMillis = System.currentTimeMillis(),
                status = TransactionStatus.Pending, mintUrl = FakeWalletGateway.TestMintUrl,
                invoice = "bc1qhistoryfixture", preimage = "b".repeat(64),
            )
            val received = sent.copy(id = "onchain-received", type = TransactionType.Incoming,
                status = TransactionStatus.Completed)
            fixture.fakeGateway!!.addTransaction(sent)
            fixture.fakeGateway!!.addTransaction(received)
            runBlocking { fixture.container.walletManager.loadTransactions() }
            robot.tapText("History").awaitText("Bitcoin sent").awaitText("Bitcoin received")
            compose.onNodeWithTag(UiTestTags.transactionRow(sent.id)).assertIsDisplayed()
            compose.onNodeWithTag(UiTestTags.transactionRow(received.id)).assertIsDisplayed()
            robot.tapTag(UiTestTags.transactionRow(sent.id))
                .awaitText("Pending").awaitText("Address").awaitText("Transaction ID")
                .pressSystemBack()

            fixture.fakeGateway!!.addTransaction(sent.copy(status = TransactionStatus.Completed))
            runBlocking { fixture.container.walletManager.loadTransactions() }
            fixture.scenario.recreate()
            robot.awaitTag(UiTestTags.HistoryScreen)
                .tapDescription("Search history").typeIntoTag(UiTestTags.HistorySearch, "bitcoin")
                .awaitText("Bitcoin sent").awaitText("Bitcoin received")
            robot.tapTag(UiTestTags.transactionRow(sent.id))
                .awaitText("Confirmed").awaitText("Address").awaitText("Transaction ID")
        }
    }
}
