package com.cashu.me.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowCircleRight
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.cashu.me.Core.Bip39WordList
import com.cashu.me.Core.MnemonicInput
import com.cashu.me.Core.NostrMintBackupService
import com.cashu.me.Core.WalletManager
import com.cashu.me.Core.mintUrlCandidates
import com.cashu.me.Models.RestoreMintResult
import com.cashu.me.Models.MintInfo
import com.cashu.me.ui.components.CanvasDivider
import com.cashu.me.ui.components.CashuTextField
import com.cashu.me.ui.components.GhostButton
import com.cashu.me.ui.components.IconSwap
import com.cashu.me.ui.components.InlineNotice
import com.cashu.me.ui.components.MintAvatar
import com.cashu.me.ui.components.PrimaryButton
import com.cashu.me.ui.components.SecondaryButton
import com.cashu.me.ui.theme.CashuTheme
import com.cashu.me.ui.theme.rememberReducedMotion

// ---------------------------------------------------------------------------
// iOS OnboardingView parity. Source of truth: ios/CashuWallet/Views/Main/
// OnboardingView.swift — welcome → showMnemonic (redacted seed, tap-to-reveal,
// acknowledge checkbox) → firstMint (multi-select recommended mints), plus the
// seed-restore branch. Step changes are quiet 250ms crossfades; blocks
// materialize with a 12dp rise staggered 70ms per index.
// ---------------------------------------------------------------------------

private data class RecommendedMint(val name: String, val url: String, val iconUrl: String)

// Mirrors iOS RecommendedMint.suggested (ActivityOrbView.swift).
private val RecommendedMints = listOf(
    RecommendedMint("Minibits", "https://mint.minibits.cash/Bitcoin", "https://minibits.cash/icon-192.png"),
    RecommendedMint("Coinos", "https://mint.coinos.io", "https://coinos.io/images/icon.png"),
    RecommendedMint("Macadamia", "https://mint.macadamia.cash", "https://cypherbase.cc/images/logo_w256.png"),
)

private sealed interface OnboardingStep {
    data object Welcome : OnboardingStep
    data class ShowMnemonic(val mnemonic: String) : OnboardingStep
    data class FirstMint(val mnemonic: String) : OnboardingStep
    data object RestoreMethod : OnboardingStep
    data object RestoreInput : OnboardingStep
    data class RestoreMints(val mnemonic: String) : OnboardingStep
    data class RestoreProgress(val mnemonic: String, val mintUrls: List<String>) : OnboardingStep
}

// iOS metrics: headers 28pt, CTA stacks 24pt, grid gutters 12/14pt.
private val HeaderPadding = 28.dp
private val CtaPadding = 24.dp
private val BottomPadding = 24.dp
private val SeedGridColumnGap = 12.dp
private val SeedGridRowGap = 14.dp
private val SeedIndexWidth = 22.dp
private val SeedBlurRadius = 9.dp
private val AckIconSize = 22.dp
private val SelectIconSize = 24.dp
private val MintAvatarSize = 36.dp
private val WarningIconSize = 16.dp
private val RevealEyeSize = 22.dp

/** iOS `.largeTitle.weight(.heavy)` + `.tracking(-0.5)` — the step-title voice. */
@Composable
private fun onboardingTitleStyle(): TextStyle =
    MaterialTheme.typography.displaySmall.copy(
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = (-0.5).sp,
        lineHeight = 40.sp,
    )

/**
 * iOS entrance stagger: content blocks rise 12pt into place, 0.4s, 70ms per
 * index. (The step crossfade owns opacity; blur-in is skipped — `Modifier.blur`
 * is API 31+ and the rise carries the effect alone.)
 */
@Composable
private fun Modifier.riseIn(appeared: Boolean, index: Int): Modifier {
    if (rememberReducedMotion()) return this
    val rise by animateDpAsState(
        targetValue = if (appeared) 0.dp else 12.dp,
        animationSpec = tween(durationMillis = 400, delayMillis = index * 70, easing = FastOutSlowInEasing),
        label = "onboarding-rise-$index",
    )
    return this.offset(y = rise)
}

@Composable
private fun rememberAppeared(): Boolean {
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    return appeared
}

