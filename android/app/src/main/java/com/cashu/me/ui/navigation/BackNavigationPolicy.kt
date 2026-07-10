package com.cashu.me.ui.navigation

enum class ShellBackAction {
    CloseReceiveDetail,
    CloseScanner,
    CloseContactless,
}

fun shellBackAction(
    receiveDetailVisible: Boolean,
    scannerVisible: Boolean,
    contactlessVisible: Boolean,
): ShellBackAction? =
    when {
        receiveDetailVisible -> ShellBackAction.CloseReceiveDetail
        scannerVisible -> ShellBackAction.CloseScanner
        contactlessVisible -> ShellBackAction.CloseContactless
        else -> null
    }

enum class OnboardingBackState {
    Welcome,
    ShowMnemonic,
    FirstMint,
    RestoreMethod,
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
    RestoreMints,
    Stay,
}

fun onboardingBackAction(
    state: OnboardingBackState,
    canExitOnboarding: Boolean,
    restoreInProgress: Boolean = false,
): OnboardingBackAction =
    when (state) {
        OnboardingBackState.Welcome ->
            if (canExitOnboarding) OnboardingBackAction.CloseRestoreFlow else OnboardingBackAction.Stay
        OnboardingBackState.ShowMnemonic -> OnboardingBackAction.Welcome
        OnboardingBackState.FirstMint -> OnboardingBackAction.ShowMnemonic
        OnboardingBackState.RestoreMethod -> OnboardingBackAction.Welcome
        OnboardingBackState.RestoreInput -> OnboardingBackAction.RestoreMethod
        OnboardingBackState.RestoreMints -> OnboardingBackAction.RestoreInput
        OnboardingBackState.RestoreProgress ->
            if (restoreInProgress) OnboardingBackAction.Stay else OnboardingBackAction.RestoreMints
    }

enum class UnifiedSendBackAction {
    Ignore,
    Close,
    ReturnToAmount,
    ResetToInput,
}

fun unifiedSendBackAction(
    sending: Boolean,
    statusVisible: Boolean,
    onConfirmStep: Boolean,
    cameFromAmount: Boolean,
    onInputStep: Boolean,
): UnifiedSendBackAction =
    when {
        sending -> UnifiedSendBackAction.Ignore
        statusVisible -> UnifiedSendBackAction.Close
        onConfirmStep && cameFromAmount -> UnifiedSendBackAction.ReturnToAmount
        !onInputStep -> UnifiedSendBackAction.ResetToInput
        else -> UnifiedSendBackAction.Close
    }

enum class SendEcashBackAction {
    Ignore,
    ReturnToInput,
    Close,
}

fun sendEcashBackAction(sending: Boolean, generated: Boolean): SendEcashBackAction =
    when {
        sending -> SendEcashBackAction.Ignore
        generated -> SendEcashBackAction.ReturnToInput
        else -> SendEcashBackAction.Close
    }

enum class ReceiveEcashBackAction {
    Ignore,
    ReturnToPaste,
    Close,
}

fun receiveEcashBackAction(claiming: Boolean, reviewing: Boolean): ReceiveEcashBackAction =
    when {
        claiming -> ReceiveEcashBackAction.Ignore
        reviewing -> ReceiveEcashBackAction.ReturnToPaste
        else -> ReceiveEcashBackAction.Close
    }

enum class ReceiveLightningBackAction {
    ReturnToInput,
    Close,
}

fun receiveLightningBackAction(displayingQuote: Boolean): ReceiveLightningBackAction =
    if (displayingQuote) ReceiveLightningBackAction.ReturnToInput else ReceiveLightningBackAction.Close

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
