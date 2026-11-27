package uk.co.jwgardenservices.crm.voice

import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet
import java.util.Locale

/** One function the small model asked for. */
data class ToolInvocation(val name: String, val arguments: Map<String, Any?>)

/** A model's choice, checked and turned into a sentence the action runner already understands. */
data class RoutedCommand(val intent: SpokenIntent, val text: String, val client: String)

/**
 * The actions FunctionGemma may choose between. The bodies never run: the
 * model only names a tool and its arguments, and [ToolRoute] checks them
 * before anything touches the owner's records.
 */
@Suppress("UNUSED_PARAMETER", "unused")
class RouterTools : ToolSet {
    @Tool(description = "Log hours worked at a client's property.")
    fun logHours(
        @ToolParam(description = "The client's name as spoken") client: String,
        @ToolParam(description = "Hours worked, for example 3.5") hours: Double,
        @ToolParam(description = "today or yesterday") day: String,
    ): String = ""

    @Tool(description = "Read out the diary for a day.")
    fun checkSchedule(
        @ToolParam(description = "today, tomorrow, a weekday name, this week or next week") day: String,
    ): String = ""

    @Tool(description = "Save a note on a client's file.")
    fun addNote(
        @ToolParam(description = "The client's name as spoken") client: String,
        @ToolParam(description = "The note to save") note: String,
    ): String = ""

    @Tool(description = "Mark a client's job as finished.")
    fun markJobDone(
        @ToolParam(description = "The client's name as spoken") client: String,
    ): String = ""

    @Tool(description = "List the clients who have unpaid invoices.")
    fun whoOwesMe(): String = ""

    @Tool(description = "Record the cost of materials bought for a client's job.")
    fun addExpense(
        @ToolParam(description = "The client's name as spoken") client: String,
        @ToolParam(description = "Cost in pounds, for example 30") amount: Double,
        @ToolParam(description = "What was bought") item: String,
    ): String = ""

    // The tools below only choose the action. The day, time, length and amounts
    // are read from the sentence itself, so the model has nothing to get wrong.

    @Tool(description = "Add a new client, and book them in if a day was mentioned.")
    fun addClient(@ToolParam(description = "The new client's name") name: String): String = ""

    @Tool(description = "Book a client in for a day and time.")
    fun bookJob(@ToolParam(description = "The client's name as spoken") client: String): String = ""

    @Tool(description = "Move a client's booking to another day or time.")
    fun moveJob(@ToolParam(description = "The client's name as spoken") client: String): String = ""

    @Tool(description = "Cancel a client's booking.")
    fun cancelJob(@ToolParam(description = "The client's name as spoken") client: String): String = ""

    @Tool(description = "Fill in or backdate a client's past visits and their hours.")
    fun backfillVisits(@ToolParam(description = "The client's name as spoken") client: String): String = ""

    @Tool(description = "A client has paid: mark their invoices as paid.")
    fun markPaid(@ToolParam(description = "The client's name as spoken") client: String): String = ""

    @Tool(description = "Change a client's phone number or hourly rate.")
    fun updateClient(@ToolParam(description = "The client's name as spoken") client: String): String = ""

    @Tool(description = "A quote was accepted or declined.")
    fun quoteStatus(@ToolParam(description = "The client's name as spoken") client: String): String = ""

    @Tool(description = "Update an enquiry: contacted, closed, or make them a client.")
    fun enquiryStatus(@ToolParam(description = "The enquirer's name") name: String): String = ""

    @Tool(description = "Read out the new enquiries.")
    fun enquiries(): String = ""

    @Tool(description = "Read out the quotes waiting on a reply.")
    fun quotesWaiting(): String = ""

    @Tool(description = "Read out the past jobs that have not been logged.")
    fun unloggedJobs(): String = ""
}

object ToolRoute {
    const val SYSTEM = "You are a model that can do function calling with the following functions"

    private val explicitHours = Regex("(\\d+(?:\\.\\d+)?)\\s*(?:hours?|hrs?)", RegexOption.IGNORE_CASE)
    private val weekdays = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
    private val owingEvidence = Regex("owe|paid|pay|unpaid|outstanding|money|invoice|debt|chase|behind|late")