@Composable
fun OnboardingScreen(
    walletManager: WalletManager,
    nostrMintBackupService: NostrMintBackupService,
) {
    val scope = rememberCoroutineScope()

    var step: OnboardingStep by remember { mutableStateOf(OnboardingStep.Welcome) }
    var infoOpen by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var createError by remember { mutableStateOf<String?>(null) }
    var restoring by remember { mutableStateOf(false) }
    var restoreError by remember { mutableStateOf<String?>(null) }
    // First-mint completion state (wallet installs once, then mints add sequentially).
    var walletInstalled by remember { mutableStateOf(false) }
    var finishing by remember { mutableStateOf(false) }
    var addingMintUrl by remember { mutableStateOf<String?>(null) }
    var firstMintError by remember { mutableStateOf<String?>(null) }

    fun finishCreate(mnemonic: String, mintUrls: List<String>) {
        scope.launch {
            finishing = true
            firstMintError = null
            var current: String? = null
            try {
                if (!walletInstalled) {
                    walletManager.initializeNewWalletForOnboarding(mnemonic)
                    walletInstalled = true
                }
                for (url in mintUrls) {
                    current = url
                    addingMintUrl = url
                    walletManager.addMint(url)
                }
                addingMintUrl = null
                walletManager.completeOnboarding()
            } catch (t: Throwable) {
                firstMintError = current?.let {
                    "Couldn't connect to ${shortenMintUrl(it)}. Check the URL or try another mint."
                } ?: (t.message ?: "Couldn't set up the wallet.")
                addingMintUrl = null
            } finally {
                finishing = false
            }
        }
    }

    AnimatedContent(
        targetState = step,
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
        // Quiet crossfade — a horizontal push between steps was rejected as
        // jarring (2026-06-26 iOS decision, binding product behavior).
        transitionSpec = { fadeIn(tween(250)).togetherWith(fadeOut(tween(250))) },
        label = "onboarding-step",
    ) { current ->
        when (current) {
            OnboardingStep.Welcome -> WelcomeFace(
                creating = creating,
                errorText = createError,
                onCreate = {
                    scope.launch {
                        creating = true
                        createError = null
                        try {
                            val mnemonic = walletManager.generateMnemonicForOnboarding()
                            step = OnboardingStep.ShowMnemonic(mnemonic)
                        } catch (t: Throwable) {
                            createError = t.message ?: "Couldn't create a wallet."
                        } finally {
                            creating = false
                        }
                    }
                },
                onRestore = {
                    restoreError = null
                    step = OnboardingStep.RestoreMethod
                },
                onInfo = { infoOpen = true },
            )

            is OnboardingStep.ShowMnemonic -> ShowMnemonicFace(
                mnemonic = current.mnemonic,
                onSaved = { step = OnboardingStep.FirstMint(current.mnemonic) },
            )

            is OnboardingStep.FirstMint -> FirstMintFace(
                busy = finishing,
                addingMintUrl = addingMintUrl,
                errorText = firstMintError,
                onContinue = { urls -> finishCreate(current.mnemonic, urls) },
                onSkip = { finishCreate(current.mnemonic, emptyList()) },
            )

            OnboardingStep.RestoreMethod -> RestoreMethodFace(
                onBack = { step = OnboardingStep.Welcome },
                onSeedPhrase = {
                    restoreError = null
                    step = OnboardingStep.RestoreInput
                },
            )

            OnboardingStep.RestoreInput -> RestoreInputFace(
                restoring = restoring,
                errorText = restoreError,
                onClearError = { restoreError = null },
                onBack = { step = OnboardingStep.RestoreMethod },
                onRestore = { mnemonic ->
                    // iOS initializeAndProceed: install the restored wallet before
                    // the mint-staging step so the repository is keyed to this
                    // seed — the Nostr backup search derives its keys from it.
                    scope.launch {
                        restoring = true
                        restoreError = null
                        val normalized = MnemonicInput.normalize(mnemonic)
                        runCatching { walletManager.initializeRestoredWallet(normalized) }
                            .onSuccess { step = OnboardingStep.RestoreMints(normalized) }
                            .onFailure {
                                restoreError = it.message
                                    ?: "That seed phrase doesn't look right. Check the spelling and try again."
                            }
                        restoring = false
                    }
                },
            )

            is OnboardingStep.RestoreMints -> RestoreMintsFace(
                nostrMintBackupService = nostrMintBackupService,
                onBack = { step = OnboardingStep.RestoreInput },
                onRestore = { mintUrls ->
                    step = OnboardingStep.RestoreProgress(current.mnemonic, mintUrls)
                },
            )

            is OnboardingStep.RestoreProgress -> RestoreProgressFace(
                walletManager = walletManager,
                mintUrls = current.mintUrls,
                onBack = { step = OnboardingStep.RestoreMints(current.mnemonic) },
                onOpenWallet = {
                    scope.launch {
                        runCatching { walletManager.completeRestore() }
                    }
                },
            )
        }
    }

    if (infoOpen) {
        EcashConceptSheet(onDismiss = { infoOpen = false })
    }
}

