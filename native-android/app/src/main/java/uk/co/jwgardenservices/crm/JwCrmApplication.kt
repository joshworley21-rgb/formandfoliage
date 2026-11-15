package uk.co.jwgardenservices.crm

import android.app.ActivityManager
import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import uk.co.jwgardenservices.crm.core.AppLock
import io.sentry.android.core.SentryAndroid
import uk.co.jwgardenservices.crm.core.CrashLog
import uk.co.jwgardenservices.crm.core.TaxReservePrefs
import uk.co.jwgardenservices.crm.data.repository.ServiceLocator
import uk.co.jwgardenservices.crm.voice.AssistantPreferences
import uk.co.jwgardenservices.crm.voice.SpeechEngine

class JwCrmApplication : Application() {

    private val warmUp = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        // Startup problems are recorded and survived, not fatal: a crash loop
        // here would also stop the crash report from ever reaching the screen.
        runCatching { ServiceLocator.init(this) }
            .onFailure { CrashLog.record(this, it, "ServiceLocator.init") }
        TaxReservePrefs.load(this)
        runCatching { AppLock.init(this) }
            .onFailure { CrashLog.record(this, it, "AppLock.init") }
        // Init happens here, never via Sentry's auto-start providers: a failure
        // is recorded and the app keeps working without crash reporting.
        runCatching {
            SentryAndroid.init(this) { options ->
                options.dsn = BuildConfig.SENTRY_DSN
                options.environment = "production"
            }
        }.onFailure { CrashLog.record(this, it, "Sentry.init") }
        // No-op unless a SENTRY_DSN was baked in at build time (set it as a
        // GitHub secret named SENTRY_DSN). Without a DSN Sentry stays silent.
        warmSpeechModel()
    }

    /**
     * Load the 190 MB recogniser after the first screen has drawn, but only
     * once the assistant has been used before. A returning caller then opens
     * the tab to a warm model instead of watching "Connecting…"; someone who
     * never uses the assistant never pays the memory.
     */
    private fun warmSpeechModel() {
        warmUp.launch {
            delay(1_200)
            // Never hold 190 MB on a phone that already reports low memory.
            val manager = getSystemService(ActivityManager::class.java)
            if (manager?.isLowRamDevice == true) return@launch
            val used = runCatching { AssistantPreferences.usedBefore(this@JwCrmApplication) }
                .getOrDefault(false)
            if (!used) return@launch
            runCatching { SpeechEngine.get(this@JwCrmApplication).prepare() }
                .onFailure { CrashLog.record(this@JwCrmApplication, it, "SpeechEngine.prepare") }
        }
    }
}