    fun toCommand(call: ToolInvocation, utterance: String, clientNames: List<String>): RoutedCommand? {
        val heard = utterance.lowercase()
        fun said(key: String): String = (call.arguments[key] as? String)?.trim().orEmpty()
        fun client(): String = LocalBrain.verifiedClient(said("client"), utterance, clientNames)
        fun routed(intent: SpokenIntent, text: String, client: String): RoutedCommand? =
            if (LocalBrain.plausible(intent, utterance)) RoutedCommand(intent, text, client) else null

        return when (call.name.trim().lowercase()) {
            "log_hours" -> {
                // A number the owner actually said always beats the model's reading of it.
                val spoken = explicitHours.find(heard)?.groupValues?.get(1)?.toDoubleOrNull()
                val hours = spoken ?: number(call.arguments["hours"])?.takeIf { it > 0 && it <= 12 } ?: return null
                if (hours <= 0 || hours > 24) return null
                val who = client()
                val day = if (said("day").lowercase() == "yesterday" && "yesterday" in heard) " yesterday" else ""
                val text = "log ${plain(hours)} hours" + (if (who.isNotEmpty()) " for $who" else "") + day
                routed(SpokenIntent.LogHours, text, who)
            }
            "check_schedule" -> {
                val day = scheduleDay(said("day"), heard)
                routed(SpokenIntent.CheckSchedule, "what have I got on $day", "")
            }
            "add_note" -> {
                val who = client().ifEmpty { return null }
                val note = said("note").replace(Regex("\\s+"), " ").take(300)
                if (note.isBlank() || note.equals(who, ignoreCase = true)) return null
                routed(SpokenIntent.AddNote, "note for $who: $note", who)
            }
            "mark_job_done" -> {
                val who = client()
                routed(SpokenIntent.MarkJobDone, if (who.isNotEmpty()) "mark $who done" else "mark that job done", who)
            }
            "who_owes_me" ->
                if (owingEvidence.containsMatchIn(heard)) {
                    routed(SpokenIntent.CheckDebtors, "who owes me money", "")
                } else {
                    null
                }
            "add_expense" -> {
                val amount = number(call.arguments["amount"])?.takeIf { it > 0 && it <= 100_000 } ?: return null
                // The figure has to be one the owner said, not one the model made up.
                val figure = Regex("(?<![\\d.])" + Regex.escape(plain(amount)) + "(?![\\d])")
                if (!figure.containsMatchIn(heard)) return null
                val who = client()
                val item = said("item").replace(Regex("[^A-Za-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim().take(60)
                val text = "materials £${plain(amount)}" +
                    (if (item.isNotEmpty()) " $item" else "") +
                    (if (who.isNotEmpty()) " for $who" else "")
                routed(SpokenIntent.AddExpense, text, who)
            }
            "add_client" -> {
                val name = said("name")
                // "Add Larry as a client": give the runner the name it looks for.
                val text = if (VoiceSlots.newClientName(utterance) == null &&
                    name.isNotBlank() && heard.contains(name.lowercase())
                ) {
                    "$utterance called $name"
                } else {
                    utterance
                }
                routed(SpokenIntent.AddClient, text, "")
            }
            "book_job" -> routed(SpokenIntent.BookJob, utterance, client())
            "move_job" -> routed(SpokenIntent.MoveJob, utterance, client())
            "cancel_job" -> routed(SpokenIntent.CancelJob, utterance, client())
            "backfill_visits" -> routed(SpokenIntent.BackfillVisits, utterance, client())
            "mark_paid" -> routed(SpokenIntent.MarkPaid, utterance, client())
            "update_client" -> routed(SpokenIntent.UpdateClient, utterance, client())
            "quote_status" -> routed(SpokenIntent.QuoteStatus, utterance, client())
            "enquiry_status" -> routed(SpokenIntent.EnquiryStatus, utterance, "")
            "enquiries" -> routed(SpokenIntent.Enquiries, utterance, "")
            "quotes_waiting" -> routed(SpokenIntent.QuotesWaiting, utterance, "")
            "unlogged_jobs" -> routed(SpokenIntent.UnloggedJobs, utterance, "")
            else -> null
        }
    }

    /** Falls back to reading the raw call text when the runtime did not parse it. */
    fun parseRawCall(text: String): ToolInvocation? {
        val head = Regex("call:\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\{").find(text) ?: return null
        val body = text.substring(head.range.last + 1).substringBeforeLast("}", "")
        val args = mutableMapOf<String, Any?>()
        var i = 0
        while (i < body.length) {
            while (i < body.length && (body[i] == ',' || body[i].isWhitespace())) i++
            val colon = body.indexOf(':', i)
            if (colon < 0) break
            val key = body.substring(i, colon).trim()
            i = colon + 1
            val value: String
            if (body.startsWith(ESCAPE, i)) {
                val end = body.indexOf(ESCAPE, i + ESCAPE.length)
                if (end < 0) {
                    value = body.substring(i + ESCAPE.length)
                    i = body.length
                } else {
                    value = body.substring(i + ESCAPE.length, end)
                    i = end + ESCAPE.length
                }
                args[key] = value
            } else {
                val end = body.indexOf(',', i).let { if (it < 0) body.length else it }
                value = body.substring(i, end).trim()
                i = end
                args[key] = value.toDoubleOrNull() ?: value
            }
        }
        return ToolInvocation(head.groupValues[1], args)
    }

    private const val ESCAPE = "<escape>"

    private fun number(value: Any?): Double? = when (value) {
        is Number -> value.toDouble()
        is String -> value.replace("£", "").trim().toDoubleOrNull()
        else -> null
    }

    private fun plain(value: Double): String =
        if (value == Math.floor(value)) {
            value.toLong().toString()
        } else {
            String.format(Locale.ROOT, "%.2f", value).trimEnd('0').trimEnd('.')
        }

    /** A day the owner actually named, otherwise today. */
    private fun scheduleDay(requested: String, heard: String): String {
        val want = requested.lowercase().replace(Regex("^(?:on|next|this)\\s+"), "").trim()
        return when {
            want == "tomorrow" && "tomorrow" in heard -> "tomorrow"
            want in weekdays && want in heard -> want
            requested.lowercase().contains("next week") && "next week" in heard -> "next week"
            requested.lowercase().contains("this week") && "this week" in heard -> "this week"
            else -> "today"
        }
    }
}
