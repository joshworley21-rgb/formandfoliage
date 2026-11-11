package uk.co.jwgardenservices.crm.data.repository

import android.content.Context
import uk.co.jwgardenservices.crm.data.local.CrmDatabase
import uk.co.jwgardenservices.crm.data.local.DatabaseProvider
import uk.co.jwgardenservices.crm.data.offline.SyncEngine
import uk.co.jwgardenservices.crm.data.offline.SyncScheduler
import uk.co.jwgardenservices.crm.data.reminders.ReminderWorker

/**
 * Single, lazily-created home for the app's repositories.
 *
 * [init] must run from [android.app.Application.onCreate] so Room and WorkManager
 * have an application context before any screen asks for data.
 */
object ServiceLocator {
    @Volatile
    private var initialised = false

    lateinit var appContext: Context
        private set

    lateinit var database: CrmDatabase
        private set

    /** Set by AppViewModel once the signed-in user's workspace role is known. */
    @Volatile
    var workspaceRole: String = "owner"

    fun init(context: Context) {
        if (initialised) return
        appContext = context.applicationContext
        database = DatabaseProvider.get(appContext)
        initialised = true
        SyncScheduler.ensurePeriodic(appContext)
        SyncScheduler.enqueue(appContext)
        ReminderWorker.schedule(appContext)
    }

    val authRepository: AuthRepository by lazy { AuthRepository() }

    val workspaceRepository: WorkspaceRepository by lazy { WorkspaceRepository() }

    val crmRepository: CrmRepository by lazy {
        CrmRepository(database, onQueued = { SyncScheduler.enqueue(appContext) })
    }

    val syncEngine: SyncEngine by lazy {
        SyncEngine(database, refresh = { crmRepository.refreshAll(workspaceRole) })
    }
}