// ---------------------------------------------------------------------------
// Welcome
// ---------------------------------------------------------------------------

@Composable
private fun WelcomeFace(
    creating: Boolean,
    errorText: String?,
    onCreate: () -> Unit,
    onRestore: () -> Unit,
    onInfo: () -> Unit,
) {
    val appeared = rememberAppeared()
    Column(modifier = Modifier.fillMaxSize()) {
        Spacer(Modifier.weight(1f))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = HeaderPadding)
                .riseIn(appeared, 0),
            verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.default),
        ) {
            Text(
                text = "Private cash.\nIn your pocket.",
                style = onboardingTitleStyle(),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "An ecash wallet for Bitcoin and Lightning.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.weight(1f))
        if (errorText != null) {
            InlineNotice(
                text = errorText,
                modifier = Modifier.padding(horizontal = CtaPadding),
            )
            Spacer(Modifier.height(CashuTheme.spacing.snug))
        }
        Column(
            modifier = Modifier
                .padding(horizontal = CtaPadding)
                .padding(bottom = BottomPadding)
                .riseIn(appeared, 1),
            verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.snug),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PrimaryButton(
                text = "Create Wallet",
                onClick = onCreate,
                loading = creating,
                colors = ButtonDefaults.filledTonalButtonColors(),
            )
            SecondaryButton(
                text = "Restore Wallet",
                onClick = onRestore,
                enabled = !creating,
            )
            GhostButton(
                text = "What is ecash?",
                onClick = onInfo,
            )
        }
    }
}

/** iOS concept sheet: heavy title + three bearer-cash beats + Got it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EcashConceptSheet(onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = HeaderPadding)
                .padding(bottom = CashuTheme.spacing.comfortable)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.comfortable),
        ) {
            Text(
                text = "Ecash is bearer cash\nfor Bitcoin.",
                style = MaterialTheme.typography.headlineMedium.copy(
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.3).sp,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Column(verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.default)) {
                Text(
                    text = "Whoever holds it, owns it. Your balance stays on this device, hidden from everyone else.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Mints hold the Bitcoin behind your ecash. You can use several at once.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "Send instantly. Cash out to Lightning anytime.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(CashuTheme.spacing.snug))
            PrimaryButton(text = "Got it", onClick = onDismiss)
        }
    }
}

// ---------------------------------------------------------------------------
// Seed phrase (showMnemonic)
// ---------------------------------------------------------------------------

@Composable
private fun ShowMnemonicFace(
    mnemonic: String,
    onSaved: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val appeared = rememberAppeared()
    val words = remember(mnemonic) { mnemonic.trim().split(' ').filter { it.isNotBlank() } }

    var revealed by remember { mutableStateOf(false) }
    var acknowledged by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }

    fun reveal() {
        if (revealed) return // one-way, like iOS
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        revealed = true
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Spacer(Modifier.weight(1f))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = HeaderPadding)
                .riseIn(appeared, 0),
            verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.default),
        ) {
            Text(
                text = "Your Seed\nPhrase.",
                style = onboardingTitleStyle(),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Write these 12 words down in order. This is the only way to recover your wallet.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(CashuTheme.spacing.tight),
            ) {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = CashuTheme.colors.pending,
                    modifier = Modifier.size(WarningIconSize),
                )
                Text(
                    text = "Never share these words with anyone",
                    style = MaterialTheme.typography.labelMedium,
                    color = CashuTheme.colors.pending,
                )
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = CashuTheme.spacing.section)
                .riseIn(appeared, 1),
            verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.snug),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = HeaderPadding)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        enabled = !revealed,
                        onClickLabel = "Reveal seed phrase",
                        onClick = ::reveal,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                SeedGrid(words = words, revealed = revealed)
                if (!revealed) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.micro),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Visibility,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(RevealEyeSize),
                        )
                        Text(
                            text = "Tap to reveal",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            GhostButton(
                text = if (copied) "Copied" else "Copy",
                onClick = {
                    clipboard.setText(AnnotatedString(words.joinToString(" ")))
                    copied = true
                    scope.launch {
                        delay(3_000)
                        copied = false
                    }
                },
            )
        }
        Spacer(Modifier.weight(1f))
        Column(
            modifier = Modifier
                .padding(horizontal = CtaPadding)
                .padding(bottom = BottomPadding)
                .riseIn(appeared, 2),
            verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.default),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        acknowledged = !acknowledged
                    }
                    .padding(horizontal = CashuTheme.spacing.micro),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(CashuTheme.spacing.snug),
            ) {
                // Circle ↔ check morphs (iOS .contentTransition(.symbolEffect(.replace))).
                IconSwap(
                    icon = if (acknowledged) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                    contentDescription = if (acknowledged) "Acknowledged" else "Not acknowledged",
                    tint = if (acknowledged) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(AckIconSize),
                )
                Text(
                    text = "I've written down my seed phrase and stored it safely.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            PrimaryButton(
                text = "I've Saved My Seed Phrase",
                onClick = onSaved,
                enabled = acknowledged,
            )
        }
    }
}

/**
 * 3-column × 4-row seed grid, plain on the canvas (no card chrome) — iOS
 * mnemonicWordsGrid. Zero-padded indices in a fixed trailing-aligned column,
 * monospaced medium words.
 *
 * While hidden the real words are never composed (iOS `.redacted` rationale:
 * an animatable blur alone can flicker the phrase legible on entrance, and
 * `Modifier.blur` is a no-op below API 31). Placeholder dots stand in, with
 * the blur layered on top where supported.
 */
