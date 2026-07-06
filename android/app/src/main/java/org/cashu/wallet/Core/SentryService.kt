package org.cashu.wallet.Core

import android.content.Context
import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.SentryLevel
import io.sentry.android.core.SentryAndroid

internal interface SentryGateway {
    fun start(dsn: String)
    fun close()
    fun capture(error: Throwable)
    fun breadcrumb(message: String, category: String)
}

/**
 * Opt-in crash reporting mirroring Swift `SentryService`: every entry point except
 * `shutdown()` is a no-op unless the user enabled `settings.sentryEnabled` (default off).
 */
class SentryService internal constructor(
    private val gateway: SentryGateway,
    private val isEnabled: () -> Boolean,
) {
    constructor(context: Context, settingsStore: SettingsStore) : this(
        gateway = AndroidSentryGateway(context.applicationContext),
        isEnabled = { settingsStore.sentryEnabled },
    )

    companion object {
        // Shared with iOS (SentryService.swift) — one Sentry project for both platforms.
        private const val DSN =
            "https://aff293071a9e53305e76990761d4b38f@o4511625394061312.ingest.de.sentry.io/4511625402712144"
    }

    fun initialize() {
        if (!isEnabled()) return
        gateway.start(DSN)
    }

    fun shutdown() {
        gateway.close()
    }

    fun capture(error: Throwable) {
        if (!isEnabled()) return
        gateway.capture(error)
    }

    fun breadcrumb(message: String, category: String = "wallet") {
        if (!isEnabled()) return
        gateway.breadcrumb(message, category)
    }
}

private class AndroidSentryGateway(private val context: Context) : SentryGateway {
    override fun start(dsn: String) {
        SentryAndroid.init(context) { options ->
            options.dsn = dsn
            options.isSendDefaultPii = false
            options.isAttachScreenshot = false
            options.isAttachViewHierarchy = false
            options.isEnableAutoSessionTracking = true
            options.tracesSampleRate = 0.1
            options.profilesSampleRate = 0.0
        }
    }

    override fun close() {
        Sentry.close()
    }

    override fun capture(error: Throwable) {
        Sentry.captureException(error)
    }

    override fun breadcrumb(message: String, category: String) {
        val crumb = Breadcrumb()
        crumb.message = message
        crumb.category = category
        crumb.level = SentryLevel.INFO
        Sentry.addBreadcrumb(crumb)
    }
}
