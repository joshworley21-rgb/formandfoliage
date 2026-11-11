package uk.co.jwgardenservices.crm

import android.app.Application
import io.sentry.android.core.SentryAndroid
import uk.co.jwgardenservices.crm.core.AppLock
import uk.co.jwgardenservices.crm.core.CrashLog
import uk.co.jwgardenservices.crm.data.repository.ServiceLocator

class JwCrmApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        ServiceLocator.init(this)
        AppLock.init(this)
        // No-op unless a SENTRY_DSN was baked in at build time (set it as a
        // GitHub secret named SENTRY_DSN). Without a DSN Sentry stays silent.
        if (BuildConfig.SENTRY_DSN.isNotBlank()) {
            SentryAndroid.init(this) { options -> options.dsn = BuildConfig.SENTRY_DSN }
        }
    }
}