@Composable
private fun SeedGrid(words: List<String>, revealed: Boolean) {
    val indexStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
    val wordStyle = MaterialTheme.typography.bodyMedium.copy(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (revealed) Modifier else Modifier.blur(SeedBlurRadius)),
        verticalArrangement = Arrangement.spacedBy(SeedGridRowGap),
    ) {
        words.chunked(3).forEachIndexed { rowIndex, rowWords ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(SeedGridColumnGap),
            ) {
                rowWords.forEachIndexed { columnIndex, word ->
                    val number = rowIndex * 3 + columnIndex + 1
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(CashuTheme.spacing.micro),
                    ) {
                        Text(
                            text = "%02d".format(number),
                            style = indexStyle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                            textAlign = TextAlign.End,
                            modifier = Modifier.width(SeedIndexWidth),
                        )
                        Text(
                            text = if (revealed) word else "••••••",
                            style = wordStyle,
                            color = if (revealed) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// First mint (multi-select recommended mints)
// ---------------------------------------------------------------------------

@Composable
private fun FirstMintFace(
    busy: Boolean,
    addingMintUrl: String?,
    errorText: String?,
    onContinue: (List<String>) -> Unit,
    onSkip: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val appeared = rememberAppeared()

    var selected by remember { mutableStateOf(setOf<String>()) }
    var customUrls by remember { mutableStateOf(listOf<String>()) }
    var customInputOpen by remember { mutableStateOf(false) }
    var customInput by remember { mutableStateOf("") }
    var localError by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current

    fun toggle(url: String) {
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        selected = if (url in selected) selected - url else selected + url
    }

    fun commitCustomUrl() {
        val normalized = normalizeMintUrl(customInput) ?: return
        val existing = RecommendedMints.map { it.url } + customUrls
        if (existing.any { it.equals(normalized, ignoreCase = true) }) {
            localError = "That mint is already in the list."
            return
        }
        localError = null
        customUrls = customUrls + normalized
        selected = selected + normalized
        customInput = ""
        customInputOpen = false
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = HeaderPadding)
                .padding(top = CashuTheme.spacing.snug)
                .riseIn(appeared, 0),
            verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.snug),
        ) {
            Text(
                text = "Pick your\nfirst mint.",
                style = onboardingTitleStyle(),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Mints issue your ecash and redeem it for Bitcoin. Add more anytime in Settings.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = HeaderPadding)
                .padding(top = CashuTheme.spacing.snug),
        ) {
            val rows = RecommendedMints.map { Triple(it.name, it.url, it.iconUrl) } +
                customUrls.map { Triple(shortenMintUrl(it), it, null) }
            rows.forEachIndexed { index, (name, url, iconUrl) ->
                if (index > 0) CanvasDivider()
                MintSelectRow(
                    name = name,
                    url = url,
                    iconUrl = iconUrl,
                    selected = url in selected,
                    enabled = !busy,
                    onToggle = { toggle(url) },
                )
            }
            if (!customInputOpen) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !busy) { customInputOpen = true }
                        .padding(vertical = CashuTheme.spacing.default),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(CashuTheme.spacing.micro),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(WarningIconSize),
                    )
                    Text(
                        text = "Add custom mint URL",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Spacer(Modifier.height(CashuTheme.spacing.snug))
                CashuTextField(
                    value = customInput,
                    onValueChange = {
                        customInput = it
                        localError = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "https://mint.example.com",
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    singleLine = true,
                    isError = localError != null,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        keyboardType = KeyboardType.Uri,
                    ),
                    trailingIcon = {
                        if (customInput.isBlank()) {
                            IconButton(onClick = {
                                clipboard.getText()?.text?.let { customInput = it.trim() }
                            }) {
                                Icon(Icons.Outlined.ContentPaste, contentDescription = "Paste")
                            }
                        } else {
                            IconButton(onClick = ::commitCustomUrl) {
                                Icon(Icons.Outlined.ArrowCircleRight, contentDescription = "Add mint")
                            }
                        }
                    },
                )
            }
            val notice = localError ?: errorText
            if (notice != null) {
                Spacer(Modifier.height(CashuTheme.spacing.snug))
                InlineNotice(text = notice)
            }
            if (addingMintUrl != null) {
                Spacer(Modifier.height(CashuTheme.spacing.snug))
                Text(
                    text = "Connecting to ${shortenMintUrl(addingMintUrl)}…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(
            modifier = Modifier
                .padding(horizontal = CtaPadding)
                .padding(bottom = BottomPadding)
                .riseIn(appeared, 1),
            verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.tight),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PrimaryButton(
                text = "Continue",
                onClick = {
                    // Preserve display order: recommended first, then customs.
                    val ordered = (RecommendedMints.map { it.url } + customUrls).filter { it in selected }
                    onContinue(ordered)
                },
                enabled = selected.isNotEmpty() && !busy,
                loading = busy,
            )
            GhostButton(
                text = "Skip for now",
                onClick = onSkip,
                enabled = !busy,
            )
        }
    }
}

