package com.cashu.me.ui.journeys

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cashu.me.test.UiFailureArtifactsRule
import com.cashu.me.test.WalletJourneyRobot
import com.cashu.me.test.fixtures.AppTestFixture
import com.cashu.me.test.fixtures.FixtureMode
import com.cashu.me.test.fixtures.LaunchedFixture
import com.cashu.me.ui.send.AmountlessBolt11Hint
import com.cashu.me.ui.testing.UiTestTags
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * cashubtc/wallet#381: pasting a BOLT11 invoice with no amount must never leave
 * Send stuck. A mint advertising NUT-05 `amountless` gets the amount keypad;
 * otherwise the caution (like any paste hint) stays visible past the typing
 * debounce. iOS twin: `LiveCdkPaymentUITests.testPastedHintsStayVisible`.
 */
@RunWith(AndroidJUnit4::class)
class AmountlessInvoiceJourneyTest {
    @get:Rule(order = 0)
    val compose = createEmptyComposeRule()

    @get:Rule(order = 1)
    val failureArtifacts = UiFailureArtifactsRule(compose) { launched?.close() }

    private val robot by lazy { WalletJourneyRobot(compose) }
    private var launched: LaunchedFixture? = null

    @Test
    fun pastedAmountlessInvoiceOpensAmountEntryAndQuotesTheEnteredAmount() {
        val fixture = launch(supportsAmountlessBolt11Melt = true)
        val fake = checkNotNull(fixture.fakeGateway)
        setClipboard(AmountlessInvoice)

        robot.awaitTag(UiTestTags.WalletScreen)
            .tapTag(UiTestTags.WalletSend)
            .awaitTag(UiTestTags.SendSheet)
            .tapTextWithinTag(UiTestTags.SendSheet, "Paste")
            .awaitText("Continue")
            .tapDescription("2")
            .tapDescription("5")
            .tapText("Continue")
            .awaitTag(UiTestTags.SendPaymentSubmit)

        assertEquals(25L, fake.lastMeltQuoteAmountSats)
    }

    @Test
    fun pastedHintsStayVisibleWhenNothingCanBePaid() {
        launch(supportsAmountlessBolt11Melt = false)
        setClipboard(AmountlessInvoice)

        robot.awaitTag(UiTestTags.WalletScreen)
            .tapTag(UiTestTags.WalletSend)
            .awaitTag(UiTestTags.SendSheet)
            .tapTextWithinTag(UiTestTags.SendSheet, "Paste")
            .awaitText(AmountlessBolt11Hint)
        assertStillShownAfterDebounce(AmountlessBolt11Hint)
        robot.assertTagDoesNotExist(UiTestTags.SendPaymentSubmit)

        setClipboard("hello world")
        robot.tapDescription("Clear")
            .tapTextWithinTag(UiTestTags.SendSheet, "Paste")
            .awaitText(UnrecognizedHint)
        assertStillShownAfterDebounce(UnrecognizedHint)
    }

    /** The typing debounce re-runs detection 400 ms after the field changes. */
    private fun assertStillShownAfterDebounce(text: String) {
        Thread.sleep(1_200)
        compose.waitForIdle()
        compose.onNodeWithText(text).assertIsDisplayed()
    }

    private fun setClipboard(text: String) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            ApplicationProvider.getApplicationContext<Context>()
                .getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("destination", text))
        }
    }

    private fun launch(supportsAmountlessBolt11Melt: Boolean?): LaunchedFixture =
        AppTestFixture.launch(
            FixtureMode.FundedWithHistory,
            supportsAmountlessBolt11Melt = supportsAmountlessBolt11Melt,
        ).also { launched = it }

    private companion object {
        // BOLT #11 example: donation invoice with no amount in the HRP.
        const val AmountlessInvoice =
            "lnbc1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdpl2pkx2ctnv5sxxmmwwd5kgetjypeh2ursdae8g6twvus8g6rfwvs8qun0dfjkxaq9qrsgq357wnc5r2ueh7ck6q93dj32dlqnls087fxdwk8qakdyafkq3yap9us6v52vjjsrvywa6rt52cm9r9zqt8r2t7mlcwspyetp5h2tztugp9lfyql"
        const val UnrecognizedHint =
            "Unrecognized — try a Lightning address, invoice, Bitcoin address, or Cashu Request"
    }
}
