package uk.co.jwgardenservices.crm.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.co.jwgardenservices.crm.data.model.Client
import uk.co.jwgardenservices.crm.data.model.Invoice
import uk.co.jwgardenservices.crm.data.model.Visit
import java.time.LocalDate

class ChatSnapshotTest {
    private val today = LocalDate.of(2026, 11, 27)
    private val smith = Client(id = "c1", userId = "u", name = "Simon Hart", hourlyRate = 25.0)
    private val rose = Client(id = "c2", userId = "u", name = "Rose Cottage", hourlyRate = 30.0)

    private fun visit(id: String, client: String, date: String, amount: Double, hours: Double = 2.0, billed: Boolean = true) =
        Visit(id = id, userId = "u", clientId = client, visitDate = date, hours = hours, amount = amount, billed = billed)

    private val data = ChatSnapshot.Data(
        today = today,
        clients = listOf(smith, rose),
        visits = listOf(
            visit("1", "c1", "2026-11-24", 100.0),
            visit("2", "c2", "2026-11-03", 60.0, billed = false),
            visit("3", "c1", "2026-10-20", 200.0, hours = 4.0),
            visit("4", "c1", "2026-11-25", 999.0).copy(missed = true),
        ),
        invoices = listOf(
            Invoice(id = "i1", userId = "u", clientId = "c1", number = "1", issueDate = "2026-11-01", total = 150.0, status = "sent"),
            Invoice(id = "i2", userId = "u", clientId = "c2", number = "2", issueDate = "2026-11-02", total = 80.0, status = "paid"),
        ),
    )

    @Test
    fun `the question decides which parts are included`() {
        assertEquals(setOf(ChatSnapshot.Topic.Money), ChatSnapshot.topics("how much am I owed this month"))
        assertTrue(ChatSnapshot.Topic.Diary in ChatSnapshot.topics("what's on tomorrow"))
        assertTrue(ChatSnapshot.Topic.Pipeline in ChatSnapshot.topics("any new enquiries"))
        assertEquals(
            setOf(ChatSnapshot.Topic.Money, ChatSnapshot.Topic.Diary),
            ChatSnapshot.topics("tell me something clever"),
        )
    }

    @Test
    fun `figures are worked out here, with missed visits left out`() {
        val text = ChatSnapshot.format(data, "am I busier this month than last month")
        assertTrue(text, text.contains("this month £160.00"))
        assertTrue(text, text.contains("last month £200.00"))
        assertFalse(text, text.contains("999"))
        assertTrue(text, text.contains("Owed: £150.00 across 1 invoices"))
        assertTrue(text, text.contains("Simon Hart £150.00"))
        assertTrue(text, text.contains("Done but not invoiced: £60.00"))
    }

    @Test
    fun `a named client gets their own line`() {
        val text = ChatSnapshot.format(data, "how is Simon getting on")
        assertTrue(text, text.contains("Simon Hart: £25.00 an hour"))
        assertTrue(text, text.contains("owes £150.00"))
        assertEquals(listOf(smith), ChatSnapshot.clientsMentioned("what does simon owe", data.clients))
        assertEquals(emptyList<Client>(), ChatSnapshot.clientsMentioned("who owes me", data.clients))
    }

    @Test
    fun `the summary stays small`() {
        val many = data.copy(
            clients = (1..80).map { Client(id = "x$it", userId = "u", name = "Customer $it") },
        )
        assertTrue(ChatSnapshot.format(many, "how am I doing").length <= 1_500)
    }

    @Test
    fun `thinking tags and formatting are removed from a reply`() {
        assertEquals("You're owed £150.", ChatBrain.tidy("<think>work it out</think>**You're owed £150.**"))
        // Still thinking when it ran out of words: there is no answer to show.
        assertEquals("", ChatBrain.tidy("<think>never closed\nstill going"))
    }
}
