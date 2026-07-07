package org.cashu.wallet.ui.navigation

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.cashu.wallet.ui.setCashuContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackNavigationComposeTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun customBackHandlersDispatchEveryPolicyOutcome() {
        val activeOutcome = mutableStateOf("unset")
        var observedOutcome = "unset"

        compose.setCashuContent {
            BackHandler(enabled = true) {
                observedOutcome = activeOutcome.value
            }
            Text("Back policy harness")
        }

        val cases = listOf(
            "shell:scanner" to shellBackAction(scannerVisible = true, contactlessVisible = true)!!.name,
            "shell:contactless" to shellBackAction(scannerVisible = false, contactlessVisible = true)!!.name,
            "onboarding:welcome-stay" to onboardingBackAction(OnboardingBackState.Welcome, canExitOnboarding = false).name,
            "onboarding:welcome-exit" to onboardingBackAction(OnboardingBackState.Welcome, canExitOnboarding = true).name,
            "onboarding:show-mnemonic" to onboardingBackAction(OnboardingBackState.ShowMnemonic, canExitOnboarding = false).name,
            "onboarding:first-mint" to onboardingBackAction(OnboardingBackState.FirstMint, canExitOnboarding = false).name,
            "onboarding:first-mint-progress" to onboardingBackAction(OnboardingBackState.FirstMintProgress, canExitOnboarding = false).name,
            "onboarding:restore-method" to onboardingBackAction(OnboardingBackState.RestoreMethod, canExitOnboarding = false).name,
            "onboarding:cloud-restore" to onboardingBackAction(OnboardingBackState.CloudRestore, canExitOnboarding = false).name,
            "onboarding:restore-input" to onboardingBackAction(OnboardingBackState.RestoreInput, canExitOnboarding = false).name,
            "onboarding:restore-mints" to onboardingBackAction(OnboardingBackState.RestoreMints, canExitOnboarding = false).name,
            "onboarding:restore-progress" to onboardingBackAction(OnboardingBackState.RestoreProgress, canExitOnboarding = false).name,
            "unified-send:sending" to unifiedSendBackAction(sending = true, sent = false, failed = false, onConfirmStep = true, cameFromAmount = true, onInputStep = false).name,
            "unified-send:sent" to unifiedSendBackAction(sending = false, sent = true, failed = false, onConfirmStep = false, cameFromAmount = false, onInputStep = true).name,
            "unified-send:failed" to unifiedSendBackAction(sending = false, sent = false, failed = true, onConfirmStep = false, cameFromAmount = false, onInputStep = true).name,
            "unified-send:confirm-from-amount" to unifiedSendBackAction(sending = false, sent = false, failed = false, onConfirmStep = true, cameFromAmount = true, onInputStep = false).name,
            "unified-send:intermediate" to unifiedSendBackAction(sending = false, sent = false, failed = false, onConfirmStep = false, cameFromAmount = false, onInputStep = false).name,
            "unified-send:input" to unifiedSendBackAction(sending = false, sent = false, failed = false, onConfirmStep = false, cameFromAmount = false, onInputStep = true).name,
            "send-ecash:sending" to sendEcashBackAction(sending = true, generated = true)!!.name,
            "send-ecash:generated" to sendEcashBackAction(sending = false, generated = true)!!.name,
            "receive-ecash:status" to receiveEcashBackAction(hasStatus = true, reviewing = true).name,
            "receive-ecash:review" to receiveEcashBackAction(hasStatus = false, reviewing = true).name,
            "receive-lightning:success" to receiveLightningBackAction(successStatus = true, hasStatus = true, methodPickerOpen = true, unitPickerOpen = true, mintPickerOpen = true, displayingQuote = true).name,
            "receive-lightning:status" to receiveLightningBackAction(successStatus = false, hasStatus = true, methodPickerOpen = true, unitPickerOpen = true, mintPickerOpen = true, displayingQuote = true).name,
            "receive-lightning:method-picker" to receiveLightningBackAction(successStatus = false, hasStatus = false, methodPickerOpen = true, unitPickerOpen = true, mintPickerOpen = true, displayingQuote = true).name,
            "receive-lightning:unit-picker" to receiveLightningBackAction(successStatus = false, hasStatus = false, methodPickerOpen = false, unitPickerOpen = true, mintPickerOpen = true, displayingQuote = true).name,
            "receive-lightning:mint-picker" to receiveLightningBackAction(successStatus = false, hasStatus = false, methodPickerOpen = false, unitPickerOpen = false, mintPickerOpen = true, displayingQuote = true).name,
            "receive-lightning:display" to receiveLightningBackAction(successStatus = false, hasStatus = false, methodPickerOpen = false, unitPickerOpen = false, mintPickerOpen = false, displayingQuote = true).name,
            "history:search" to historyBackAction(searching = true)!!.name,
            "scanner:direct-close" to directSurfaceBackAction().name,
            "contactless:direct-close" to directSurfaceBackAction().name,
            "p2pk:detail" to p2pkBackAction(showingDetail = true)!!.name,
        )

        cases.forEach { (name, expected) ->
            observedOutcome = "unset"
            compose.runOnIdle { activeOutcome.value = expected }
            compose.waitForIdle()
            compose.activityRule.scenario.onActivity {
                it.onBackPressedDispatcher.onBackPressed()
            }
            compose.runOnIdle {
                assertEquals(name, expected, observedOutcome)
            }
        }
    }
}
