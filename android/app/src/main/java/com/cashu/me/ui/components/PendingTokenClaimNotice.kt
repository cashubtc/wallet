package com.cashu.me.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.cashu.me.Core.PendingTokenClaimCheckResult

@Composable
internal fun PendingTokenClaimNotice(outcome: PendingTokenClaimCheckResult?) {
    val announcement = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    when (outcome) {
        PendingTokenClaimCheckResult.NotClaimed -> InlineNotice(
            text = "Status checked",
            detail = "This token has not been claimed yet.",
            severity = NoticeSeverity.Info,
            modifier = announcement,
        )
        is PendingTokenClaimCheckResult.Failed -> InlineNotice(
            text = "Couldn't check status",
            detail = outcome.message.text,
            modifier = announcement,
            severity = NoticeSeverity.Caution,
        )
        PendingTokenClaimCheckResult.Claimed, null -> Unit
    }
}
