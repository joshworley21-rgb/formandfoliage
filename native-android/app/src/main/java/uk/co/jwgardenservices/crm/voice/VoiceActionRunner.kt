package uk.co.jwgardenservices.crm.voice

import kotlinx.coroutines.flow.first
import uk.co.jwgardenservices.crm.data.model.VisitDraft
import uk.co.jwgardenservices.crm.data.repository.CrmRepository
import uk.co.jwgardenservices.crm.data.repository.ServiceLocator
import uk.co.jwgardenservices.crm.domain.BillingSource
import uk.co.jwgardenservices.crm.domain.formatTime
import uk.co.jwgardenservices.crm.domain.hoursLabel
import uk.co.jwgardenservices.crm.domain.localDate
import uk.co.jwgardenservices.crm.domain.money
import uk.co.jwgardenservices.crm.domain.parseSpokenNumber
import uk.co.jwgardenservices.crm.domain.parseTimestamp
import uk.co.jwgardenservices.crm.domain.priceVisit
import uk.co.jwgardenservices.crm.domain.round2
import java.time.LocalDate

/**
 * Turns a spoken command into a real change in the CRM, not just a spoken
 * acknowledgement. The service owns no UI, so this talks to the same
 * repository the screens use (Room-backed, offline first).
 */
class VoiceActionRunner(private val repository: CrmRepository = ServiceLocator.crmRepository) {

    /**
     * Returns the sentence to speak when the command was executed, or null
     * when this runner cannot handle it (the caller falls back to the
     * generic confirmation).
     */
    suspend fun run(transcript: String, intent: QwenIntentEngine.Intent): String? {
        val text = transcript.lowercase()
        return when (intent.intent) {
            QwenIntentEngine.Kind.LogHours -> logHours(text)
            QwenIntentEngine.Kind.CheckSchedule -> today()
            else -> null
        }
    }

    private suspend fun logHours(text: String): String {
        val hours = spokenHours(text)
            ?: return "How many hours was that?"
        val clients = repository.observeClients().first()
        val client = clients.firstOrNull { matches(it.name, text) }
            ?: return "I couldn't match a client in that. Try saying the client's name."

        val date = if (text.contains("yesterday")) LocalDate.now().minusDays(1) else LocalDate.now()
        val draft = VisitDraft(
            clientId = client.id,
            visitDate = date.toString(),
            hours = round2(hours),
            materialsCost = 0.0,
            description = "Logged by voice",
            amount = round2(
                priceVisit(
                    BillingSource(client.billingMode, client.hourlyRate, client.fixedPrice),
                    hours,
                    0.0,
                ),
            ),
        )
        repository.saveVisit(draft)
        val whenText = if (date == LocalDate.now()) "" else " for ${date.dayOfMonth}/${date.monthValue}"
        return "Logged ${hoursLabel(hours)} for ${client.name}$whenText, ${money(draft.amount)}."
    }

    private suspend fun today(): String {
        val today = LocalDate.now()
        val jobs = repository.observeJobs().first()
            .filter { it.status == "scheduled" }
            .mapNotNull { job -> parseTimestamp(job.startsAt)?.let { job to it } }
            .filter { (_, starts) -> starts.localDate() == today }
            .sortedBy { (_, starts) -> starts }
        if (jobs.isEmpty()) return "Nothing booked today."
        val clients = repository.observeClients().first()
        val names = jobs.take(4).joinToString {
            val time = it.second.formatTime()
            val name = clients.firstOrNull { client -> client.id == it.first.clientId }?.name ?: "a client"
            "$time $name"
        }
        val more = if (jobs.size > 4) ", and ${jobs.size - 4} more" else ""
        return "You have ${jobs.size} ${if (jobs.size == 1) "job" else "jobs"} today: $names$more."
    }

    /** "eight hours", "8 hrs", "two and a half hours", "at eight hours". */
    private fun spokenHours(text: String): Double? {
        Regex("(\\d+(?:\\.\\d+)?)\\s*(?:hours?|hrs?)").find(text)?.let {
            return it.groupValues[1].toDoubleOrNull()
        }
        Regex("([a-z]+(?:\\s+(?:and\\s+)?a\\s+half)?)\\s*(?:hours?|hrs?)").find(text)?.let {
            return parseSpokenNumber(it.groupValues[1])
        }
        return null
    }

    private fun matches(clientName: String, text: String): Boolean {
        val words = clientName.lowercase().split(Regex("\\s+")).filter { it.length >= 3 }
        if (words.isEmpty()) return false
        return words.all { text.contains(it) }
    }
}