/** iOS mint row: avatar + name/URL + trailing multi-select check circle. */
@Composable
private fun MintSelectRow(
    name: String,
    url: String,
    iconUrl: String?,
    selected: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onToggle)
            .padding(vertical = CashuTheme.spacing.default),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CashuTheme.spacing.snug),
    ) {
        RecommendedMintAvatar(name = name, url = url, iconUrl = iconUrl)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = shortenMintUrl(url),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
        }
        // Selection glyph morphs instead of hard-cutting (symbol-replace parity).
        IconSwap(
            icon = if (selected) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
            contentDescription = if (selected) "Selected" else "Not selected",
            tint = if (selected) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f)
            },
            modifier = Modifier.size(SelectIconSize),
        )
    }
}

/** 36dp circular avatar with curated icon; monogram fallback (iOS MintAvatarView). */
@Composable
private fun RecommendedMintAvatar(name: String, url: String, iconUrl: String?, size: Dp = MintAvatarSize) {
    MintAvatar(
        mint = MintInfo(url = url, name = name, iconUrl = iconUrl),
        size = size,
    )
}

// ---------------------------------------------------------------------------
// Restore (seed input)
// ---------------------------------------------------------------------------

@Composable
private fun RestoreMethodFace(
    onBack: () -> Unit,
    onSeedPhrase: () -> Unit,
) {
    val appeared = rememberAppeared()
    Column(modifier = Modifier.fillMaxSize()) {
        Spacer(Modifier.weight(1f))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = HeaderPadding)
                .riseIn(appeared, 0),
            verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.default),
        ) {
            Text(
                text = "Restore Wallet.",
                style = onboardingTitleStyle(),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Use your seed phrase, then choose the mints you used before.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.weight(1f))
        Column(
            modifier = Modifier
                .padding(horizontal = CtaPadding)
                .padding(bottom = BottomPadding)
                .riseIn(appeared, 1),
            verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.tight),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SecondaryButton(
                text = "Seed Phrase",
                onClick = onSeedPhrase,
            )
            GhostButton(text = "Back", onClick = onBack)
        }
    }
}

