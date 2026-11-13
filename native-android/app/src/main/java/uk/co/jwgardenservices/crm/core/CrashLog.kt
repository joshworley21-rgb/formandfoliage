package uk.co.jwgardenservices.crm.core

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.time.Instant

/**
 * Saves crash information to files so it can be shared from the phone.
 *
 * - [install] captures uncaught Java exceptions (and keeps startup failures).
 * - [captureExitInfo] runs on the next launch and recovers native crashes and
 *   low-memory kills from ApplicationExitInfo, which the exception handler
 *   can never see.
 * - [note] writes an assistant breadcrumb trail; when the process is killed
 *   natively, the last line says what it was doing.
 */
object CrashLog {
    private const val FILE = "last-crash.txt"
    private const val NOTES = "assistant.log"
    private const val MAX_NOTES = 64 * 1024

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
        captureExitInfo(appContext)
    }

    /**
     * Native code (sherpa-onnx, onnxruntime) crashes without running the
     * Java handler. Android records those exits; pull the newest interesting
     * one into the crash file on the next start.
     */
    fun captureExitInfo(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val file = File(context.applicationContext.filesDir, FILE)
        if (file.exists()) return
        runCatching {
            val manager = context.getSystemService(ActivityManager::class.java) ?: return
            val exits = manager.getHistoricalProcessExitReasons(context.packageName, 0, 8)
            val interesting = exits.filter {
                it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE ||
                    it.reason == ApplicationExitInfo.REASON_CRASH ||
                    it.reason == ApplicationExitInfo.REASON_LOW_MEMORY ||
                    it.reason == ApplicationExitInfo.REASON_SIGNALED ||
                    it.reason == ApplicationExitInfo.REASON_ANR
            }
            if (interesting.isEmpty()) return
            val text = buildString {
                interesting.forEach { info ->
                    appendLine("Exit at ${Instant.ofEpochMilli(info.timestamp)} — ${reasonName(info.reason)}")
                    appendLine("description: ${info.description}")
                    info.traceInputStream?.use { stream ->
                        val trace = stream.bufferedReader().readText().take(6000)
                        if (trace.isNotBlank()) appendLine(trace)
                    }
                    appendLine("---")
                }
                appendLine()
                appendLine("Assistant log (last lines):")
                append(notes(context))
            }
            file.writeText(text)
        }
    }

    /** Records an error that was caught instead of crashing (startup failures). */
    fun record(context: Context, error: Throwable, where: String) {
        runCatching {
            File(context.applicationContext.filesDir, FILE).writeText(
                buildString {
                    appendLine("Caught during $where on ${Instant.now()}")
                    append(Log.getStackTraceString(error))
                },
            )
        }
    }

    /** Assistant breadcrumb; safe to call from any thread. */
    fun note(context: Context, tag: String, message: String) {
        runCatching {
            val file = File(context.applicationContext.filesDir, NOTES)
            if (file.length() > MAX_NOTES) file.writeText("")
            file.appendText("${Instant.now()} [$tag] $message\n")
        }
    }

    fun read(context: Context): String? =
        File(context.applicationContext.filesDir, FILE)
            .takeIf { it.length() > 0 }
            ?.readText()

    /** Everything useful for a bug report, crash first. */
    fun diagnostics(context: Context): String = buildString {
        val crash = read(context)
        if (crash.isNullOrBlank()) appendLine("No crash report recorded.") else appendLine(crash)
        appendLine()
        appendLine("Assistant log:")
        append(notes(context).ifBlank { "(empty)" })
    }

    fun notes(context: Context): String =
        File(context.applicationContext.filesDir, NOTES)
            .takeIf { it.isFile }
            ?.readText()
            ?.takeLast(12_000)
            .orEmpty()

    fun clear(context: Context) {
        runCatching { File(context.applicationContext.filesDir, FILE).delete() }
        runCatching { File(context.applicationContext.filesDir, NOTES).delete() }
    }

    private fun reasonName(reason: Int): String = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> "Java crash"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "low memory kill"
        ApplicationExitInfo.REASON_SIGNALED -> "killed by signal"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "initialisation failure"
        else -> "reason $reason"
    }
}
