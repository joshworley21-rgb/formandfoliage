package uk.co.jwgardenservices.crm.core

import android.content.Context
import android.util.Log
import java.io.File
import java.time.Instant

/**
 * Saves the last uncaught crash to a file so the app can show it on the next
 * launch. Sentry replaces this when a DSN is configured; until then this is
 * how a crash on the phone reaches the developer.
 */
object CrashLog {
    private const val FILE = "last-crash.txt"

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                File(appContext.filesDir, FILE).writeText(
                    buildString {
                        appendLine("Crashed on ${Instant.now()}")
                        appendLine(Log.getStackTraceString(error))
                        append("Previous handler: ${previous?.javaClass?.name ?: "none"}")
                    },
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun read(context: Context): String? =
        File(context.applicationContext.filesDir, FILE)
            .takeIf { it.length() > 0 }
            ?.readText()

    fun clear(context: Context) {
        runCatching { File(context.applicationContext.filesDir, FILE).delete() }
    }
}