@Composable
private fun RestoreInputFace(
    restoring: Boolean,
    errorText: String?,
    onClearError: () -> Unit,
    onBack: () -> Unit,
    onRestore: (String) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val appeared = rememberAppeared()
    var input by remember { mutableStateOf("") }
    val wordCount = remember(input) {
        input.trim().split(Regex("\\s+")).count { it.isNotBlank() }
    }
    // iOS invalidMnemonicWords: live per-word check against the BIP-39 list.
    val invalidCount = remember(input) { Bip39WordList.invalidWordIndices(input).size }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = HeaderPadding)
                .padding(top = CashuTheme.spacing.snug)
                .riseIn(appeared, 0),
            verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.snug),
        ) {
            Text(
                text = "Restore Wallet.",
                style = onboardingTitleStyle(),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Enter your 12 words in order.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = HeaderPadding)
                .padding(top = CashuTheme.spacing.section),
            verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.snug),
        ) {
            Box(modifier = Modifier.fillMaxWidth()) {
                CashuTextField(
                    value = input,
                    onValueChange = {
                        input = it
                        onClearError()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = "word1 word2 word3 …",
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    minLines = 5,
                    maxLines = 8,
                    // Red only once the phrase is "complete" but wrong (or a
                    // restore attempt failed) — not while a word is half-typed.
                    isError = errorText != null || (wordCount >= 12 && invalidCount > 0),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                )
                // Corner affordance (iOS bottomTrailing): paste when empty, clear when full.
                IconButton(
                    onClick = {
                        if (input.isBlank()) {
                            clipboard.getText()?.text?.let { input = it.trim() }
                        } else {
                            input = ""
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(CashuTheme.spacing.micro),
                ) {
                    IconSwap(
                        icon = if (input.isBlank()) Icons.Outlined.ContentPaste else Icons.Filled.Cancel,
                        contentDescription = if (input.isBlank()) "Paste" else "Clear",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // iOS word counter: green only once 12 words all pass the BIP-39
            // list; the invalid tally stays quiet secondary text.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(CashuTheme.spacing.micro),
            ) {
                Text(
                    text = "$wordCount / 12 words",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (wordCount == 12 && invalidCount == 0) {
                        CashuTheme.colors.received
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                if (invalidCount > 0) {
                    Text(
                        text = "· $invalidCount invalid",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (errorText != null) {
                InlineNotice(text = errorText)
            }
        }
        Spacer(Modifier.weight(1f))
        Column(
            modifier = Modifier
                .padding(horizontal = CtaPadding)
                .padding(bottom = BottomPadding)
                .riseIn(appeared, 1),
            verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.tight),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PrimaryButton(
                text = "Restore Wallet",
                onClick = { onRestore(input) },
                enabled = wordCount == 12 && !restoring,
                loading = restoring,
            )
            GhostButton(text = "Back", onClick = onBack, enabled = !restoring)
        }
    }
}

@Composable
private fun RestoreMintsFace(
    nostrMintBackupService: NostrMintBackupService,
    onBack: () -> Unit,
    onRestore: (List<String>) -> Unit,
) {
    val appeared = rememberAppeared()
    val scope = rememberCoroutineScope()
    val backupState by nostrMintBackupService.state.collectAsState()
    var input by remember { mutableStateOf("") }
    var staged by remember { mutableStateOf<List<String>>(emptyList()) }
    var notice by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current

    // iOS searchNostrMintBackups: look up the encrypted mint-list backup for
    // this seed on the user's relays (NUT-27, fetched by cdk) and stage every
    // mint it contains.
    fun searchNostrBackup() {
        scope.launch {
            runCatching { nostrMintBackupService.fetchBackedUpMintUrls() }
                .onSuccess { urls ->
                    val normalized = urls.mapNotNull(::normalizeMintUrl)
                    val fresh = normalized.filterNot { candidate ->
                        staged.any { it.equals(candidate, ignoreCase = true) }
                    }
                    staged = staged + fresh
                    notice = when {
                        normalized.isEmpty() -> "No Nostr mint backup found on your relays."
                        fresh.isEmpty() -> "Backup found — its mints are already in the list."
                        else -> "Added ${fresh.size} mint${if (fresh.size == 1) "" else "s"} from your Nostr backup."
                    }
                }
                .onFailure { notice = it.message ?: "Could not search your relays." }
        }
    }

    fun addInput() {
        val candidates = mintUrlCandidates(input)
        if (candidates.isEmpty()) {
            notice = "Paste one or more HTTPS mint URLs."
            return
        }
        val fresh = candidates.filterNot { candidate ->
            staged.any { it.equals(candidate, ignoreCase = true) }
        }
        if (fresh.isEmpty()) {
            notice = "Those mints are already staged."
            input = ""
            return
        }
        staged = staged + fresh
        notice = when (fresh.size) {
            1 -> "Added ${shortenMintUrl(fresh.single())}."
            else -> "Added ${fresh.size} mints."
        }
        input = ""
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = HeaderPadding)
                .padding(top = CashuTheme.spacing.snug)
                .riseIn(appeared, 0),
            verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.snug),
        ) {
            Text(
                text = "Restore\nMints.",
                style = onboardingTitleStyle(),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "Add the mints you used before. Each mint will be restored separately.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = HeaderPadding)
                .padding(top = CashuTheme.spacing.section),
            verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.snug),
        ) {
            CashuTextField(
                value = input,
                onValueChange = {
                    input = it
                    notice = null
                },
                modifier = Modifier.fillMaxWidth(),
                placeholder = "mint.one, mint.two/path",
                textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                minLines = 3,
                maxLines = 5,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    keyboardType = KeyboardType.Uri,
                ),
                trailingIcon = {
                    if (input.isBlank()) {
                        IconButton(onClick = {
                            clipboard.getText()?.text?.let { input = it.trim() }
                        }) {
                            Icon(Icons.Outlined.ContentPaste, contentDescription = "Paste")
                        }
                    } else {
                        IconButton(onClick = ::addInput) {
                            Icon(Icons.Outlined.ArrowCircleRight, contentDescription = "Add mints")
                        }
                    }
                },
            )
            GhostButton(
                text = "Add Mints",
                onClick = ::addInput,
                enabled = input.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            )
            GhostButton(
                text = if (backupState.isSearching) "Searching…" else "Find Mints from Nostr Backup",
                onClick = ::searchNostrBackup,
                enabled = !backupState.isSearching,
                modifier = Modifier.fillMaxWidth(),
            )
            if (notice != null) {
                InlineNotice(text = notice!!)
            }
            staged.forEach { url ->
                StagedRestoreMintRow(
                    url = url,
                    onRemove = {
                        staged = staged.filterNot { it == url }
                    },
                )
            }
        }
        Column(
            modifier = Modifier
                .padding(horizontal = CtaPadding)
                .padding(bottom = BottomPadding)
                .riseIn(appeared, 1),
            verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.tight),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PrimaryButton(
                text = if (staged.isEmpty()) {
                    "Restore Without Mints"
                } else {
                    "Restore From ${staged.size} Mint${if (staged.size == 1) "" else "s"}"
                },
                onClick = { onRestore(staged) },
            )
            GhostButton(
                text = "Skip mints for now",
                onClick = { onRestore(emptyList()) },
            )
            GhostButton(text = "Back", onClick = onBack)
        }
    }
}

@Composable
private fun StagedRestoreMintRow(
    url: String,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = CashuTheme.spacing.snug),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CashuTheme.spacing.snug),
    ) {
        RecommendedMintAvatar(name = shortenMintUrl(url), url = url, iconUrl = null)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = shortenMintUrl(url),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
            Text(
                text = "Ready to restore",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Filled.Cancel, contentDescription = "Remove mint")
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

@Composable
private fun RestoreProgressFace(
    walletManager: WalletManager,
    mintUrls: List<String>,
    onBack: () -> Unit,
    onOpenWallet: () -> Unit,
) {
    val phases = remember(mintUrls) {
        mutableStateMapOf<String, RestorePhase>().apply {
            mintUrls.forEach { put(it, RestorePhase.Pending) }
        }
    }
    val scope = rememberCoroutineScope()
    var completed by remember { mutableStateOf(false) }

    suspend fun restoreMint(url: String) {
        phases[url] = RestorePhase.Restoring
        runCatching { walletManager.restoreFromMint(url) }
            .onSuccess { phases[url] = RestorePhase.Restored(it) }
            .onFailure { phases[url] = RestorePhase.Failed(it.message ?: "Could not restore from this mint.") }
    }

    // The restored wallet was already installed on the seed-entry step (iOS
    // initializeAndProceed) — this face only runs the per-mint recovery.
    LaunchedEffect(mintUrls) {
        completed = false
        phases.keys.forEach { phases[it] = RestorePhase.Pending }
        mintUrls.forEach { url -> restoreMint(url) }
        completed = true
    }

    val isWorking = phases.values.any { it is RestorePhase.Pending || it is RestorePhase.Restoring }

    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = HeaderPadding)
                .padding(top = CashuTheme.spacing.snug),
            verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.snug),
        ) {
            Text(
                text = if (completed) "Wallet\nRestored." else "Restoring\nWallet.",
                style = onboardingTitleStyle(),
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = if (mintUrls.isEmpty()) {
                    "Your seed phrase is restored. Add mints later to recover ecash."
                } else {
                    "Recovering ecash from ${mintUrls.size} mint${if (mintUrls.size == 1) "" else "s"}."
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = HeaderPadding)
                .padding(top = CashuTheme.spacing.section),
            verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.snug),
        ) {
            mintUrls.forEach { url ->
                RestoreProgressRow(
                    url = url,
                    phase = phases[url] ?: RestorePhase.Pending,
                    onRetry = {
                        scope.launch { restoreMint(url) }
                    },
                    onSkip = {
                        phases[url] = RestorePhase.Skipped
                    },
                )
            }
            if (mintUrls.isEmpty()) {
                Text(
                    text = "No mints selected.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(
            modifier = Modifier
                .padding(horizontal = CtaPadding)
                .padding(bottom = BottomPadding),
            verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.tight),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PrimaryButton(
                text = if (completed) "Open Wallet" else "Restoring…",
                onClick = onOpenWallet,
                enabled = completed && !isWorking,
                loading = !completed || isWorking,
            )
            GhostButton(
                text = "Back",
                onClick = onBack,
                enabled = completed && !isWorking,
            )
        }
    }
}

