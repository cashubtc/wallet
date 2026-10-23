package com.cashu.me.ui.journeys

import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cashu.me.test.UiFailureArtifactsRule
import com.cashu.me.test.WalletJourneyRobot
import com.cashu.me.test.fixtures.AppTestFixture
import com.cashu.me.test.fixtures.FixtureMode
import com.cashu.me.test.fixtures.LaunchedFixture
import com.cashu.me.ui.testing.UiTestTags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Move ecash between two held mints: Mints → Transfer → review → status. */
@RunWith(AndroidJUnit4::class)
class MintTransferJourneyTest {
    @get:Rule(order = 0) val compose = createEmptyComposeRule()
    @get:Rule(order = 1) val artifacts = UiFailureArtifactsRule(compose) { fixture?.close() }
    private val robot by lazy { WalletJourneyRobot(compose) }
    private var fixture: LaunchedFixture? = null

    private fun launchFunded(): LaunchedFixture =
        AppTestFixture.launch(FixtureMode.FundedWithHistory).also {
            fixture = it
            robot.awaitTag(UiTestTags.WalletScreen).tapText("Mints")
                .awaitTag(UiTestTags.MintsScreen)
        }

    @Test fun transferRowWaitsForASecondMint() {
        launchFunded()
        robot.assertTagDoesNotExist(UiTestTags.MintsTransfer)
        addSecondMint()
        robot.awaitTag(UiTestTags.MintsTransfer)
    }

    @Test fun typedAmountMovesBetweenMintsAndReportsCompletion() {
        launchFunded()
        addSecondMint()
        robot.tapTag(UiTestTags.MintsTransfer).awaitTag(UiTestTags.MintTransferScreen)
            .assertTagIsNotEnabled(UiTestTags.MintTransferContinue)
            .tapDescription("5").tapDescription("0")
            .tapTag(UiTestTags.MintTransferContinue)
            .awaitText("Network fee").awaitText("Total")
            .tapTag(UiTestTags.MintTransferSubmit)
            .awaitText("Transfer complete")
    }

    private fun addSecondMint() {
        robot.tapDescription("Add mint")
            .awaitTag(UiTestTags.AddMintSheet)
            .replaceTextInTag(UiTestTags.AddMintUrl, SecondMintUrl)
            .tapTag(UiTestTags.AddMintSubmit)
            .awaitTag(UiTestTags.mintRow(SecondMintUrl))
    }

    companion object { private const val SecondMintUrl = "https://second.test" }
}
