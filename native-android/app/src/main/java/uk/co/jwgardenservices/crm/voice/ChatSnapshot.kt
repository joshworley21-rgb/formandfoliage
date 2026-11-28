package uk.co.jwgardenservices.crm.voice

import uk.co.jwgardenservices.crm.data.model.Client
import uk.co.jwgardenservices.crm.data.model.Enquiry
import uk.co.jwgardenservices.crm.data.model.Invoice
import uk.co.jwgardenservices.crm.data.model.Quote
import uk.co.jwgardenservices.crm.data.model.ScheduledJob
import uk.co.jwgardenservices.crm.data.model.Visit
import uk.co.jwgardenservices.crm.domain.formatShortWeekday
import uk.co.jwgardenservices.crm.domain.formatTime
import uk.co.jwgardenservices.crm.domain.localDate
import uk.co.jwgardenservices.crm.domain.money
import uk.co.jwgardenservices.crm.domain.parseDate
import uk.co.jwgardenservices.crm.domain.parseTimestamp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A short, question-shaped summary of the business for the clever chat model.
 *
 * The model reads this instead of the database, so it has to be small: on a
 * phone the time to the first word is mostly the time to read the prompt. Only
 * the parts a question is about are included, and every figure is computed
 * here, so the model reports numbers rather than inventing them.
 */
object ChatSnapshot {
    /** Everything the summary is built from; plain data so it can be tested. */
    data class Data(
        val today: LocalDate,
        val clients: List<Client> = emptyList(),
        val jobs: List<ScheduledJob> = emptyList(),
        val visits: List<Visit> = emptyList(),
        val invoices: List<Invoice> = emptyList(),
        val quotes: List<Quote> = emptyList(),
        val enquiries: List<Enquiry> = emptyList(),
        val monthlyRunningCosts: Double = 0.0,
    )

    enum class Topic { Money, Diary, Pipeline }

