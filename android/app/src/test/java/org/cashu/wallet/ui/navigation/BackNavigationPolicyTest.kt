package org.cashu.wallet.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackNavigationPolicyTest {
    @Test
    fun shellBackPrioritizesScannerBeforeContactless() {
        assertEquals(ShellBackAction.CloseScanner, shellBackAction(scannerVisible = true, contactlessVisible = true))
        assertEquals(ShellBackAction.CloseContactless, shellBackAction(scannerVisible = false, contactlessVisible = true))
        assertNull(shellBackAction(scannerVisible = false, contactlessVisible = false))
    }

    @Test
    fun onboardingBackMovesOneLogicalStepOrStaysDuringProgress() {
        assertEquals(OnboardingBackAction.Stay, onboardingBackAction(OnboardingBackState.Welcome, canExitOnboarding = false))
        assertEquals(OnboardingBackAction.CloseRestoreFlow, onboardingBackAction(OnboardingBackState.Welcome, canExitOnboarding = true))
        assertEquals(OnboardingBackAction.Welcome, onboardingBackAction(OnboardingBackState.ShowMnemonic, canExitOnboarding = false))
        assertEquals(OnboardingBackAction.ShowMnemonic, onboardingBackAction(OnboardingBackState.FirstMint, canExitOnboarding = false))
        assertEquals(OnboardingBackAction.Stay, onboardingBackAction(OnboardingBackState.FirstMintProgress, canExitOnboarding = false))
        assertEquals(OnboardingBackAction.Welcome, onboardingBackAction(OnboardingBackState.RestoreMethod, canExitOnboarding = false))
        assertEquals(OnboardingBackAction.RestoreMethod, onboardingBackAction(OnboardingBackState.CloudRestore, canExitOnboarding = false))
        assertEquals(OnboardingBackAction.RestoreMethod, onboardingBackAction(OnboardingBackState.RestoreInput, canExitOnboarding = false))
        assertEquals(OnboardingBackAction.RestoreInput, onboardingBackAction(OnboardingBackState.RestoreMints, canExitOnboarding = false))
        assertEquals(OnboardingBackAction.Stay, onboardingBackAction(OnboardingBackState.RestoreProgress, canExitOnboarding = false))
    }

    @Test
    fun unifiedSendBackHandlesStatusesBeforeRouteSteps() {
        assertEquals(UnifiedSendBackAction.Ignore, unifiedSendBackAction(sending = true, sent = false, failed = false, onConfirmStep = true, cameFromAmount = true, onInputStep = false))
        assertEquals(UnifiedSendBackAction.Close, unifiedSendBackAction(sending = false, sent = true, failed = false, onConfirmStep = false, cameFromAmount = false, onInputStep = true))
        assertEquals(UnifiedSendBackAction.ClearStatus, unifiedSendBackAction(sending = false, sent = false, failed = true, onConfirmStep = false, cameFromAmount = false, onInputStep = true))
        assertEquals(UnifiedSendBackAction.ReturnToAmount, unifiedSendBackAction(sending = false, sent = false, failed = false, onConfirmStep = true, cameFromAmount = true, onInputStep = false))
        assertEquals(UnifiedSendBackAction.ResetToInput, unifiedSendBackAction(sending = false, sent = false, failed = false, onConfirmStep = false, cameFromAmount = false, onInputStep = false))
        assertEquals(UnifiedSendBackAction.Close, unifiedSendBackAction(sending = false, sent = false, failed = false, onConfirmStep = false, cameFromAmount = false, onInputStep = true))
    }

    @Test
    fun sendEcashBackOnlyConsumesGeneratedOrSendingState() {
        assertEquals(SendEcashBackAction.Ignore, sendEcashBackAction(sending = true, generated = true))
        assertEquals(SendEcashBackAction.ReturnToInput, sendEcashBackAction(sending = false, generated = true))
        assertNull(sendEcashBackAction(sending = false, generated = false))
    }

    @Test
    fun receiveBackPoliciesCloseStatusBeforeNestedFaces() {
        assertEquals(ReceiveEcashBackAction.ClearStatus, receiveEcashBackAction(hasStatus = true, reviewing = true))
        assertEquals(ReceiveEcashBackAction.ReturnToPaste, receiveEcashBackAction(hasStatus = false, reviewing = true))
        assertEquals(ReceiveEcashBackAction.Close, receiveEcashBackAction(hasStatus = false, reviewing = false))

        assertEquals(
            ReceiveLightningBackAction.Close,
            receiveLightningBackAction(successStatus = true, hasStatus = true, methodPickerOpen = true, unitPickerOpen = true, mintPickerOpen = true, displayingQuote = true),
        )
        assertEquals(
            ReceiveLightningBackAction.ClearStatus,
            receiveLightningBackAction(successStatus = false, hasStatus = true, methodPickerOpen = true, unitPickerOpen = true, mintPickerOpen = true, displayingQuote = true),
        )
        assertEquals(
            ReceiveLightningBackAction.CloseMethodPicker,
            receiveLightningBackAction(successStatus = false, hasStatus = false, methodPickerOpen = true, unitPickerOpen = true, mintPickerOpen = true, displayingQuote = true),
        )
        assertEquals(
            ReceiveLightningBackAction.CloseUnitPicker,
            receiveLightningBackAction(successStatus = false, hasStatus = false, methodPickerOpen = false, unitPickerOpen = true, mintPickerOpen = true, displayingQuote = true),
        )
        assertEquals(
            ReceiveLightningBackAction.CloseMintPicker,
            receiveLightningBackAction(successStatus = false, hasStatus = false, methodPickerOpen = false, unitPickerOpen = false, mintPickerOpen = true, displayingQuote = true),
        )
        assertEquals(
            ReceiveLightningBackAction.ReturnToInput,
            receiveLightningBackAction(successStatus = false, hasStatus = false, methodPickerOpen = false, unitPickerOpen = false, mintPickerOpen = false, displayingQuote = true),
        )
        assertEquals(
            ReceiveLightningBackAction.Close,
            receiveLightningBackAction(successStatus = false, hasStatus = false, methodPickerOpen = false, unitPickerOpen = false, mintPickerOpen = false, displayingQuote = false),
        )
    }

    @Test
    fun simpleBackPoliciesCoverSearchDetailAndDirectSurfaces() {
        assertEquals(SimpleBackAction.CloseSearch, historyBackAction(searching = true))
        assertNull(historyBackAction(searching = false))
        assertEquals(SimpleBackAction.CloseDetail, p2pkBackAction(showingDetail = true))
        assertNull(p2pkBackAction(showingDetail = false))
        assertEquals(SimpleBackAction.Close, directSurfaceBackAction())
    }
}
