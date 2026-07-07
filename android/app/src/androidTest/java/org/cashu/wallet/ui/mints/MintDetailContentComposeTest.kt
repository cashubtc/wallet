package org.cashu.wallet.ui.mints

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.cashu.wallet.Models.MintContactInfo
import org.cashu.wallet.Models.MintInfo
import org.cashu.wallet.Models.MintNutSupport
import org.cashu.wallet.Models.MintPaymentMethodSetting
import org.cashu.wallet.Models.PaymentMethodKind
import org.cashu.wallet.ui.setCashuContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MintDetailContentComposeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun rendersNut06MetadataAcrossFullDetailContent() {
        compose.setCashuContent(fontScale = 1.3f) {
            MintDetailContent(
                mint = richMint(),
                isActive = true,
                connectionState = MintConnectionState.Online,
                showFullDescription = false,
                copiedUrl = true,
                unitBalances = mapOf("usd" to 1_234),
                loadedUnits = setOf("usd", "eur"),
                onToggleFullDescription = {},
                onCopyUrl = {},
                onOpenTerms = {},
                onOpenContact = {},
                onSetActive = {},
                onRemove = {},
                modifier = Modifier.verticalScroll(rememberScrollState()),
            )
        }

        compose.onNodeWithText("NUT-06 Mint").assertIsDisplayed()
        compose.onNodeWithText("Online").assertIsDisplayed()
        compose.onNodeWithText("Mint URL copied.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Lightning, On-chain, Locked ecash, HTLC, WebSockets")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("NUT-04").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Mint quotes supported").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Receive BOLT12").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("USD · min 100 · max 2500 · description · amountless")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("Balance (USD)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("\$12.34").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Balance (EUR)").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Not created").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("mintd 0.16.0").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("https://mint.example/terms").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("hello@mint.example").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Active mint").performScrollTo().assertIsDisplayed()
    }

    private fun richMint(): MintInfo =
        MintInfo(
            url = "https://mint.example",
            name = "NUT-06 Mint",
            pubkey = "02abcdef",
            description = "A test mint with full metadata.",
            descriptionLong = "Long metadata ".repeat(32),
            motd = "Maintenance window tonight.",
            balance = 210,
            urls = listOf("https://mint.example", "https://backup.mint.example"),
            tosUrl = "https://mint.example/terms",
            softwareName = "mintd",
            softwareVersion = "0.16.0",
            contacts = listOf(MintContactInfo(method = "email", info = "hello@mint.example")),
            nuts = MintNutSupport(
                nut04 = true,
                nut05 = true,
                nut10 = true,
                nut11 = true,
                nut14 = true,
                nut20 = true,
            ),
            units = listOf("sat", "usd", "eur"),
            mintUnits = listOf("sat", "usd"),
            supportedMintMethods = listOf(PaymentMethodKind.Bolt11, PaymentMethodKind.Bolt12, PaymentMethodKind.Onchain),
            supportedMeltMethods = listOf(PaymentMethodKind.Bolt11, PaymentMethodKind.Onchain),
            mintMethodSettings = listOf(
                MintPaymentMethodSetting(
                    method = PaymentMethodKind.Bolt12,
                    unit = "usd",
                    minAmount = 100,
                    maxAmount = 2_500,
                    supportsDescription = true,
                    supportsAmountless = true,
                ),
            ),
            meltMethodSettings = listOf(
                MintPaymentMethodSetting(
                    method = PaymentMethodKind.Onchain,
                    unit = "sat",
                    minAmount = 1_000,
                    maxAmount = 100_000,
                    supportsDescription = false,
                    supportsAmountless = false,
                ),
            ),
            onchainMintConfirmations = 2,
        )
}
