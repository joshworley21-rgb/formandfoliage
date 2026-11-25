package uk.co.jwgardenservices.crm.voice

import kotlinx.coroutines.flow.first
import uk.co.jwgardenservices.crm.data.repository.CrmRepository
import uk.co.jwgardenservices.crm.data.repository.ServiceLocator
import uk.co.jwgardenservices.crm.domain.formatTime
import uk.co.jwgardenservices.crm.domain.formatWeekday
import uk.co.jwgardenservices.crm.domain.money
import uk.co.jwgardenservices.crm.domain.monthlyRunningCosts
import uk.co.jwgardenservices.crm.domain.parseDate
import uk.co.jwgardenservices.crm.domain.parseTimestamp
import java.time.Instant
import java.time.LocalDate

/**
 * A compact snapshot of the business, written for a language model rather
 * than for a screen.
 *
 * The assistant is given this before every sentence: who the clients are,
 * what is booked, what is owed and anything the owner asked it to remember.
 * That is what lets "move the Smiths job" or "who still owes me?" work
 * without the model having seen a single training example from this business.
 */
class AssistantContext(
    private val repository: CrmRepository = ServiceLocator.crmRepository,
    /** Distilled website profile, bundled as an asset. Null when unreadable. */
    private val businessProfile: String? = null,
) {
    suspend fun snapshot(memories: List<String>, turns: List<AssistantTurn>): String = buildString {
        val settings = runCatching { repository.getSettings() }.getOrNull()
        appendLine("Business: ${settings?.businessName ?: "Form & Foliage"}.")
        if (settings != null) {
            appendLine(
                "VAT: " + if (settings.vatRegistered) {
                    "registered at ${settings.vatRate}%."
                } else {
                    "not registered."
                },
            )
            settings.paymentTerms?.takeIf { it.isNotBlank() }?.let {
                appendLine("Payment terms: ${it.trim().take(120)}.")
            }
            if (settings.labourRate > 0.0) {
                appendLine("Labour cost: ${money(settings.labourRate)} an hour.")
            }
        }
        // Services, patch and credentials from the website, so answers about
        // what the business offers or where it covers come from the source of
        // truth rather than a language model's guess. A profile learned from
        // the live site (or edited in Settings) wins over the bundled asset.
        val storedProfile = settings?.businessProfile?.takeIf { it.isNotBlank() }
        (storedProfile ?: businessProfile)?.takeIf { it.isNotBlank() }?.let { profile ->
            appendLine("About the business:")
            appendLine(profile)
        }

        val now = Instant.now()
        appendLine(
            "Now: ${now.formatWeekday()} ${now.formatTime()}, " +
                LocalDate.now().year + ".",
        )

        val clients = runCatching { repository.observeClients().first() }.getOrDefault(emptyList())
        if (clients.isNotEmpty()) {
            appendLine("Clients: ${clients.take(30).joinToString { it.name }}.")
        }

        val names = clients.associateBy { it.id }
        val scheduled = runCatching { repository.observeJobs().first() }.getOrDefault(emptyList())
            .filter { it.status == "scheduled" }
            .mapNotNull { job -> parseTimestamp(job.startsAt)?.let { job to it } }
            .sortedBy { (_, starts) -> starts }

        // The slot the owner is standing in right now. Without this line a
        // 9am job is invisible to the model all afternoon, which is exactly
        // when "log four hours and mark it done" is said.
        scheduled.firstOrNull { (job, starts) ->
            !now.isBefore(starts) &&
                now.isBefore(starts.plusSeconds(job.durationMinutes.coerceAtLeast(15) * 60L))
        }?.let { (job, starts) ->
            val name = names[job.clientId]?.name ?: "a client"
            appendLine(
                "On now: $name since ${starts.formatTime()}, " +
                    "booked ${job.durationMinutes} minutes.",
            )
        }

        val jobs = scheduled.filter { (_, starts) -> !starts.isBefore(now) }.take(5)
        if (jobs.isNotEmpty()) {
            appendLine(
                "Next jobs: " + jobs.joinToString("; ") { (job, starts) ->
                    val name = names[job.clientId]?.name ?: "a client"
                    "${starts.formatWeekday()} ${starts.formatTime()} $name"
                } + ".",
            )
        }

        val invoices = runCatching { repository.observeInvoices().first() }.getOrDefault(emptyList())
        val unpaid = invoices.filter { it.status != "paid" && it.status != "draft" && it.total > 0 }
        if (unpaid.isNotEmpty()) {
            appendLine("Owed: ${money(unpaid.sumOf { it.total })} across ${unpaid.size} invoices.")
        } else {
            appendLine("Owed: nothing outstanding.")
        }

        val weekStart = LocalDate.now().with(java.time.DayOfWeek.MONDAY)
        val visits = runCatching { repository.observeVisits().first() }.getOrDefault(emptyList())
            .filter { visit ->
                if (visit.missed) return@filter false
                val date = parseDate(visit.visitDate) ?: return@filter false
                !date.isBefore(weekStart)
            }
        if (visits.isNotEmpty()) {
            appendLine("This week so far: ${money(visits.sumOf { it.amount })} from ${visits.size} visits.")
        }

        val costs = runCatching { repository.observeRunningCosts().first() }
            .getOrDefault(emptyList())
        if (costs.isNotEmpty()) {
            appendLine("Running costs: ${money(costs.monthlyRunningCosts())} a month.")
        }

        if (memories.isNotEmpty()) {
            appendLine("Remembered: " + memories.take(15).joinToString("; ") + ".")
        }
        val recent = turns.takeLast(6)
        if (recent.isNotEmpty()) {
            appendLine("Recent conversation:")
            recent.forEach { turn ->
                appendLine((if (turn.fromUser) "Owner: " else "Assistant: ") + turn.text.take(160))
            }
        }
    }.trim()
}
