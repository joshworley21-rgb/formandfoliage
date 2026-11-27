package uk.co.jwgardenservices.crm.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ToolRouteTest {
    private val clients = listOf("Smith", "Rose Cottage", "The Hendersons")

    private fun route(name: String, said: String, vararg args: Pair<String, Any?>) =
        ToolRoute.toCommand(ToolInvocation(name, args.toMap()), said, clients)

    @Test
    fun `a spoken number beats the models reading of it`() {
        val command = route("log_hours", "log 4 hours for Smith", "client" to "Smith", "hours" to 5.0, "day" to "today")
        assertEquals("log 4 hours for Smith", command?.text)
        assertEquals(SpokenIntent.LogHours, command?.intent)
    }

    @Test
    fun `half a day becomes hours only when no number was spoken`() {
        val command = route("log_hours", "put Smith down for a half day", "client" to "Smith", "hours" to 4.0, "day" to "")
        assertEquals("log 4 hours for Smith", command?.text)
        assertEquals("Smith", command?.client)
        assertEquals("log 3.5 hours for Smith", route("log_hours", "put Smith down for most of a day", "client" to "Smith", "hours" to 3.5)?.text)
    }

    @Test
    fun `implausible or missing hours are refused`() {
        assertNull(route("log_hours", "put Smith down for a half day", "client" to "Smith", "hours" to 20.0))
        assertNull(route("log_hours", "put Smith down for a half day", "client" to "Smith", "hours" to 0.0))
        assertNull(route("log_hours", "put Smith down for a half day", "client" to "Smith"))
        assertNull(route("log_hours", "put Smith down for a half day", "client" to "Smith", "hours" to "lots"))
    }

    @Test
    fun `hours may arrive as text`() {
        assertEquals("log 2 hours for Smith", route("log_hours", "put Smith down for a couple of hours", "client" to "Smith", "hours" to "2")?.text)
    }

    @Test
    fun `yesterday only counts when it was said`() {
        assertEquals(
            "log 3 hours for Smith yesterday",
            route("log_hours", "log 3 hours for Smith yesterday", "client" to "Smith", "hours" to 3.0, "day" to "yesterday")?.text,
        )
        assertEquals(
            "log 3 hours for Smith",
            route("log_hours", "log 3 hours for Smith", "client" to "Smith", "hours" to 3.0, "day" to "yesterday")?.text,
        )
    }

    @Test
    fun `a client the model invented is dropped so the runner asks`() {
        val command = route("log_hours", "log 3 hours", "client" to "Smith", "hours" to 3.0)
        assertEquals("log 3 hours", command?.text)
        assertEquals("", command?.client)
    }

    @Test
    fun `a first name resolves to the whole customer`() {
        val command = route("mark_job_done", "that's Rose all finished", "client" to "Rose")
        assertEquals("mark Rose Cottage done", command?.text)
        assertEquals("Rose Cottage", command?.client)
    }

    @Test
    fun `the diary only uses a day that was actually said`() {
        assertEquals("what have I got on tomorrow", route("check_schedule", "what's on tomorrow", "day" to "tomorrow")?.text)
        assertEquals("what have I got on friday", route("check_schedule", "what am I doing on Friday", "day" to "friday")?.text)
        assertEquals("what have I got on next week", route("check_schedule", "anything in next week", "day" to "next week")?.text)
        assertEquals("what have I got on today", route("check_schedule", "what's on", "day" to "friday")?.text)
        assertEquals("what have I got on today", route("check_schedule", "what's on", "day" to "")?.text)
    }

    @Test
    fun `a note needs a client and some text`() {
        val command = route("add_note", "remember Smith's gate sticks", "client" to "Smith", "note" to "gate sticks")
        assertEquals("note for Smith: gate sticks", command?.text)
        assertNull(route("add_note", "remember the gate sticks", "client" to "Smith", "note" to "gate sticks"))
        assertNull(route("add_note", "remember Smith's gate sticks", "client" to "Smith", "note" to "  "))
        assertNull(route("add_note", "remember Smith's gate sticks", "client" to "Smith", "note" to "Smith"))
    }

    @Test
    fun `records are not changed without supporting words`() {
        assertNull(route("mark_job_done", "what's next at Smith", "client" to "Smith"))
        assertNull(route("add_note", "when are the Smith in", "client" to "Smith", "note" to "gate"))
        assertNull(route("log_hours", "lovely weather today Smith", "client" to "Smith", "hours" to 3.0))
    }

    @Test
    fun `who owes me needs talk about money`() {
        assertEquals("who owes me money", route("who_owes_me", "who hasn't paid me yet")?.text)
        assertNull(route("who_owes_me", "how are you getting on"))
    }

    @Test
    fun `an expense must use a figure that was spoken`() {
        val command = route(
            "add_expense", "bought £30 of compost for Smith",
            "client" to "Smith", "amount" to 30.0, "item" to "compost",
        )
        assertEquals("materials £30 compost for Smith", command?.text)
        assertEquals(SpokenIntent.AddExpense, command?.intent)
        assertNull(route("add_expense", "bought £30 of compost for Smith", "client" to "Smith", "amount" to 40.0, "item" to "compost"))
        assertEquals(
            "materials £12.5 compost for Smith",
            route("add_expense", "bought £12.5 of compost for Smith", "client" to "Smith", "amount" to 12.5, "item" to "compost")?.text,
        )
        assertNull(route("add_expense", "bought some compost for Smith", "client" to "Smith", "amount" to 0.0, "item" to "compost"))
    }

    @Test
    fun `an expense item is cleaned`() {
        val command = route(
            "add_expense", "bought £30 of compost for Smith",
            "client" to "Smith", "amount" to 30.0, "item" to "compost; DROP TABLE <b>x</b>",
        )
        assertEquals("materials £30 compost DROP TABLE b x b for Smith", command?.text)
    }

    @Test
    fun `unknown tools are refused`() {
        assertNull(route("turn_on_flashlight", "turn the torch on"))
        assertNull(route("", "anything"))
    }

    @Test
    fun `every command reads back as the action it was built for`() {
        val built = listOf(
            route("log_hours", "log 4 hours for Smith", "client" to "Smith", "hours" to 4.0),
            route("check_schedule", "what's on tomorrow", "day" to "tomorrow"),
            route("add_note", "remember Smith's gate sticks", "client" to "Smith", "note" to "gate sticks"),
            route("mark_job_done", "Smith is done", "client" to "Smith"),
            route("who_owes_me", "who hasn't paid me"),
            route("add_expense", "bought £30 of compost for Smith", "client" to "Smith", "amount" to 30.0, "item" to "compost"),
        )
        built.forEach { assertNotNull(it) }
        built.forEach { command ->
            assertEquals(
                "'${command!!.text}' should read as ${command.intent}",
                command.intent,
                IntentRules.classify(command.text).intent,
            )
        }
    }

    @Test
    fun `raw call text is understood when the runtime does not parse it`() {
        val call = ToolRoute.parseRawCall(
            "<start_function_call>call:log_hours{client:<escape>Simon<escape>,hours:4,day:<escape>today<escape>}<end_function_call>",
        )
        assertEquals("log_hours", call?.name)
        assertEquals("Simon", call?.arguments?.get("client"))
        assertEquals(4.0, call?.arguments?.get("hours"))
        assertEquals("today", call?.arguments?.get("day"))
    }

    @Test
    fun `commas inside an escaped value stay in the value`() {
        val call = ToolRoute.parseRawCall("call:add_note{client:<escape>Smith<escape>,note:<escape>gate sticks, needs oil<escape>}")
        assertEquals("gate sticks, needs oil", call?.arguments?.get("note"))
    }

    @Test
    fun `a call with no arguments and text with no call`() {
        assertEquals(emptyMap<String, Any?>(), ToolRoute.parseRawCall("call:who_owes_me{}")?.arguments)
        assertNull(ToolRoute.parseRawCall("I am not sure what you mean."))
    }

    @Test
    fun `the new actions are chosen by the model but read from the sentence`() {
        fun routed(tool: String, said: String, vararg args: Pair<String, Any?>) = route(tool, said, *args)
        val book = routed("book_job", "book the Smiths in for Tuesday at 10", "client" to "Smith")
        assertEquals(SpokenIntent.BookJob, book?.intent)
        // The day and time are the owner's own words, never the model's.
        assertEquals("book the Smiths in for Tuesday at 10", book?.text)
        assertEquals("Smith", book?.client)

        assertEquals(SpokenIntent.MoveJob, routed("move_job", "move Smith to Thursday", "client" to "Smith")?.intent)
        assertEquals(SpokenIntent.CancelJob, routed("cancel_job", "cancel Smith on Friday", "client" to "Smith")?.intent)
        assertEquals(SpokenIntent.MarkPaid, routed("mark_paid", "Smith has paid", "client" to "Smith")?.intent)
        assertEquals(SpokenIntent.UpdateClient, routed("update_client", "change Smith's number to 07123 456789", "client" to "Smith")?.intent)
        assertEquals(SpokenIntent.QuoteStatus, routed("quote_status", "Smith accepted the quote", "client" to "Smith")?.intent)
        assertEquals(SpokenIntent.EnquiryStatus, routed("enquiry_status", "I called the enquiry from Sarah", "name" to "Sarah")?.intent)
        assertEquals(
            SpokenIntent.BackfillVisits,
            routed("backfill_visits", "backdate Smith's visits this month", "client" to "Smith")?.intent,
        )
        assertEquals(SpokenIntent.Enquiries, routed("enquiries", "any new enquiries")?.intent)
        assertEquals(SpokenIntent.QuotesWaiting, routed("quotes_waiting", "any quotes waiting")?.intent)
        assertEquals(SpokenIntent.UnloggedJobs, routed("unlogged_jobs", "what haven't I logged")?.intent)
    }

    @Test
    fun `a tool the sentence gives no support for is refused`() {
        assertNull(route("book_job", "what's the weather like", "client" to "Smith"))
        assertNull(route("cancel_job", "when is Smith next in", "client" to "Smith"))
        assertNull(route("mark_paid", "how are you getting on", "client" to "Smith"))
        assertNull(route("quote_status", "what's on tomorrow", "client" to "Smith"))
        assertNull(route("enquiries", "what's on tomorrow"))
        assertNull(route("quotes_waiting", "what's on tomorrow"))
        assertNull(route("unlogged_jobs", "what's on tomorrow"))
        assertNull(route("move_job", "lovely day", "client" to "Smith"))
    }

    @Test
    fun `a client the model invented is dropped from the new actions too`() {
        val command = route("book_job", "book someone in for Tuesday", "client" to "Smith")
        assertEquals(SpokenIntent.BookJob, command?.intent)
        assertEquals("", command?.client)
    }

    @Test
    fun `a new client's name is passed on when the sentence did not use called`() {
        val said = "add Larry as a client"
        assertEquals("add Larry as a client called Larry", route("add_client", said, "name" to "Larry")?.text)
        // Already said with "called": left exactly as spoken.
        val called = "add a new client called Larry for Tuesday"
        assertEquals(called, route("add_client", called, "name" to "Larry")?.text)
        // A name nobody said is not invented into the sentence.
        assertEquals("add a new client", route("add_client", "add a new client", "name" to "Larry")?.text)
    }
}
