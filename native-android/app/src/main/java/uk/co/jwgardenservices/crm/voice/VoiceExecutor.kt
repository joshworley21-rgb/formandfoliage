package uk.co.jwgardenservices.crm.voice

import java.time.Instant
import java.time.LocalDate
import uk.co.jwgardenservices.crm.data.model.Client
import uk.co.jwgardenservices.crm.data.model.ScheduledJob
import uk.co.jwgardenservices.crm.data.model.VisitDraft
import uk.co.jwgardenservices.crm.domain.BillingSource
import uk.co.jwgardenservices.crm.domain.VoiceIntent
import uk.co.jwgardenservices.crm.domain.daysOverdue
import uk.co.jwgardenservices.crm.domain.formatDayMonth
import uk.co.jwgardenservices.crm.domain.formatTime
import uk.co.jwgardenservices.crm.domain.hoursLabel
import uk.co.jwgardenservices.crm.domain.localDate
import uk.co.jwgardenservices.crm.domain.parseTimestamp
import uk.co.jwgardenservices.crm.domain.money
import uk.co.jwgardenservices.crm.domain.priceVisit
import uk.co.jwgardenservices.crm.domain.round2
import uk.co.jwgardenservices.crm.ui.CrmViewModel
import uk.co.jwgardenservices.crm.ui.onDay

/**
 * Carries out what was said, using nothing but the offline cache. Money
 * actions come back as a pending confirmation the caller must read out and
 * have confirmed before they run, so a misheard word can never mark an
 * invoice paid on its own.
 */
class VoiceExecutor(private val viewModel: CrmViewModel) {

    data class Reply(val text: String, val endSession: Boolean = false)

    data class Pending(val label: String, val run: suspend () -> String)

    suspend fun handle(intent: VoiceIntent): Reply {
        val existing = pending
        return when {
            existing != null && intent == VoiceIntent.Confirm -> {
                pending = null
                Reply(existing.run())
            }
            existing != null && intent == VoiceIntent.Cancel -> {
                pending = null
                Reply("Okay, dropped that.")
            }
            existing != null -> {
                pending = null
                Reply("Cancelled. " + respond(intent).text)
            }
            intent == VoiceIntent.Confirm -> Reply("There's nothing waiting to confirm.")
            intent == VoiceIntent.Cancel -> Reply("Nothing to cancel.")
            else -> respond(intent)
        }
    }

    private suspend fun respond(intent: VoiceIntent): Reply = when (intent) {
        VoiceIntent.TodayRound -> todayRound()
        VoiceIntent.NextJob -> nextJob()
        VoiceIntent.OutstandingSummary -> outstanding()
        VoiceIntent.ChaseOverdue -> chase()
        VoiceIntent.Help -> help()
        VoiceIntent.Stop -> Reply("Okay, ending the call.", endSession = true)
        is VoiceIntent.MarkJobDone -> markJobDone(intent.who)
        is VoiceIntent.LogHours -> logHours(intent.hours, intent.who)
        is VoiceIntent.InvoicePaid -> invoicePaid(intent.number)
        is VoiceIntent.PriceJob -> priceJob(intent.hours, intent.rate, intent.materials)
        is VoiceIntent.Unknown -> Reply(
            "I didn't catch that. You can say: what's on today, next job, " +
                "mark louisa done, log three hours for louisa, what's outstanding, " +
                "or price three hours at thirty.",
        )
        else -> Reply("I didn't catch that.")
    }

    private fun todayRound(): Reply {
        val state = viewModel.state.value
        val today = LocalDate.now()
        val jobs = state.jobs.filter { it.status == "scheduled" }.onDay(today).sortedBy { it.startsAt }
        if (jobs.isEmpty()) return Reply("Nothing booked today. Enjoy the quiet.")
        val names = jobs.take(4).joinToString {
            val time = parseTimestamp(it.startsAt)?.formatTime().orEmpty()
            "${time} ${clientFor(it.clientId)?.name ?: "a client"}"
        }
        val more = if (jobs.size > 4) ", and ${jobs.size - 4} more" else ""
        return Reply(
            "You have ${jobs.size} ${if (jobs.size == 1) "job" else "jobs"} today: $names$more. " +
                "That's ${hoursLabel(jobs.sumOf { it.durationMinutes } / 60.0)}.",
        )
    }

    private fun nextJob(): Reply {
        val state = viewModel.state.value
        val next = state.jobs
            .filter { it.status == "scheduled" }
            .mapNotNull { job -> parseTimestamp(job.startsAt)?.let { starts -> job to starts } }
            .filter { (_, starts) -> starts.isAfter(Instant.now()) }
            .minByOrNull { (_, starts) -> starts }
            ?: return Reply("Nothing left in the diary.")
        val (job, starts) = next
        val client = clientFor(job.clientId)
        return Reply(
            "Next up: ${client?.name ?: "a client"} at ${starts.formatTime()} " +
                "on ${starts.localDate().formatDayMonth()}.",
        )
    }

