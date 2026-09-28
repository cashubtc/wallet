package com.cashu.me.ui.journeys

import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cashu.me.test.UiFailureArtifactsRule
import com.cashu.me.test.WalletJourneyRobot
import com.cashu.me.test.fixtures.AppTestFixture
import com.cashu.me.test.fixtures.FixtureMode
import com.cashu.me.test.fixtures.LaunchedFixture
import com.cashu.me.ui.testing.UiTestTags
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Max on a Lightning payment must leave room for the mint's fee reserve, or
 * the confirm step rejects the amount Max just filled in. The fixture wallet
 * holds 500 sat and the fake mint reserves 2 sat per melt.
 */
@RunWith(AndroidJUnit4::class)
class SendMaxJourneyTest {
    @get:Rule(order = 0)
    val compose = createEmptyComposeRule()

    @get:Rule(order = 1)
    val failureArtifacts = UiFailureArtifactsRule(compose) { launched?.close() }

    private val robot by lazy { WalletJourneyRobot(compose) }
    private var launched: LaunchedFixture? = null

    @Test
    fun maxOnALightningAddressLeavesRoomForTheFeeReserve() {
        val fixture = AppTestFixture.launch(FixtureMode.FundedWithHistory).also { launched = it }
        val fake = checkNotNull(fixture.fakeGateway)

        robot.awaitTag(UiTestTags.WalletScreen)
            .tapTag(UiTestTags.WalletSend)
            .awaitTag(UiTestTags.SendSheet)
            .typeIntoTag(UiTestTags.SendDestination, "alice@example.com")
            .awaitText("Continue")
            .tapDescription("Send maximum")
        compose.waitUntil(WalletJourneyRobot.DefaultTimeout) { fake.lastMeltQuoteAmountSats == 498L }

        robot.tapText("Continue")
            .awaitTag(UiTestTags.SendPaymentSubmit)
            .assertTextDoesNotExist("Not enough balance", substring = true)
        assertEquals(498L, fake.lastMeltQuoteAmountSats)
    }
}
