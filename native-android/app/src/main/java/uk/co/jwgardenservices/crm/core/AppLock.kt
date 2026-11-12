package uk.co.jwgardenservices.crm.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Optional fingerprint / phone-PIN gate. The choice is stored locally on the
 * device: it is a device-security preference, not business data, so it never
 * syncs and signing out leaves it as the person left it.
 *
 * The app fails open when a phone has no fingerprint or PIN set at all —
 * locking someone out of their own job list with no way back in is worse than
 * the risk of an unlocked phone.
 */
object AppLock {
    private const val PREFS = "app-security"
    private const val KEY_ENABLED = "app_lock_enabled"

    /** How long the app can sit in the background before locking again. */
    private const val GRACE_MS = 2 * 60_000L

    private var appContext: Context? = null
    private var backgroundedAt = 0L
    private val lockState = MutableStateFlow(true)
    private val enabledState = MutableStateFlow(true)

    /** True whenever the gate should be showing. Cold start starts locked. */
    val locked: StateFlow<Boolean> = lockState.asStateFlow()

    val enabled: StateFlow<Boolean> = enabledState.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
        enabledState.value = isEnabled(context)
        if (!enabledState.value) lockState.value = false
    }

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, value).apply()
        enabledState.value = value
        if (!value) lockState.value = false
    }

    /**
     * Called when the app leaves the foreground. It only notes when that
     * happened: locking on every background event meant a glance at a
     * notification or a two-second app switch demanded the fingerprint again.
     */
    fun onBackground() {
        if (enabledState.value) backgroundedAt = System.currentTimeMillis()
    }

    /**
     * Called when the app comes back. It locks only if the app was away for
     * longer than the grace period; quick switches carry straight on.
     */
    fun onForeground() {
        val leftAt = backgroundedAt
        backgroundedAt = 0L
        if (enabledState.value && leftAt > 0 && System.currentTimeMillis() - leftAt > GRACE_MS) {
            lockState.value = true
        }
    }

    /** Lock immediately, e.g. when the user signs out. */
    fun lock() {
        if (enabledState.value) lockState.value = true
    }

    fun unlock() {
        lockState.value = false
    }
}
