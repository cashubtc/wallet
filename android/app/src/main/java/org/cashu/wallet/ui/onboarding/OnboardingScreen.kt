package org.cashu.wallet.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.cashu.wallet.Core.MnemonicInput
import org.cashu.wallet.Core.SentryService
import org.cashu.wallet.Core.mintUrlCandidates
import org.cashu.wallet.Core.shortenMintUrl
import org.cashu.wallet.Core.WalletManager
import org.cashu.wallet.Models.MintInfo
import org.cashu.wallet.Models.RestoreMintResult
import org.cashu.wallet.ui.components.CashuTextField
import org.cashu.wallet.ui.components.GhostButton
import org.cashu.wallet.ui.components.InlineNotice
import org.cashu.wallet.ui.components.MintAvatar
import org.cashu.wallet.ui.components.PrimaryButton
import org.cashu.wallet.ui.theme.CashuTheme

private data class RecommendedMint(val name: String, val url: String)

private val RecommendedMints = listOf(
    RecommendedMint("Minibits", "https://mint.minibits.cash/Bitcoin"),
    RecommendedMint("Coinos", "https://mint.coinos.io"),
    RecommendedMint("Macadamia", "https://mint.macadamia.cash"),
)

private sealed interface OnboardingStep {
    data object Welcome : OnboardingStep
    data class ShowMnemonic(val mnemonic: String) : OnboardingStep
    data class FirstMint(val mnemonic: String) : OnboardingStep
    data class FirstMintProgress(val mnemonic: String, val mintUrls: List<String>) : OnboardingStep
    data object RestoreMethod : OnboardingStep
    data object CloudRestore : OnboardingStep
    data object RestoreInput : OnboardingStep
    data class RestoreMints(val mnemonic: String) : OnboardingStep
    data class RestoreProgress(val mnemonic: String, val mintUrls: List<String>) : OnboardingStep
}

