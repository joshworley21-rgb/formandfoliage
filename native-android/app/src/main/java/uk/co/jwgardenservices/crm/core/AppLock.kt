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

    private var appContext: Context? = null
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

    /** Called when the app leaves the foreground. */
    fun lock() {
        if (enabledState.value) lockState.value = true
    }

    fun unlock() {
        lockState.value = false
    }
}
