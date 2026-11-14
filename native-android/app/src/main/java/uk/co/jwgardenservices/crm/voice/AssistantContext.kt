package uk.co.jwgardenservices.crm.voice

import kotlinx.coroutines.flow.first
import uk.co.jwgardenservices.crm.data.repository.CrmRepository
import uk.co.jwgardenservices.crm.data.repository.ServiceLocator
import uk.co.jwgardenservices.crm.domain.formatTime
import uk.co.jwgardenservices.crm.domain.formatWeekday
import uk.co.jwgardenservices.crm.domain.money
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
) {
    suspend fun snapshot(memories: List<String>, turns: List<AssistantTurn>): String = buildString {
        val settings = runCatching { repository.getSettings() }.getOrNull()
        appendLine("Business: ${settings?.businessName ?: "JW Garden Services"}.")
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

        val jobs = runCatching { repository.observeJobs().first() }.getOrDefault(emptyList())
            .filter { it.status == "scheduled" }
            .mapNotNull { job -> parseTimestamp(job.startsAt)?.let { job to it } }
            .filter { (_, starts) -> !starts.isBefore(now) }
            .sortedBy { (_, starts) -> starts }
            .take(5)
        if (jobs.isNotEmpty()) {
            val names = clients.associateBy { it.id }
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
