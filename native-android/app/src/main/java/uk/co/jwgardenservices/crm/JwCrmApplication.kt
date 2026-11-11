package uk.co.jwgardenservices.crm

import android.app.Application
import uk.co.jwgardenservices.crm.data.repository.ServiceLocator

class JwCrmApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLocator.init(this)
    }
}
