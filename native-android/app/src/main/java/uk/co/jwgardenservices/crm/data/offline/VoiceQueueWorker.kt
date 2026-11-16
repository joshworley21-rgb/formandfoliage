package uk.co.jwgardenservices.crm.data.offline

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import uk.co.jwgardenservices.crm.core.CrashLog
import uk.co.jwgardenservices.crm.data.model.ClientDraft
import uk.co.jwgardenservices.crm.data.repository.ServiceLocator
import uk.co.jwgardenservices.crm.voice.AssistantContext
import uk.co.jwgardenservices.crm.voice.BusinessBrain
import uk.co.jwgardenservices.crm.voice.OpenAiCloudRouter

/**
 * Replays voice memos captured without signal through `voice-route` once the
 * phone is back on a network. The outcome is attached to the client as a
 * dated note (the reviewable artefact) and stored on the row for the crash
 * log; the row is then marked processed so it is never sent twice.
 *
 * Runs only with a validated connection, retries five times with backoff,
 * and never touches the microphone.
 */
class VoiceQueueWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (!ServiceLocator.isInitialised()) return Result.retry()
        val dao = ServiceLocator.database.pendingVoiceDao()
        val pending = dao.pending()
        if (pending.isEmpty()) return Result.success()

        val context = applicationContext
        val router = OpenAiCloudRouter(onEvent = { CrashLog.note(context, TAG, it) })
        val snapshot = runCatching {
            AssistantContext(businessProfile = BusinessBrain.load(context))
                .snapshot(emptyList(), emptyList())
        }.getOrDefault("")

        var outstanding = 0
        for (item in pending) {
            val reply = runCatching { router.ask(item.transcript, snapshot, emptyList()) }.getOrNull()
            if (reply == null || reply.answer.isBlank()) {
                outstanding++
                CrashLog.note(context, TAG, "memo ${item.id.take(8)} not processed")
                continue
            }
            val answer = reply.answer.trim()
            runCatching { appendNote(item.clientId, item.transcript, answer) }
                .onFailure { CrashLog.note(context, TAG, "note failed: ${it.message}") }
            dao.markProcessed(item.id, answer.take(600))
            CrashLog.note(context, TAG, "memo ${item.id.take(8)} processed")
        }
        return if (outstanding > 0 && runAttemptCount < 5) Result.retry() else Result.success()
    }

    /** Append the memo and the cloud's summary to the client's notes. */
    private suspend fun appendNote(clientId: String?, transcript: String, answer: String) {
        if (clientId == null) return
        val repository = ServiceLocator.crmRepository
        val client = repository.observeClients().first().firstOrNull { it.id == clientId } ?: return
        val combined = buildString {
            client.notes?.trim()?.takeIf { it.isNotEmpty() }?.let { append(it).append('\n') }
            append(LocalDate.now()).append(": voice memo — ").append(transcript.trim())
            if (answer.isNotBlank()) append(" (").append(answer).append(")")
        }
        repository.updateClient(
            client,
            ClientDraft(
                name = client.name,
                address = client.address,
                phone = client.phone,
                email = client.email,
                notes = combined,
                billingMode = client.billingMode,
                hourlyRate = client.hourlyRate,
                fixedPrice = client.fixedPrice,
                paymentReminders = client.paymentReminders,
            ),
        )
    }

    private companion object {
        const val TAG = "VoiceQueue"
    }
}

object VoiceQueueScheduler {
    private const val ONE_OFF = "voice-queue-now"
    private const val PERIODIC = "voice-queue-periodic"

    /** Process the queue as soon as a validated connection exists. */
    fun enqueue(context: Context) {
        val request = OneTimeWorkRequestBuilder<VoiceQueueWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            ONE_OFF,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    /** A slow sweep for memos saved while the app was then closed. */
    fun ensurePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<VoiceQueueWorker>(60, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }
}
