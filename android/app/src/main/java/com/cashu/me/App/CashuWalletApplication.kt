package com.cashu.me.App

import android.app.Application

class CashuWalletApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // No-op unless the user opted into crash reports.
        container.sentryService.initialize()
    }
}
