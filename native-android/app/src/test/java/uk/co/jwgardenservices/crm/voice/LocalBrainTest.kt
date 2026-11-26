package uk.co.jwgardenservices.crm.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalBrainTest {
    private val clients = listOf("Simon Hart", "The Hendersons", "Rose Cottage", "Parkers")

    private fun parse(reply: String, said: String) = LocalBrain.parseIntent(reply, said, clients)

    @Test
    fun `a clean label and client are read`() {
        val result = parse("log_hours|Simon", "stick Simon down for a half day")
        assertNotNull(result)
        assertEquals(SpokenIntent.LogHours, result!!.intent)
        assertEquals("Simon Hart", result.clientOrTopic)
        assertEquals(false, result.requiresCloud)
    }

    @Test
    fun `a label with no client is fine`() {
        val result = parse("who_owes_me|", "who's still not paid me")
        assertEquals(SpokenIntent.CheckDebtors, result?.intent)
        assertEquals("", result?.clientOrTopic)
    }

    @Test
    fun `an echoed sentence before the label is skipped`() {
        val result = parse("where am I off to after this => next_job_address|", "where am I off to after this")
        assertEquals(SpokenIntent.NextJobAddress, result?.intent)
        val piped = parse("where am I off to|next_job_address|", "where am I off to")
        assertEquals(SpokenIntent.NextJobAddress, piped?.intent)
    }

    @Test
    fun `quotes, case and trailing punctuation are tolerated`() {
        assertEquals(SpokenIntent.Unbilled, parse("\"Unbilled\".", "anything not invoiced yet")?.intent)
        assertEquals(SpokenIntent.ClientList, parse("`client_list|`", "who do I work for")?.intent)
    }

    @Test
    fun `only the first line counts`() {
        val result = parse("\ncheck_schedule|\nsome explanation nobody asked for", "what's on tomorrow")
        assertEquals(SpokenIntent.CheckSchedule, result?.intent)
    }

    @Test
    fun `unknown, other and empty replies are not understood`() {
        assertNull(parse("make_coffee|", "make me a coffee"))
        assertNull(parse("other|", "how are you doing"))
        assertNull(parse("", "anything"))
        assertNull(parse("   \n  ", "anything"))
        assertNull(parse("I think the user wants to log hours", "log hours"))
    }

    @Test
    fun `a client the owner never said is dropped`() {
        val result = parse("check_schedule|Rose Cottage", "what's on tomorrow")
        assertEquals(SpokenIntent.CheckSchedule, result?.intent)
        assertEquals("", result?.clientOrTopic)
    }

    @Test
    fun `a spoken name that is not a customer is kept as said`() {
        val result = parse("last_visit|Mrs Bell", "when was I last at Mrs Bell's")
        assertEquals("Mrs Bell", result?.clientOrTopic)
    }

    @Test
    fun `a short spoken name resolves to the full customer name`() {
        assertEquals("The Hendersons", LocalBrain.verifiedClient("Hendersons", "the Hendersons are done", clients))
        assertEquals("Rose Cottage", LocalBrain.verifiedClient("rose cottage", "back at rose cottage", clients))
    }

    @Test
    fun `changing records needs a supporting word in the sentence`() {
        assertNull(parse("log_hours|Simon", "the weather is lovely today, Simon"))
        assertNull(parse("mark_job_done|Parkers", "what's next at the Parkers"))
        assertNull(parse("add_note|Parkers", "when are the Parkers in"))
        assertNull(parse("add_expense|", "how's the van doing"))
        assertNotNull(parse("log_hours|Simon", "put Simon down for a half day"))
        assertNotNull(parse("log_hours|", "log three and a half hours"))
        assertNotNull(parse("mark_job_done|Parkers", "that's the Parkers wrapped up"))
        assertNotNull(parse("add_note|Parkers", "remember the gate sticks at the Parkers"))
        assertNotNull(parse("add_expense|", "spent forty quid on diesel"))
    }

    @Test
    fun `weather needs a weather word`() {
        assertNull(parse("weather|", "what's the plan"))
        assertEquals(SpokenIntent.Weather, parse("weather|", "will it rain tomorrow")?.intent)
    }

    @Test
    fun `read only labels need no evidence`() {
        assertEquals(SpokenIntent.Profit, parse("profit|", "how are we doing for money")?.intent)
    }

    @Test
    fun `every label is unique and has a wire name the prompt mentions`() {
        assertEquals(LocalBrain.labels.size, LocalBrain.labels.toSet().size)
        val prompt = LocalBrain.intentPrompt(clients)
        LocalBrain.labels.forEach { assertTrue("${it.wire} missing from prompt", prompt.contains(it.wire)) }
    }

    @Test
    fun `the prompt fits the model's small context`() {
        val many = (1..60).map { "Customer number $it with a very long name indeed" }
        val prompt = LocalBrain.intentPrompt(many)
        // The model has 1,280 tokens in total; about 3 characters a token is
        // a safe estimate, and the sentence and the answer need room too.
        assertTrue("prompt was ${prompt.length} chars", prompt.length < 2_600)
        assertTrue(prompt.contains("Customers:"))
        assertTrue(!prompt.contains("Customer number 30 "))
    }

    @Test
    fun `no customers means no customer line`() {
        assertTrue(!LocalBrain.intentPrompt(emptyList()).contains("Customers:"))
    }
}
