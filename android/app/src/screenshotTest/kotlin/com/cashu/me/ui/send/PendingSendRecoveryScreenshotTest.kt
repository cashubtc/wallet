package com.cashu.me.ui.send

import android.content.res.Configuration
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.cashu.me.Core.PendingSend
import com.cashu.me.Core.PendingSendRecovery
import com.cashu.me.Core.SendRecoveryOutcome
import com.cashu.me.ui.components.NoticeSeverity
import com.cashu.me.ui.components.SheetHeader
import com.cashu.me.ui.theme.CashuTheme

private const val MintUrl = "https://mint.minibits.cash/Bitcoin"
private const val Token =
    "cashuBo2FteCJodHRwczovL21pbnQubWluaWJpdHMuY2FzaC9CaXRjb2luYXVjc2F0YXSBomFpSABQBVDwSUFGYXCBpGFhEGFzeBhwcmV2aWV3LXNlY3JldC0wMDAxYWNYIQJwcmV2aWV3"

// Short enough to stay one still code, so no reference depends on the animated encoder.
private val OneSend = listOf(PendingSend("send-1", 16, Token))
private val TwoSends = OneSend + PendingSend("send-2", 2_100, Token + "Mg")

/** The screen under the Send sheet's header, the chrome it most often takes over. */
@Composable
private fun RecoveryPreview(
    outcome: SendRecoveryOutcome,
    sends: List<PendingSend> = emptyList(),
    darkTheme: Boolean = true,
    activity: SendRecoveryActivity = SendRecoveryActivity.Idle,
    notice: SendRecoveryNotice? = null,
) {
    CashuTheme(darkTheme = darkTheme) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(modifier = Modifier.fillMaxSize()) {
                SheetHeader(
                    title = "Send",
                    navigationIcon = Icons.Outlined.Close,
                    navigationContentDescription = "Close",
                    onNavigationClick = {},
                )
                PendingSendRecoveryLayout(
                    recovery = PendingSendRecovery(MintUrl, "sat", sends.map { it.id }, sends, outcome),
                    activity = activity,
                    notice = notice,
                    reclaimed = null,
                    mintName = "Minibits",
                    // A fixed label: the host's locale and zone must not reach the reference.
                    sentLabel = { "Nov 24, 2026, 9:10 PM" },
                    onCheck = {},
                    onReclaim = {},
                    onDone = {},
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@PreviewTest
@Preview(name = "pending-ecash-dark", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun pendingEcashDarkScreenshot() = RecoveryPreview(SendRecoveryOutcome.Pending, OneSend)

@PreviewTest
@Preview(name = "pending-ecash-light", widthDp = 412, heightDp = 915)
@Composable
fun pendingEcashLightScreenshot() = RecoveryPreview(SendRecoveryOutcome.Pending, OneSend, darkTheme = false)

@PreviewTest
@Preview(name = "pending-ecash-several", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun pendingEcashSeveralScreenshot() = RecoveryPreview(SendRecoveryOutcome.Pending, TwoSends)

@PreviewTest
@Preview(name = "send-unconfirmed", widthDp = 412, heightDp = 915, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun sendUnconfirmedScreenshot() = RecoveryPreview(
    SendRecoveryOutcome.Unverified,
    notice = SendRecoveryNotice("Check your connection and try again.", NoticeSeverity.Error, "Couldn't check status"),
)
