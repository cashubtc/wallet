package com.cashu.me.Core.Services

import android.content.Context
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ContactlessLimits(val perTap: Long = 1_000, val daily: Long = 10_000) {
    val isValid: Boolean get() = perTap >= 0 && daily in 1..2_100_000_000_000_000 && perTap <= daily
}

@Serializable
data class ContactlessSpendState(
    val limits: ContactlessLimits = ContactlessLimits(),
    val day: Long = 0,
    val spent: Long = 0,
) {
    fun recording(amount: Long, today: Long): ContactlessSpendState {
        check(limits.isValid && amount > 0 && spent >= 0) { ContactlessErrors.InvalidLimits }
        val current = if (today > day) copy(day = today, spent = 0) else this
        check(current.spent <= limits.daily && amount <= limits.daily - current.spent) { ContactlessErrors.DailyLimit }
        return current.copy(spent = current.spent + amount)
    }
}

object ContactlessErrors {
    const val InvalidLimits = "Choose a daily limit at least as large as the per-tap limit."
    const val DailyLimit = "Daily contactless limit reached. Use Send to review another payment."
    const val Authentication = "Payment not authorized. Authenticate with your device to continue."
    const val Inactive = "Contactless payment stopped. Open and unlock the wallet to try again."
    const val Storage = "Couldn't save contactless limits. No new payment was authorized."
}

class ContactlessSpendPolicy(
    private val load: () -> String?,
    private val save: (String) -> Unit,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val json = Json { encodeDefaults = true }

    fun state(): ContactlessSpendState {
        val stored = load() ?: return ContactlessSpendState()
        return runCatching { json.decodeFromString<ContactlessSpendState>(stored) }
            .getOrElse { error(ContactlessErrors.Storage) }
            .also { check(it.limits.isValid && it.spent >= 0) { ContactlessErrors.Storage } }
    }

    suspend fun saveLimits(limits: ContactlessLimits) = mutex.withLock {
        require(limits.isValid) { ContactlessErrors.InvalidLimits }
        save(json.encodeToString(ContactlessSpendState.serializer(), state().copy(limits = limits)))
    }

    suspend fun authorizeAndRecord(total: Long, isActive: () -> Boolean, authenticate: suspend () -> Boolean) = mutex.withLock {
        currentCoroutineContext().ensureActive()
        check(isActive()) { ContactlessErrors.Inactive }
        val initial = state().recording(total, day())
        if (total > initial.limits.perTap) check(authenticate()) { ContactlessErrors.Authentication }
        currentCoroutineContext().ensureActive()
        check(isActive()) { ContactlessErrors.Inactive }
        save(json.encodeToString(ContactlessSpendState.serializer(), state().recording(total, day())))
    }

    private fun day() = nowMillis() / 86_400_000

    companion object {
        @Volatile private var instance: ContactlessSpendPolicy? = null
        fun get(context: Context): ContactlessSpendPolicy = instance ?: synchronized(this) {
            instance ?: run {
                val store = context.applicationContext.getSharedPreferences("contactless_spend_policy", Context.MODE_PRIVATE)
                ContactlessSpendPolicy(
                    load = { store.getString("state", null) },
                    save = { check(store.edit().putString("state", it).commit()) { ContactlessErrors.Storage } },
                ).also { instance = it }
            }
        }
    }
}

class ContactlessSendCallbacks(
    val approve: suspend (Long) -> Unit,
    val deliver: suspend (String) -> Unit,
)

internal suspend fun <T> completeContactlessSend(
    approve: suspend () -> Unit,
    confirm: suspend () -> T,
    cancel: suspend () -> Unit,
    reclaim: suspend () -> Unit,
    deliver: suspend (T) -> Unit,
): T {
    var confirmationStarted = false
    try {
        approve()
        currentCoroutineContext().ensureActive()
        confirmationStarted = true
        val token = confirm()
        currentCoroutineContext().ensureActive()
        deliver(token)
        return token
    } catch (error: Throwable) {
        withContext(NonCancellable) {
            runCatching { if (confirmationStarted) reclaim() else cancel() }
        }
        throw error
    }
}
