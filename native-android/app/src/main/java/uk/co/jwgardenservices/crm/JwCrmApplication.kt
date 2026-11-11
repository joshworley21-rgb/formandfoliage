package uk.co.jwgardenservices.crm

import android.app.Application
import uk.co.jwgardenservices.crm.core.AppLock
import io.sentry.android.core.SentryAndroid
import uk.co.jwgardenservices.crm.core.CrashLog
import uk.co.jwgardenservices.crm.data.repository.ServiceLocator

class JwCrmApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        // Startup problems are recorded and survived, not fatal: a crash loop
        // here would also stop the crash report from ever reaching the screen.
        runCatching { ServiceLocator.init(this) }
            .onFailure { CrashLog.record(this, it, "ServiceLocator.init") }
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
    }
}
