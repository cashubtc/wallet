package com.cashu.me.Core

import android.content.Context
import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.SentryLevel
import io.sentry.android.core.SentryAndroid
import com.cashu.me.BuildConfig

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

    @Volatile
    private var started = false

    fun initialize() {
        if (!isEnabled() || started) return
        gateway.start(BuildConfig.SENTRY_DSN)
        started = true
    }

    fun shutdown() {
        gateway.close()
        started = false
    }

    fun capture(error: Throwable) {
        if (!isEnabled()) return
        gateway.capture(AppLogger.privacySafeThrowable(error))
    }

    fun breadcrumb(message: String, category: String = "wallet") {
        if (!isEnabled()) return
        gateway.breadcrumb(AppLogger.privacySafeMessage(message), category)
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
