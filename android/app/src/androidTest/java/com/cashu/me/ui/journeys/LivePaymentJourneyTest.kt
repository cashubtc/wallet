package com.cashu.me.ui.journeys

import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import com.cashu.me.liveintegration.PaymentFixtureTest
import com.cashu.me.Models.TransactionKind
import com.cashu.me.Models.TransactionStatus
import com.cashu.me.Models.TransactionType
import com.cashu.me.test.UiFailureArtifactsRule
import com.cashu.me.test.WalletJourneyRobot
import com.cashu.me.test.fixtures.AppTestFixture
import com.cashu.me.test.fixtures.FixtureMode
import com.cashu.me.test.fixtures.LaunchedFixture
import com.cashu.me.ui.testing.UiTestTags
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class LivePaymentJourneyTest : PaymentFixtureTest() {
    @get:Rule(order = 0) val compose = createEmptyComposeRule()
    @get:Rule(order = 1) val artifacts = UiFailureArtifactsRule(compose) { launched?.close() }
    private val robot by lazy { WalletJourneyRobot(compose) }
    private var launched: LaunchedFixture? = null

    @Test fun cdkReceivePayAndReopenHistory() = receivePayAndReopen("cdk")
    @Test fun nutshellReceivePayAndReopenHistory() = receivePayAndReopen("nutshell")

    @Test fun failedCashuRequestReturnsFunds() = exerciseFailedCashuRequest(reclaimUnavailable = false)

    @Test fun failedCashuRequestWithUnavailableReclaimStaysVisible() = exerciseFailedCashuRequest(reclaimUnavailable = true)

    private fun exerciseFailedCashuRequest(reclaimUnavailable: Boolean) {
        val app = AppTestFixture.launch(FixtureMode.LivePayments, paymentMintUrl = mintUrl("controlled"))
        launched = app
        robot.awaitTag(UiTestTags.WalletScreen)
        val manager = app.container.walletManager
        runBlocking {
            val source = wallet("controlled")
            source.fund()
            assertEquals(100L, manager.receiveTokens(source.send(100).token))
        }
        val encoded = call("$root/request", "POST", buildJsonObject {
            put("target", if (reclaimUnavailable) "$fixture$root/receive?fail=true&wait=true" else "$fixture$root/unavailable")
            put("amount", 21)
            putJsonArray("mints") { add(mintUrl("controlled")) }
        }).getValue("request").jsonPrimitive.content
        robot.tapTag(UiTestTags.WalletSend).awaitTag(UiTestTags.SendSheet)
            .typeIntoTag(UiTestTags.SendDestination, encoded)
            .awaitTag(UiTestTags.SendPaymentSubmit).tapTag(UiTestTags.SendPaymentSubmit)
        if (reclaimUnavailable) {
            runBlocking {
                var delivered = false
                repeat(100) {
                    if (!delivered) {
                        delivered = runCatching { call("$root/received-token") }.isSuccess
                        if (!delivered) delay(100)
                    }
                }
                assertTrue("Payment must reach delivery before blocking reclaim", delivered)
                arm("/v1/swap", "reject", count = 20)
                call("$root/release-delivery", "POST")
            }
            robot.awaitText("Pending ecash", timeoutMillis = 30_000)
            assertEquals(79L, manager.state.value.balance)
            val pending = manager.state.value.transactions.single { it.type == TransactionType.Outgoing }
            assertEquals(TransactionStatus.Pending, pending.status)
            robot.pressSystemBack().awaitTag(UiTestTags.WalletScreen)
                .awaitText("Ecash sent")
            return
        }
        robot.awaitText("The payment couldn't be delivered. Unclaimed ecash was returned to your wallet. Mint fees may apply.", timeoutMillis = 30_000)
        // The production payment path must publish this without a manual refresh.
        assertEquals(100L, manager.state.value.balance)
        val payment = manager.state.value.transactions.single { it.type == TransactionType.Outgoing }
        assertEquals(TransactionStatus.Failed, payment.status)
        robot.pressSystemBack().awaitTag(UiTestTags.WalletScreen)
            .tapText("History").awaitTag(UiTestTags.transactionRow(payment.id))
        app.scenario.recreate()
        robot.awaitTag(UiTestTags.HistoryScreen).awaitTag(UiTestTags.transactionRow(payment.id))
        assertEquals(100L, manager.state.value.balance)
        runBlocking {
            val recipient = wallet("controlled")
            val token = manager.sendTokens(100, null, null, mintUrl("controlled"))
            assertEquals(100L, recipient.receive(token.token))
        }
    }

    private fun receivePayAndReopen(mint: String) {
        val fixture = AppTestFixture.launch(FixtureMode.LivePayments, paymentMintUrl = mintUrl(mint))
        launched = fixture
        robot.awaitTag(UiTestTags.WalletScreen)
            .tapTag(UiTestTags.WalletReceive)
            .tapDescription("Bitcoin. Receive over Lightning or on-chain")
            .awaitTag(UiTestTags.ReceiveLightningScreen)
            .tapDescription("1").tapDescription("0").tapDescription("0")
            .tapTextWithinTag(UiTestTags.ReceiveLightningScreen, "Create invoice")
            .awaitText("Payment Received!", timeoutMillis = 30_000)
            .tapText("Done")
            .awaitTag(UiTestTags.WalletScreen)
        val manager = fixture.container.walletManager
        assertEquals(100L, manager.state.value.balance)
        robot.tapTag(UiTestTags.WalletSend)
            .awaitTag(UiTestTags.SendSheet)
            .typeIntoTag(UiTestTags.SendDestination, invoice())
            .awaitTag(UiTestTags.SendPaymentSubmit)
            .tapTag(UiTestTags.SendPaymentSubmit)
            .awaitText("Payment sent", timeoutMillis = 30_000)
        val payment = manager.state.value.transactions.single {
            it.type == TransactionType.Outgoing && it.kind == TransactionKind.Lightning
        }
        assertEquals(TransactionStatus.Completed, payment.status)
        assertEquals(21L, payment.amount)
        assertEquals(100L - 21 - payment.fee, manager.state.value.balance)
        robot.tapText("Done").awaitTag(UiTestTags.WalletScreen).tapText("History")
            .awaitTag(UiTestTags.transactionRow(payment.id))
        fixture.scenario.recreate()
        robot.awaitTag(UiTestTags.HistoryScreen).awaitTag(UiTestTags.transactionRow(payment.id))
    }
}