    private const val MAX_CHARS = 1_500
    private val dayFormat = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.UK)

    private val moneyWords = Regex(
        "\\b(owe|owes|owed|owing|paid|pay|unpaid|money|earn|earned|earning|takings|income|profit|" +
            "cost|costs|month|week|year|invoice|invoices|bill|billed|unbilled|busier|busiest|" +
            "quieter|compare|better|worse|doing|best|worst|average|total|hours|worth|revenue)\\b",
    )
    private val diaryWords = Regex(
        "\\b(today|tomorrow|tonight|week|weekend|next|booked|job|jobs|diary|schedule|free|busy|" +
            "when|monday|tuesday|wednesday|thursday|friday|saturday|sunday|coming|upcoming|plan)\\b",
    )
    private val pipelineWords = Regex("\\b(quote|quotes|enquiry|enquiries|lead|leads|new work|prospect)\\b")

    /** Which parts of the business the question touches; with none, a brief overview of two. */
    fun topics(question: String): Set<Topic> {
        val lower = question.lowercase()
        val found = buildSet {
            if (moneyWords.containsMatchIn(lower)) add(Topic.Money)
            if (diaryWords.containsMatchIn(lower)) add(Topic.Diary)
            if (pipelineWords.containsMatchIn(lower)) add(Topic.Pipeline)
        }
        return found.ifEmpty { setOf(Topic.Money, Topic.Diary) }
    }

    /** Clients named in the question, by full name or by first or last word. */
    fun clientsMentioned(question: String, clients: List<Client>): List<Client> {
        val lower = question.lowercase()
        return clients.filter { client ->
            val name = client.name.lowercase().trim()
            name.isNotEmpty() && (
                lower.contains(name) ||
                    name.split(Regex("\\s+")).any { word ->
                        word.length >= 4 && Regex("\\b${Regex.escape(word)}s?\\b").containsMatchIn(lower)
                    }
                )
        }.take(2)
    }

    fun format(data: Data, question: String): String {
        val lines = mutableListOf<String>()
        lines += "Today is ${data.today.format(dayFormat)}."
        val mentioned = clientsMentioned(question, data.clients)
        val topics = topics(question)
        if (Topic.Money in topics) lines += moneyLines(data)
        if (Topic.Diary in topics) lines += diaryLines(data)
        if (Topic.Pipeline in topics) lines += pipelineLines(data)
        mentioned.forEach { lines += clientLines(it, data) }
        return lines.filter { it.isNotBlank() }.joinToString("\n").take(MAX_CHARS)
    }

    private fun valid(visit: Visit) = !visit.missed

    private fun takings(visits: List<Visit>, from: LocalDate, to: LocalDate): Pair<Double, Double> {
        var amount = 0.0
        var hours = 0.0
        for (visit in visits) {
            if (!valid(visit)) continue
            val date = parseDate(visit.visitDate) ?: continue
            if (date.isBefore(from) || date.isAfter(to)) continue
            amount += visit.amount
            hours += visit.hours
        }
        return amount to hours
    }

    private fun moneyLines(data: Data): List<String> {
        val today = data.today
        val weekStart = today.with(DayOfWeek.MONDAY)
        val monthStart = today.withDayOfMonth(1)
        val lastMonthStart = monthStart.minusMonths(1)
        val (week, weekHours) = takings(data.visits, weekStart, today)
        val (lastWeek, _) = takings(data.visits, weekStart.minusWeeks(1), weekStart.minusDays(1))
        val (month, monthHours) = takings(data.visits, monthStart, today)
        val (lastMonth, lastMonthHours) = takings(data.visits, lastMonthStart, monthStart.minusDays(1))
        val (year, _) = takings(data.visits, today.withDayOfYear(1), today)
        val out = mutableListOf<String>()
        out += "Takings: this week ${money(week)} (${hours(weekHours)}), last week ${money(lastWeek)}; " +
            "this month ${money(month)} (${hours(monthHours)}), last month ${money(lastMonth)} " +
            "(${hours(lastMonthHours)}); this year ${money(year)}."
        val names = data.clients.associateBy { it.id }
        val unpaid = data.invoices.filter { it.status != "paid" && it.status != "draft" && it.total > 0 }
        if (unpaid.isEmpty()) {
            out += "Owed: nothing outstanding."
        } else {
            val top = unpaid.groupBy { it.clientId }
                .map { (id, rows) -> (names[id]?.name ?: "a client") to rows.sumOf { it.total } }
                .sortedByDescending { it.second }
                .take(5)
                .joinToString("; ") { "${it.first} ${money(it.second)}" }
            out += "Owed: ${money(unpaid.sumOf { it.total })} across ${unpaid.size} invoices. Most: $top."
        }
        val unbilled = data.visits.filter { valid(it) && !it.billed && it.amount > 0 }
        if (unbilled.isNotEmpty()) {
            out += "Done but not invoiced: ${money(unbilled.sumOf { it.amount })} from ${unbilled.size} visits."
        }
        if (data.monthlyRunningCosts > 0) {
            out += "Running costs: ${money(data.monthlyRunningCosts)} a month."
        }
        return out
    }

    private fun diaryLines(data: Data): List<String> {
        val names = data.clients.associateBy { it.id }
        val upcoming = data.jobs
            .filter { it.status == "scheduled" }
            .mapNotNull { job -> parseTimestamp(job.startsAt)?.let { job to it } }
            .filter { (_, starts) -> !starts.localDate().isBefore(data.today) }
            .sortedBy { (_, starts) -> starts }
        if (upcoming.isEmpty()) return listOf("Diary: nothing booked from today.")
        val week = upcoming.filter { (_, starts) -> starts.localDate().isBefore(data.today.plusDays(8)) }
        val shown = week.take(12)
        val text = shown.joinToString("; ") { (job, starts) ->
            "${starts.formatShortWeekday()} ${starts.localDate().dayOfMonth} ${starts.formatTime()} " +
                (names[job.clientId]?.name ?: "a client")
        }
        val more = if (week.size > shown.size) " (+${week.size - shown.size} more)" else ""
        val later = upcoming.size - week.size
        return listOf(
            "Diary, next 7 days: $text$more.",
            if (later > 0) "Booked after that: $later more jobs." else "",
        )
    }

    private fun pipelineLines(data: Data): List<String> {
        val out = mutableListOf<String>()
        val fresh = data.enquiries.filter { it.status == "new" }
        out += if (fresh.isEmpty()) {
            "Enquiries: none new."
        } else {
            "New enquiries: ${fresh.size} (" + fresh.take(5).joinToString(", ") { it.name } + ")."
        }
        val waiting = data.quotes.filter { it.status == "sent" }
        out += if (waiting.isEmpty()) {
            "Quotes waiting on a reply: none."
        } else {
            "Quotes waiting on a reply: ${waiting.size}, worth ${money(waiting.sumOf { it.total })}."
        }
        return out
    }

    private fun clientLines(client: Client, data: Data): String {
        val parts = mutableListOf<String>()
        parts += if (client.billingMode == "fixed") {
            "${money(client.fixedPrice)} a visit"
        } else {
            "${money(client.hourlyRate)} an hour"
        }
        val last = data.visits.filter { it.clientId == client.id && valid(it) }
            .mapNotNull { parseDate(it.visitDate) }.maxOrNull()
        if (last != null) parts += "last visit ${last.format(dayFormat)}"
        val owed = data.invoices.filter {
            it.clientId == client.id && it.status != "paid" && it.status != "draft" && it.total > 0
        }.sumOf { it.total }
        parts += if (owed > 0) "owes ${money(owed)}" else "owes nothing"
        val next = data.jobs.filter { it.clientId == client.id && it.status == "scheduled" }
            .mapNotNull { job -> parseTimestamp(job.startsAt) }
            .filter { !it.localDate().isBefore(data.today) }
            .minOrNull()
        if (next != null) parts += "next booked ${next.localDate().format(dayFormat)}"
        client.notes?.takeIf { it.isNotBlank() }?.let { parts += "note: ${it.trim().take(100)}" }
        return "${client.name}: " + parts.joinToString(", ") + "."
    }

    private fun hours(value: Double): String =
        if (value == Math.floor(value)) "${value.toLong()}h" else String.format(Locale.UK, "%.1fh", value)
}

/** The prompt that makes a chat reply. Short on purpose: see [ChatSnapshot]. */
object ChatBrain {
    fun system(owner: String, snapshot: String, remembered: List<String>, style: String): String = buildString {
        appendLine("You are $owner's secretary for a UK gardening business, replying in a text chat.")
        appendLine("Use only the figures below; if something is not there, say you don't have it. Never invent numbers or names.")
        appendLine("Be warm and plain, British spelling. Short answers, a couple of sentences, no lists unless asked.")
        if (style.isNotBlank()) appendLine(style)
        appendLine("Business data:")
        appendLine(snapshot)
        if (remembered.isNotEmpty()) appendLine("Remembered: " + remembered.take(6).joinToString("; ") + ".")
    }.trimEnd()

    /** A reply with the model's own thinking tags and stray formatting removed. */
    fun tidy(raw: String): String = raw
        .replace(Regex("(?s)<think>.*?(</think>|$)"), "")
        .replace(Regex("<\\|[^>]*\\|>"), "")
        .replace(Regex("[*_`#]+"), "")
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
}