@Composable
fun OnboardingScreen(
    walletManager: WalletManager,
    sentryService: SentryService? = null,
) {
    val walletState by walletManager.state.collectAsState()
    val scope = rememberCoroutineScope()

    var step: OnboardingStep by remember { mutableStateOf(OnboardingStep.Welcome) }
    var direction by remember { mutableStateOf(1) }
    var infoOpen by remember { mutableStateOf(false) }

    fun goTo(next: OnboardingStep, forward: Boolean = true) {
        direction = if (forward) 1 else -1
        step = next
        sentryService?.breadcrumb("Onboarding step: ${next.analyticsName()}", category = "onboarding")
    }

    fun goBack() {
        when (val current = step) {
            OnboardingStep.Welcome -> if (walletState.canExitOnboarding) walletManager.closeRestoreFlow()
            is OnboardingStep.ShowMnemonic -> goTo(OnboardingStep.Welcome, forward = false)
            is OnboardingStep.FirstMint -> goTo(OnboardingStep.ShowMnemonic(current.mnemonic), forward = false)
            is OnboardingStep.FirstMintProgress -> Unit
            OnboardingStep.RestoreMethod -> goTo(OnboardingStep.Welcome, forward = false)
            OnboardingStep.CloudRestore -> goTo(OnboardingStep.RestoreMethod, forward = false)
            OnboardingStep.RestoreInput -> goTo(OnboardingStep.RestoreMethod, forward = false)
            is OnboardingStep.RestoreMints -> goTo(OnboardingStep.RestoreInput, forward = false)
            is OnboardingStep.RestoreProgress -> Unit
        }
    }

    BackHandler(enabled = true) { goBack() }

    AnimatedContent(
        targetState = step,
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .imePadding(),
        transitionSpec = { stepTransition(direction) },
        label = "onboarding-step",
    ) { current ->
        when (current) {
            OnboardingStep.Welcome -> WelcomeFace(
                isLoading = walletState.isLoading,
                onCreate = {
                    scope.launch {
                        val mnemonic = runCatching {
                            walletManager.generateMnemonicForOnboarding()
                        }.getOrNull() ?: return@launch
                        goTo(OnboardingStep.ShowMnemonic(mnemonic))
                    }
                },
                onRestore = { goTo(OnboardingStep.RestoreMethod) },
                onInfo = { infoOpen = true },
                canExit = walletState.canExitOnboarding,
                onExit = { walletManager.closeRestoreFlow() },
            )

            is OnboardingStep.ShowMnemonic -> ShowMnemonicFace(
                mnemonic = current.mnemonic,
                onBack = { goTo(OnboardingStep.Welcome, forward = false) },
                onContinue = { goTo(OnboardingStep.FirstMint(current.mnemonic)) },
            )

            is OnboardingStep.FirstMint -> FirstMintFace(
                isLoading = walletState.isLoading,
                onBack = { goTo(OnboardingStep.ShowMnemonic(current.mnemonic), forward = false) },
                onFinish = { selectedMintUrls ->
                    goTo(OnboardingStep.FirstMintProgress(current.mnemonic, selectedMintUrls))
                },
            )

            is OnboardingStep.FirstMintProgress -> FirstMintProgressFace(
                walletManager = walletManager,
                mnemonic = current.mnemonic,
                mintUrls = current.mintUrls,
                onOpenWallet = {
                    scope.launch {
                        runCatching { walletManager.completeOnboarding() }
                    }
                },
            )

            OnboardingStep.RestoreMethod -> RestoreMethodFace(
                onBack = { goTo(OnboardingStep.Welcome, forward = false) },
                onCloudRestore = { goTo(OnboardingStep.CloudRestore) },
                onSeedPhrase = { goTo(OnboardingStep.RestoreInput) },
            )

            OnboardingStep.CloudRestore -> CloudRestoreUnavailableFace(
                onBack = { goTo(OnboardingStep.RestoreMethod, forward = false) },
                onSeedPhrase = { goTo(OnboardingStep.RestoreInput) },
            )

            OnboardingStep.RestoreInput -> RestoreInputFace(
                isLoading = walletState.isLoading,
                onBack = { goTo(OnboardingStep.RestoreMethod, forward = false) },
                onRestore = { mnemonic ->
                    goTo(OnboardingStep.RestoreMints(MnemonicInput.normalize(mnemonic)))
                },
                errorMessage = walletState.errorMessage,
            )

            is OnboardingStep.RestoreMints -> RestoreMintsFace(
                walletManager = walletManager,
                onBack = { goTo(OnboardingStep.RestoreInput, forward = false) },
                onRestore = { mintUrls -> goTo(OnboardingStep.RestoreProgress(current.mnemonic, mintUrls)) },
            )

            is OnboardingStep.RestoreProgress -> RestoreProgressFace(
                walletManager = walletManager,
                mnemonic = current.mnemonic,
                mintUrls = current.mintUrls,
                onOpenWallet = {
                    scope.launch {
                        runCatching { walletManager.completeOnboarding() }
                    }
                },
            )
        }
    }

    if (infoOpen) {
        EcashInfoDialog(onDismiss = { infoOpen = false })
    }
}

private fun OnboardingStep.analyticsName(): String =
    when (this) {
        OnboardingStep.Welcome -> "welcome"
        is OnboardingStep.ShowMnemonic -> "show_mnemonic"
        is OnboardingStep.FirstMint -> "first_mint"
        is OnboardingStep.FirstMintProgress -> "first_mint_progress"
        OnboardingStep.RestoreMethod -> "restore_method"
        OnboardingStep.CloudRestore -> "cloud_restore"
        OnboardingStep.RestoreInput -> "restore_input"
        is OnboardingStep.RestoreMints -> "restore_mints"
        is OnboardingStep.RestoreProgress -> "restore_progress"
    }