@Composable
private fun RestoreProgressRow(
    url: String,
    phase: RestorePhase,
    onRetry: () -> Unit,
    onSkip: () -> Unit,
) {
    val (icon, tint, subtitle) = when (phase) {
        RestorePhase.Pending -> Triple(Icons.Outlined.Circle, MaterialTheme.colorScheme.onSurfaceVariant, "Waiting")
        RestorePhase.Restoring -> Triple(Icons.Outlined.Circle, MaterialTheme.colorScheme.onSurfaceVariant, "Restoring…")
        is RestorePhase.Restored -> {
            val recovered = phase.result.totalRecovered
            Triple(
                Icons.Filled.CheckCircle,
                CashuTheme.colors.received,
                if (recovered > 0) "Recovered $recovered sat" else "No spendable ecash found",
            )
        }
        RestorePhase.Skipped -> Triple(Icons.Outlined.Circle, MaterialTheme.colorScheme.onSurfaceVariant, "Skipped")
        is RestorePhase.Failed -> Triple(Icons.Filled.Warning, MaterialTheme.colorScheme.error, phase.message)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = CashuTheme.spacing.snug),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CashuTheme.spacing.snug),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(SelectIconSize),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = shortenMintUrl(url),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = if (phase is RestorePhase.Failed) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            if (phase is RestorePhase.Failed) {
                Row(horizontalArrangement = Arrangement.spacedBy(CashuTheme.spacing.snug)) {
                    GhostButton(text = "Retry", onClick = onRetry)
                    GhostButton(text = "Skip", onClick = onSkip)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/** iOS shortenUrl: strip scheme + trailing slash for display. */
private fun shortenMintUrl(url: String): String =
    url.removePrefix("https://").removePrefix("http://").trimEnd('/')

/** iOS normalizedMintUrl: quote-strip, https-default, trailing-slash trim. */
private fun normalizeMintUrl(raw: String): String? {
    var trimmed = raw.trim().trim('"', '\'')
    if (trimmed.isEmpty()) return null
    if (!trimmed.startsWith("http://", ignoreCase = true) &&
        !trimmed.startsWith("https://", ignoreCase = true)
    ) {
        trimmed = "https://$trimmed"
    }
    return trimmed.trimEnd('/')
}