    private suspend fun markJobDone(who: String): Reply {
        val state = viewModel.state.value
        val today = LocalDate.now()
        val job = state.jobs
            .filter { it.status == "scheduled" }
            .onDay(today)
            .firstOrNull { matchesSpoken(it.clientId, who) }
            ?: return Reply("I can't find a job for $who today.")
        val client = clientFor(job.clientId) ?: return Reply("The client is missing.")
        val hours = round2(job.durationMinutes / 60.0)
        val draft = VisitDraft(
            clientId = client.id,
            visitDate = today.toString(),
            hours = hours,
            materialsCost = 0.0,
            description = "Logged by voice",
            amount = round2(
                priceVisit(
                    BillingSource(client.billingMode, client.hourlyRate, client.fixedPrice),
                    hours,
                    0.0,
                ),
            ),
            completesJobId = job.id,
            jobOutcome = "done",
        )
        return confirm("Finish the ${client.name} job, ${hoursLabel(hours)} logged. Say yes to confirm.") {
            viewModel.saveVisit(draft)
            "${client.name} ticked off, ${money(draft.amount)} logged."
        }
    }

    private suspend fun logHours(hours: Double?, who: String): Reply {
        val parsed = hours
            ?: return Reply("How many hours? For example: log three hours for louisa.")
        val state = viewModel.state.value
        val client = state.clients.firstOrNull { matchesSpoken(it.id, who) }
            ?: return Reply("I can't find a client called $who.")
        val draft = VisitDraft(
            clientId = client.id,
            visitDate = LocalDate.now().toString(),
            hours = round2(parsed),
            materialsCost = 0.0,
            description = "Logged by voice",
            amount = round2(
                priceVisit(
                    BillingSource(client.billingMode, client.hourlyRate, client.fixedPrice),
                    parsed,
                    0.0,
                ),
            ),
        )
        return confirm("Log ${hoursLabel(parsed)} for ${client.name}, ${money(draft.amount)}. Say yes to confirm.") {
            viewModel.saveVisit(draft)
            "Logged ${hoursLabel(parsed)} for ${client.name}."
        }
    }

    private suspend fun invoicePaid(number: String): Reply {
        val state = viewModel.state.value
        val invoice = state.invoices
            .filter { it.status != "paid" }
            .firstOrNull { it.number.filter { c -> c.isDigit() }.endsWith(number) }
            ?: return Reply("I can't find an unpaid invoice ending in $number.")
        val client = clientFor(invoice.clientId)
        return confirm(
            "Mark ${invoice.number} for ${client?.name ?: "the client"}, ${money(invoice.total)}, paid. Say yes to confirm.",
        ) {
            viewModel.markInvoicePaid(invoice.id)
            "${invoice.number} marked paid."
        }
    }

    private fun outstanding(): Reply {
        val state = viewModel.state.value
        val unpaid = state.invoices.filter { it.status != "paid" }
        if (unpaid.isEmpty()) return Reply("Everything is paid. Nice.")
        val total = unpaid.sumOf { it.total }
        val names = unpaid.take(3).joinToString {
            "${clientFor(it.clientId)?.name ?: "a client"}, ${money(it.total)}"
        }
        return Reply(
            "${unpaid.size} ${if (unpaid.size == 1) "invoice" else "invoices"} outstanding, ${money(total)} total: $names" +
                if (unpaid.size > 3) ", and ${unpaid.size - 3} more." else ".",
        )
    }

    private fun chase(): Reply {
        val state = viewModel.state.value
        val overdue = state.invoices.filter { daysOverdue(it, LocalDate.now()) > 0 }
        if (overdue.isEmpty()) return Reply("Nothing is overdue. All good.")
        val worst = overdue.maxBy { daysOverdue(it, LocalDate.now()) }
        val client = clientFor(worst.clientId)?.name ?: "a client"
        return Reply(
            "${overdue.size} ${if (overdue.size == 1) "invoice is" else "invoices are"} overdue. " +
                "The worst is $client, ${money(worst.total)}, ${daysOverdue(worst, LocalDate.now())} days late.",
        )
    }

    private fun priceJob(hours: Double, rate: Double?, materials: Double?): Reply {
        if (rate == null) {
            return Reply("What hourly rate? For example: price three hours at thirty.")
        }
        val materialsValue = materials ?: 0.0
        val net = round2(hours * rate + materialsValue)
        val settings = viewModel.state.value.settings
        val vat = if (settings?.vatRegistered == true) round2(net * settings.vatRate / 100.0) else 0.0
        val gross = round2(net + vat)
        return Reply(
            if (vat > 0.0) "$net plus VAT, $gross total." else "$gross total.",
        )
    }

    private fun help(): Reply = Reply(
        "You can say: what's on today, next job, mark louisa done, " +
            "log three hours for louisa, what's outstanding, which invoices are overdue, " +
            "mark invoice twelve paid, or price three hours at thirty. " +
            "Money actions always ask you to confirm.",
    )

    private fun confirm(prompt: String, run: suspend () -> String): Reply {
        pending = Pending(label = prompt, run = run)
        return Reply(prompt)
    }

    private fun clientFor(clientId: String): Client? =
        viewModel.state.value.clients.firstOrNull { it.id == clientId }

    /** A client matches when every word the user said appears in their name. */
    private fun matchesSpoken(clientId: String, spoken: String): Boolean {
        val name = clientFor(clientId)?.name?.lowercase() ?: return false
        val said = spoken.lowercase().trim()
        if (said.isBlank()) return false
        return said.split(" ").all { word -> word.length >= 3 && name.contains(word) }
    }

    private var pending: Pending? = null
}