@Composable
private fun WelcomeFace(
    isLoading: Boolean,
    onCreate: () -> Unit,
    onRestore: () -> Unit,
    onInfo: () -> Unit,
    canExit: Boolean,
    onExit: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = CashuTheme.spacing.section, vertical = CashuTheme.spacing.page + CashuTheme.spacing.micro),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(0.8f))
        Text(
            text = "CASHU",
            style = MaterialTheme.typography.labelSmall.copy(
                letterSpacing = 3.sp,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(CashuTheme.spacing.default))
        Text(
            text = "Private cash.\nIn your pocket.",
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.weight(1f))
        PrimaryButton(
            text = "Create wallet",
            onClick = onCreate,
            loading = isLoading,
        )
        Spacer(Modifier.height(CashuTheme.spacing.snug))
        PrimaryButton(
            text = "I have a seed phrase",
            onClick = onRestore,
        )
        Spacer(Modifier.height(CashuTheme.spacing.snug))
        GhostButton(
            text = "What is ecash?",
            onClick = onInfo,
        )
        if (canExit) {
            Spacer(Modifier.height(CashuTheme.spacing.snug))
            GhostButton(
                text = "Back to wallet",
                onClick = onExit,
            )
        }
    }
}

@Composable
private fun ShowMnemonicFace(
    mnemonic: String,
    onBack: () -> Unit,
    onContinue: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val words = remember(mnemonic) {
        mnemonic.trim().split(' ').filter { it.isNotBlank() }
    }
    var revealed by remember { mutableStateOf(false) }
    var acknowledged by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(3000)
            copied = false
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(CashuTheme.spacing.section)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.comfortable),
    ) {
        Text(
            text = "Your recovery phrase",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "Write these 12 words down in order. This is the only way to recover your wallet.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "Never share these words with anyone.",
            style = MaterialTheme.typography.bodySmall,
            color = CashuTheme.colors.pending,
        )
        if (revealed) {
            SeedGrid(words = words)
            GhostButton(
                text = if (copied) "Copied" else "Copy phrase",
                onClick = {
                    clipboard.setText(AnnotatedString(mnemonic))
                    copied = true
                },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .clickable { revealed = true }
                    .padding(CashuTheme.spacing.section),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Tap to reveal",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .clickable(enabled = revealed) { acknowledged = !acknowledged }
                .padding(vertical = CashuTheme.spacing.snug),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CashuTheme.spacing.snug),
        ) {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = null,
                tint = if (acknowledged) CashuTheme.colors.received else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(VERIFY_CHECK_ICON_SIZE),
            )
            Text(
                text = "I've written down my seed phrase and stored it safely.",
                style = MaterialTheme.typography.bodyMedium,
                color = if (revealed) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }
        PrimaryButton(
            text = "I've saved my seed phrase",
            onClick = onContinue,
            enabled = revealed && acknowledged,
        )
        GhostButton(text = "Back", onClick = onBack, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun FirstMintFace(
    isLoading: Boolean,
    onBack: () -> Unit,
    onFinish: (selectedMintUrls: List<String>) -> Unit,
) {
    var selectedUrls by remember { mutableStateOf<Set<String>>(emptySet()) }
    var customUrls by remember { mutableStateOf<List<String>>(emptyList()) }
    var customUrl by remember { mutableStateOf("") }
    var customError by remember { mutableStateOf<String?>(null) }
    val allRows = remember(customUrls) { RecommendedMints.map { it.url } + customUrls }

    fun addCustomInput() {
        val candidates = mintUrlCandidates(customUrl)
        if (candidates.isEmpty()) {
            customError = "That doesn't look like a mint URL."
            return
        }
        val existing = (RecommendedMints.map { it.url } + customUrls).toSet()
        val fresh = candidates.filterNot { it in existing }
        if (fresh.isEmpty()) {
            customError = "Those mints are already in the list."
            return
        }
        customUrls = customUrls + fresh
        selectedUrls = selectedUrls + fresh
        customUrl = ""
        customError = null
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(CashuTheme.spacing.section)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.default),
    ) {
        Text(
            text = "Add your first mint",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "A mint custodies your ecash. You can change or add more later.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        allRows.forEach { url ->
            val rec = RecommendedMints.firstOrNull { it.url == url }
            MintOptionRow(
                title = rec?.name ?: shortenMintUrl(url),
                subtitle = shortenMintUrl(url),
                selected = url in selectedUrls,
                onSelect = {
                    selectedUrls = if (url in selectedUrls) selectedUrls - url else selectedUrls + url
                },
            )
        }
        CashuTextField(
            value = customUrl,
            onValueChange = { customUrl = it; customError = null },
            label = "Add custom mint URL",
            placeholder = "mint.example.com, another.example.com",
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
        )
        GhostButton(
            text = "Add custom mint",
            onClick = ::addCustomInput,
            enabled = customUrl.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        )
        if (customError != null) {
            InlineNotice(text = customError!!)
        }
        Spacer(Modifier.height(CashuTheme.spacing.snug))
        PrimaryButton(
            text = if (isLoading) "Setting up…" else "Continue",
            onClick = { onFinish(allRows.filter { it in selectedUrls }) },
            enabled = selectedUrls.isNotEmpty() && !isLoading,
            loading = isLoading,
        )
        GhostButton(
            text = "Skip for now",
            onClick = { onFinish(emptyList()) },
            enabled = !isLoading,
            modifier = Modifier.fillMaxWidth(),
        )
        GhostButton(text = "Back", onClick = onBack, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun RestoreMethodFace(
    onBack: () -> Unit,
    onCloudRestore: () -> Unit,
    onSeedPhrase: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(CashuTheme.spacing.section),
        verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.default),
    ) {
        Spacer(Modifier.weight(1f))
        Text(
            text = "Restore wallet",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "Choose how to recover your wallet.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        PrimaryButton(
            text = "Restore from Android backup",
            onClick = onCloudRestore,
        )
        PrimaryButton(
            text = "Use seed phrase",
            onClick = onSeedPhrase,
        )
        GhostButton(text = "Back", onClick = onBack, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun CloudRestoreUnavailableFace(
    onBack: () -> Unit,
    onSeedPhrase: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(CashuTheme.spacing.section),
        verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.default),
    ) {
        Spacer(Modifier.weight(1f))
        Text(
            text = "No Android backup yet",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "Cloud seed backup is not enabled on Android yet. Restore with your seed phrase and add the mints you used.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        PrimaryButton(
            text = "Use seed phrase",
            onClick = onSeedPhrase,
        )
        GhostButton(text = "Back", onClick = onBack, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun RestoreInputFace(
    isLoading: Boolean,
    onBack: () -> Unit,
    onRestore: (String) -> Unit,
    errorMessage: String?,
) {
    var input by remember { mutableStateOf("") }
    val normalized = remember(input) { MnemonicInput.normalize(input) }
    val wordCount = remember(input) {
        input.trim().split(Regex("\\s+")).count { it.isNotBlank() }
    }
    val hasSupportedWordCount = remember(normalized) { MnemonicInput.hasSupportedWordCount(normalized) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(CashuTheme.spacing.section)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.default),
    ) {
        Text(
            text = "Restore from seed",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "Paste or type your recovery phrase.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        CashuTextField(
            value = input,
            onValueChange = { input = it },
            label = "Recovery phrase",
            placeholder = "word1 word2 word3 …",
            minLines = 4,
            modifier = Modifier
                .fillMaxWidth()
                .height(SEED_INPUT_HEIGHT),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
        )
        Text(
            text = "$wordCount words · ${MnemonicInput.supportedWordCountLabel} supported",
            style = MaterialTheme.typography.bodySmall,
            color = if (wordCount == 0 || hasSupportedWordCount) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            },
        )
        if (errorMessage != null) {
            InlineNotice(text = errorMessage)
        }
        Spacer(Modifier.height(CashuTheme.spacing.snug))
        PrimaryButton(
            text = if (isLoading) "Restoring…" else "Restore wallet",
            onClick = { onRestore(input) },
            enabled = hasSupportedWordCount && !isLoading,
            loading = isLoading,
        )
        GhostButton(text = "Back", onClick = onBack, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun RestoreMintsFace(
    walletManager: WalletManager,
    onBack: () -> Unit,
    onRestore: (List<String>) -> Unit,
) {
    var input by remember { mutableStateOf("") }
    var staged by remember { mutableStateOf<List<String>>(emptyList()) }
    var notice by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val previewStates = remember { mutableStateMapOf<String, MintPreviewState>() }

    fun previewMint(url: String) {
        previewStates[url] = MintPreviewState(isLoading = true)
        scope.launch {
            val result = runCatching { walletManager.previewMint(url) }
            previewStates[url] = result.fold(
                onSuccess = { MintPreviewState(mintInfo = it) },
                onFailure = { MintPreviewState(error = it.message ?: "Preview unavailable") },
            )
        }
    }

    fun addInput() {
        val candidates = mintUrlCandidates(input)
        if (candidates.isEmpty()) {
            notice = "Paste one or more HTTPS mint URLs."
            return
        }
        val fresh = candidates.filterNot { it in staged }
        staged = staged + fresh
        fresh.forEach(::previewMint)
        notice = when {
            fresh.isEmpty() -> "Those mints are already staged."
            fresh.size == 1 -> "Added ${shortenMintUrl(fresh.single())}."
            else -> "Added ${fresh.size} mints."
        }
        input = ""
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(CashuTheme.spacing.section)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.default),
    ) {
        Text(
            text = "Restore mints",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "Add the mints you used before. Android will recover spendable and pending ecash from each one.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        CashuTextField(
            value = input,
            onValueChange = { input = it; notice = null },
            label = "Mint URLs",
            placeholder = "mint.one, mint.two/path",
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
        )
        GhostButton(
            text = "Add mints",
            onClick = ::addInput,
            enabled = input.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        )
        if (notice != null) {
            InlineNotice(text = notice!!)
        }
        if (staged.isNotEmpty()) {
            staged.forEachIndexed { index, url ->
                StagedMintRow(
                    url = url,
                    previewState = previewStates[url] ?: MintPreviewState(),
                    index = index,
                    count = staged.size,
                    onMoveUp = {
                        if (index > 0) staged = staged.toMutableList().also { list ->
                            val item = list.removeAt(index)
                            list.add(index - 1, item)
                        }
                    },
                    onMoveDown = {
                        if (index < staged.lastIndex) staged = staged.toMutableList().also { list ->
                            val item = list.removeAt(index)
                            list.add(index + 1, item)
                        }
                    },
                    onRemove = {
                        staged = staged.filterNot { it == url }
                        previewStates.remove(url)
                    },
                )
            }
        }
        Spacer(Modifier.height(CashuTheme.spacing.snug))
        PrimaryButton(
            text = if (staged.isEmpty()) "Restore without mints" else "Restore from ${staged.size} mint${if (staged.size == 1) "" else "s"}",
            onClick = { onRestore(staged) },
        )
        GhostButton(
            text = "Skip mints for now",
            onClick = { onRestore(emptyList()) },
            modifier = Modifier.fillMaxWidth(),
        )
        GhostButton(text = "Back", onClick = onBack, modifier = Modifier.fillMaxWidth())
    }
}

private data class MintPreviewState(
    val mintInfo: MintInfo? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
)

@Composable
private fun StagedMintRow(
    url: String,
    previewState: MintPreviewState,
    index: Int,
    count: Int,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    val fallbackMint = remember(url) { MintInfo(url = url, name = shortenMintUrl(url)) }
    val mintInfo = previewState.mintInfo ?: fallbackMint
    val subtitle = when {
        previewState.isLoading -> "Fetching mint info…"
        previewState.error != null -> "${previewState.error} · ${shortenMintUrl(url)}"
        else -> shortenMintUrl(url)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(CashuTheme.spacing.comfortable),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CashuTheme.spacing.default),
    ) {
        MintAvatar(mint = mintInfo, size = 36)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = mintInfo.name.ifBlank { shortenMintUrl(url) },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(CashuTheme.spacing.tight)) {
                TextButton(onClick = onMoveUp, enabled = index > 0) { Text("Up") }
                TextButton(onClick = onMoveDown, enabled = index < count - 1) { Text("Down") }
                TextButton(onClick = onRemove) { Text("Remove") }
            }
        }
    }
}

private sealed interface RestorePhase {
    data object Pending : RestorePhase
    data object Restoring : RestorePhase
    data class Restored(val result: RestoreMintResult) : RestorePhase
    data object Skipped : RestorePhase
    data class Failed(val message: String) : RestorePhase
}

private sealed interface MintSetupPhase {
    data object Pending : MintSetupPhase
    data object Adding : MintSetupPhase
    data object Added : MintSetupPhase
    data object Skipped : MintSetupPhase
    data class Failed(val message: String) : MintSetupPhase
}

@Composable
private fun FirstMintProgressFace(
    walletManager: WalletManager,
    mnemonic: String,
    mintUrls: List<String>,
    onOpenWallet: () -> Unit,
) {
    val phases = remember(mintUrls) {
        mutableStateMapOf<String, MintSetupPhase>().apply {
            mintUrls.forEach { put(it, MintSetupPhase.Pending) }
        }
    }
    val scope = rememberCoroutineScope()
    var completed by remember { mutableStateOf(false) }
    var topError by remember { mutableStateOf<String?>(null) }

    suspend fun addMint(url: String) {
        phases[url] = MintSetupPhase.Adding
        runCatching { walletManager.addMint(url) }
            .onSuccess { phases[url] = MintSetupPhase.Added }
            .onFailure { phases[url] = MintSetupPhase.Failed(it.message ?: "Could not add this mint.") }
    }

    LaunchedEffect(mnemonic, mintUrls) {
        completed = false
        topError = null
        phases.keys.forEach { phases[it] = MintSetupPhase.Pending }
        runCatching { walletManager.initializeNewWalletForOnboarding(mnemonic) }
            .onFailure {
                topError = it.message ?: "Could not create wallet."
                completed = true
                return@LaunchedEffect
            }
        if (mintUrls.isEmpty()) {
            completed = true
            return@LaunchedEffect
        }
        mintUrls.forEach { url -> addMint(url) }
        completed = true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(CashuTheme.spacing.section)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.default),
    ) {
        Text(
            text = if (completed) "Wallet ready" else "Setting up wallet",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = if (mintUrls.isEmpty()) {
                "Your wallet is ready. Add mints later from the Mints tab."
            } else {
                "Adding ${mintUrls.size} mint${if (mintUrls.size == 1) "" else "s"} to your wallet."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (topError != null) {
            InlineNotice(text = topError!!)
        }
        mintUrls.forEach { url ->
            MintSetupProgressRow(
                url = url,
                phase = phases[url] ?: MintSetupPhase.Pending,
                onRetry = {
                    scope.launch { addMint(url) }
                },
            )
        }
        Spacer(Modifier.height(CashuTheme.spacing.snug))
        PrimaryButton(
            text = if (completed) "Open wallet" else "Setting up…",
            onClick = onOpenWallet,
            enabled = completed && topError == null,
            loading = !completed,
        )
    }
}

@Composable
private fun MintSetupProgressRow(
    url: String,
    phase: MintSetupPhase,
    onRetry: () -> Unit,
) {
    val subtitle = when (phase) {
        MintSetupPhase.Pending -> "Waiting"
        MintSetupPhase.Adding -> "Adding…"
        MintSetupPhase.Added -> "Added"
        MintSetupPhase.Skipped -> "Skipped"
        is MintSetupPhase.Failed -> "${phase.message} Tap to retry."
    }
    MintOptionRow(
        title = shortenMintUrl(url),
        subtitle = subtitle,
        selected = phase is MintSetupPhase.Added,
        onSelect = {
            if (phase is MintSetupPhase.Failed) onRetry()
        },
    )
}

@Composable
private fun RestoreProgressFace(
    walletManager: WalletManager,
    mnemonic: String,
    mintUrls: List<String>,
    onOpenWallet: () -> Unit,
) {
    val phases = remember(mintUrls) {
        mutableStateMapOf<String, RestorePhase>().apply {
            mintUrls.forEach { put(it, RestorePhase.Pending) }
        }
    }
    val scope = rememberCoroutineScope()
    var completed by remember { mutableStateOf(false) }
    var topError by remember { mutableStateOf<String?>(null) }

    suspend fun restoreMint(url: String) {
        phases[url] = RestorePhase.Restoring
        runCatching { walletManager.restoreFromMint(url) }
            .onSuccess { phases[url] = RestorePhase.Restored(it) }
            .onFailure { phases[url] = RestorePhase.Failed(it.message ?: "Could not restore from this mint.") }
    }

    LaunchedEffect(mnemonic, mintUrls) {
        completed = false
        topError = null
        phases.keys.forEach { phases[it] = RestorePhase.Pending }
        runCatching { walletManager.initializeRestoredWallet(mnemonic) }
            .onFailure {
                topError = it.message ?: "Could not restore wallet."
                completed = true
                return@LaunchedEffect
            }
        if (mintUrls.isEmpty()) {
            completed = true
            return@LaunchedEffect
        }
        mintUrls.forEach { url ->
            restoreMint(url)
        }
        completed = true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(CashuTheme.spacing.section)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.default),
    ) {
        Text(
            text = if (completed) "Wallet restored" else "Restoring wallet",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = if (mintUrls.isEmpty()) {
                "Your seed phrase has been restored. Add mints later to recover ecash."
            } else {
                "Recovering ecash from ${mintUrls.size} mint${if (mintUrls.size == 1) "" else "s"}."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (topError != null) {
            InlineNotice(text = topError!!)
        }
        mintUrls.forEach { url ->
            RestoreProgressRow(
                url = url,
                phase = phases[url] ?: RestorePhase.Pending,
                onRetry = {
                    scope.launch { restoreMint(url) }
                },
            )
        }
        Spacer(Modifier.height(CashuTheme.spacing.snug))
        PrimaryButton(
            text = if (completed) "Open wallet" else "Restoring…",
            onClick = onOpenWallet,
            enabled = completed && topError == null,
            loading = !completed,
        )
    }
}

@Composable
private fun RestoreProgressRow(
    url: String,
    phase: RestorePhase,
    onRetry: () -> Unit,
) {
    val title = shortenMintUrl(url)
    val subtitle = when (phase) {
        RestorePhase.Pending -> "Waiting"
        RestorePhase.Restoring -> "Restoring…"
        is RestorePhase.Restored -> {
            val recovered = phase.result.totalRecovered
            if (recovered > 0) "Recovered $recovered sat" else "No spendable ecash found"
        }
        RestorePhase.Skipped -> "Skipped"
        is RestorePhase.Failed -> "${phase.message} Tap to retry."
    }
    MintOptionRow(
        title = title,
        subtitle = subtitle,
        selected = phase is RestorePhase.Restored,
        onSelect = {
            if (phase is RestorePhase.Failed) onRetry()
        },
    )
}

@Composable
private fun SeedGrid(words: List<String>) {
    val mono = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(CashuTheme.spacing.comfortable),
        verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.snug),
    ) {
        words.chunked(2).forEachIndexed { rowIndex, pair ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(CashuTheme.spacing.default),
            ) {
                pair.forEachIndexed { columnIndex, word ->
                    val index = rowIndex * 2 + columnIndex + 1
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(CashuTheme.spacing.snug),
                    ) {
                        Text(
                            text = "%2d".format(index),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(text = word, style = mono)
                    }
                }
            }
        }
    }
}

@Composable
private fun MintOptionRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClick = onSelect)
            .padding(CashuTheme.spacing.comfortable),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CashuTheme.spacing.default),
    ) {
        Box(
            modifier = Modifier
                .size(CashuTheme.spacing.loose)
                .clip(CircleShape)
                .background(
                    if (selected) CashuTheme.colors.received
                    else MaterialTheme.colorScheme.surfaceContainerHighest,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Icon(
                    imageVector = Icons.Outlined.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.size(RADIO_CHECK_ICON_SIZE),
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EcashInfoDialog(onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("What is ecash?") },
        text = {
            Text(
                "Ecash is bearer money: you hold the value as tokens on your device, not as an account at a service. A mint issues and redeems tokens; you can send them to anyone with a copy/paste, QR code, or Lightning.",
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Got it") }
        },
    )
}

// Component-local sizes that sit below the spacing scale on purpose.
private val VERIFY_CHECK_ICON_SIZE = 18.dp
private val SEED_INPUT_HEIGHT = 160.dp
private val RADIO_CHECK_ICON_SIZE = 14.dp

// Steps crossfade — a horizontal push between onboarding steps was rejected as
// jarring (2026-06-26 iOS decision, binding product behavior).
private fun <S> AnimatedContentTransitionScope<S>.stepTransition(
    @Suppress("UNUSED_PARAMETER") direction: Int,
): androidx.compose.animation.ContentTransform =
    fadeIn(tween(250)).togetherWith(fadeOut(tween(250)))
