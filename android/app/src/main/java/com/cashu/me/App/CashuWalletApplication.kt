package com.cashu.me.App

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class CashuWalletApplication : Application() {
    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableContainer = MutableStateFlow<AppContainer?>(null)
    val container: StateFlow<AppContainer?> = mutableContainer.asStateFlow()

    @Volatile
    private var pendingDeepLink: String? = null

    override fun onCreate() {
        super.onCreate()
        // AppContainer loads DataStore-backed settings and cached wallet JSON.
        // Construct it away from the main thread so Application.onCreate can
        // return immediately and Android can draw the first frame.
        startupScope.launch {
            val built = AppContainer(this@CashuWalletApplication)
            publishContainer(built)
            // No-op unless the user opted into crash reports.
            built.sentryService.initialize()
        }
    }

    @Synchronized
    fun handleDeepLink(url: String?) {
        if (url.isNullOrBlank()) return
        val current = mutableContainer.value
        if (current != null) {
            current.navigationManager.handleDeepLink(url)
        } else {
            pendingDeepLink = url
        }
    }

    @Synchronized
    private fun publishContainer(container: AppContainer) {
        pendingDeepLink?.let(container.navigationManager::handleDeepLink)
        pendingDeepLink = null
        mutableContainer.value = container
    }
}
