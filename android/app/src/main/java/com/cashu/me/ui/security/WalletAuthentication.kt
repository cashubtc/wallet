package com.cashu.me.ui.security

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.launch
import com.cashu.me.Core.AppLockManager

@Composable
fun rememberWalletAuthenticationLauncher(
    appLockManager: AppLockManager,
): (reason: String, onAuthenticated: () -> Unit) -> Unit {
    val context = LocalContext.current
    val activity = remember(context) { context.findFragmentActivity() }
    val scope = rememberCoroutineScope()
    return remember(appLockManager, activity, scope) {
        { reason, onAuthenticated ->
            scope.launch {
                if (appLockManager.authenticate(activity, reason)) {
                    onAuthenticated()
                }
            }
        }
    }
}

internal tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}

internal tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
