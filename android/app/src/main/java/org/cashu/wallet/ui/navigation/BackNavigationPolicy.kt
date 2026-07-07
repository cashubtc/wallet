package org.cashu.wallet.ui.navigation

enum class ShellBackAction {
    CloseScanner,
    CloseContactless,
}

fun shellBackAction(scannerVisible: Boolean, contactlessVisible: Boolean): ShellBackAction? =
    when {
        scannerVisible -> ShellBackAction.CloseScanner
        contactlessVisible -> ShellBackAction.CloseContactless
        else -> null
    }

enum class OnboardingBackState {
    Welcome,
    ShowMnemonic,
    FirstMint,
    FirstMintProgress,
    RestoreMethod,
    CloudRestore,
    RestoreInput,
    RestoreMints,
    RestoreProgress,
}

enum class OnboardingBackAction {
    CloseRestoreFlow,
    Welcome,
    ShowMnemonic,
    RestoreMethod,
    RestoreInput,
    Stay,
}

fun onboardingBackAction(
    state: OnboardingBackState,
    canExitOnboarding: Boolean,
): OnboardingBackAction =
    when (state) {
        OnboardingBackState.Welcome ->
            if (canExitOnboarding) OnboardingBackAction.CloseRestoreFlow else OnboardingBackAction.Stay
        OnboardingBackState.ShowMnemonic -> OnboardingBackAction.Welcome
        OnboardingBackState.FirstMint -> OnboardingBackAction.ShowMnemonic
        OnboardingBackState.FirstMintProgress -> OnboardingBackAction.Stay
        OnboardingBackState.RestoreMethod -> OnboardingBackAction.Welcome
        OnboardingBackState.CloudRestore -> OnboardingBackAction.RestoreMethod
        OnboardingBackState.RestoreInput -> OnboardingBackAction.RestoreMethod
        OnboardingBackState.RestoreMints -> OnboardingBackAction.RestoreInput
        OnboardingBackState.RestoreProgress -> OnboardingBackAction.Stay
    }

enum class UnifiedSendBackAction {
    Ignore,
    Close,
    ClearStatus,
    ReturnToAmount,
    ResetToInput,
}

fun unifiedSendBackAction(
    sending: Boolean,
    sent: Boolean,
    failed: Boolean,
    onConfirmStep: Boolean,
    cameFromAmount: Boolean,
    onInputStep: Boolean,
): UnifiedSendBackAction =
    when {
        sending -> UnifiedSendBackAction.Ignore
        sent -> UnifiedSendBackAction.Close
        failed -> UnifiedSendBackAction.ClearStatus
        onConfirmStep && cameFromAmount -> UnifiedSendBackAction.ReturnToAmount
        !onInputStep -> UnifiedSendBackAction.ResetToInput
        else -> UnifiedSendBackAction.Close
    }

enum class SendEcashBackAction {
    Ignore,
    ReturnToInput,
}

fun sendEcashBackAction(sending: Boolean, generated: Boolean): SendEcashBackAction? =
    when {
        sending -> SendEcashBackAction.Ignore
        generated -> SendEcashBackAction.ReturnToInput
        else -> null
    }

enum class ReceiveEcashBackAction {
    ClearStatus,
    ReturnToPaste,
    Close,
}

fun receiveEcashBackAction(hasStatus: Boolean, reviewing: Boolean): ReceiveEcashBackAction =
    when {
        hasStatus -> ReceiveEcashBackAction.ClearStatus
        reviewing -> ReceiveEcashBackAction.ReturnToPaste
        else -> ReceiveEcashBackAction.Close
    }

enum class ReceiveLightningBackAction {
    Close,
    ClearStatus,
    CloseMethodPicker,
    CloseUnitPicker,
    CloseMintPicker,
    ReturnToInput,
}

fun receiveLightningBackAction(
    successStatus: Boolean,
    hasStatus: Boolean,
    methodPickerOpen: Boolean,
    unitPickerOpen: Boolean,
    mintPickerOpen: Boolean,
    displayingQuote: Boolean,
): ReceiveLightningBackAction =
    when {
        successStatus -> ReceiveLightningBackAction.Close
        hasStatus -> ReceiveLightningBackAction.ClearStatus
        methodPickerOpen -> ReceiveLightningBackAction.CloseMethodPicker
        unitPickerOpen -> ReceiveLightningBackAction.CloseUnitPicker
        mintPickerOpen -> ReceiveLightningBackAction.CloseMintPicker
        displayingQuote -> ReceiveLightningBackAction.ReturnToInput
        else -> ReceiveLightningBackAction.Close
    }

enum class SimpleBackAction {
    Close,
    CloseSearch,
    CloseDetail,
}

fun historyBackAction(searching: Boolean): SimpleBackAction? =
    if (searching) SimpleBackAction.CloseSearch else null

fun p2pkBackAction(showingDetail: Boolean): SimpleBackAction? =
    if (showingDetail) SimpleBackAction.CloseDetail else null

fun directSurfaceBackAction(): SimpleBackAction = SimpleBackAction.Close
