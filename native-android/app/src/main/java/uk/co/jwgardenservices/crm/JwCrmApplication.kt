package uk.co.jwgardenservices.crm

import android.app.Application
import uk.co.jwgardenservices.crm.core.AppLock
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
        // No-op unless a SENTRY_DSN was baked in at build time (set it as a
        // GitHub secret named SENTRY_DSN). Without a DSN Sentry stays silent.
    }
}
