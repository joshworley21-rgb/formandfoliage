package uk.co.jwgardenservices.crm.voice

import android.content.Context
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.flow.first
import uk.co.jwgardenservices.crm.data.model.PendingVoiceCommand
import uk.co.jwgardenservices.crm.data.model.ScheduledJob
import uk.co.jwgardenservices.crm.data.offline.VoiceQueueScheduler
import uk.co.jwgardenservices.crm.data.repository.ServiceLocator
import uk.co.jwgardenservices.crm.domain.localDate
import uk.co.jwgardenservices.crm.domain.parseTimestamp

/** What was kept, so the spoken reply can name the client. */
data class QueuedMemo(val clientName: String?)

/**
 * The zero-signal net: words that no local rule could act on, and that the
 * cloud could not answer because there is no data. They are stored verbatim
 * in Room and replayed through `voice-route` by [VoiceQueueWorker] the moment
 * the phone is back on a network, so a dictated quote is never lost on a
 * rural site.
 */
object PendingVoiceStore {

    /**
     * Store [transcript] and ask WorkManager to process it on reconnect.
     * Returns null when the transcript could not be saved.
     */
    suspend fun enqueue(context: Context, transcript: String): QueuedMemo? {
        val clean = transcript.trim()
        if (clean.isEmpty()) return null
        return runCatching {
            val client = bestClient(clean)
            ServiceLocator.database.pendingVoiceDao().insert(
                PendingVoiceCommand(
                    id = UUID.randomUUID().toString(),
                    transcript = clean,
                    createdAt = Instant.now().toString(),
                    clientId = client?.id,
                    clientName = client?.name,
                ),
            )
            VoiceQueueScheduler.enqueue(context)
            QueuedMemo(client?.name)
        }.getOrNull()
    }

    /**
     * The client a memo is about: a name spoken in the sentence first, else
     * whoever is on the schedule right now, else nobody.
     */
    private suspend fun bestClient(text: String): uk.co.jwgardenservices.crm.data.model.Client? {
        val repository = ServiceLocator.crmRepository
        val clients = runCatching { repository.observeClients().first() }.getOrDefault(emptyList())
        val lower = text.lowercase()
        clients.firstOrNull { it.name.isNotBlank() && lower.contains(it.name.lowercase()) }
            ?.let { return it }
        // A nickname counts too: "a note for the rectory" belongs to whoever
        // the rectory is.
        runCatching { repository.allVoiceAliases() }.getOrDefault(emptyList())
            .firstOrNull { (_, alias) -> alias.isNotBlank() && lower.contains(alias) }
            ?.let { (clientId, _) -> clients.firstOrNull { it.id == clientId } }
            ?.let { return it }

        val now = Instant.now()
        val today = now.localDate()
        val activeJob: Pair<ScheduledJob, Instant>? = runCatching {
            repository.observeJobs().first()
                .filter { job -> job.status == "scheduled" }
                .mapNotNull { job -> parseTimestamp(job.startsAt)?.let { job to it } }
                .filter { (_, starts) -> starts.localDate() == today && !starts.isAfter(now) }
                .sortedBy { (_, starts) -> starts }
                .lastOrNull()
        }.getOrNull()
        val clientId = activeJob?.first?.clientId ?: return null
        return clients.firstOrNull { it.id == clientId }
    }
}
