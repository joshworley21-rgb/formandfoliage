package uk.co.jwgardenservices.crm.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTest {
    private fun parse(text: String): VoiceIntent = parseVoiceCommand(text)

    @Test
    fun `today round phrases`() {
        assertEquals(VoiceIntent.TodayRound, parse("what's on today"))
        assertEquals(VoiceIntent.TodayRound, parse("What's my round today?"))
        assertEquals(VoiceIntent.TodayRound, parse("jobs today"))
    }

    @Test
    fun `next job phrases`() {
        assertEquals(VoiceIntent.NextJob, parse("what's next"))
        assertEquals(VoiceIntent.NextJob, parse("next job"))
    }

    @Test
    fun `mark a job done by client name`() {
        val intent = parse("mark louisa done") as VoiceIntent.MarkJobDone
        assertEquals("louisa", intent.who.trim())
        val finished = parse("the job for louisa and george is finished") as VoiceIntent.MarkJobDone
        assertEquals("louisa and george", finished.who.trim())
    }

    @Test
    fun `log hours with spoken numbers`() {
        val intent = parse("log three hours for louisa") as VoiceIntent.LogHours
        assertEquals(3.0, intent.hours!!, 0.001)
        assertTrue(intent.who.contains("louisa"))
        val half = parse("add two and a half hours for george") as VoiceIntent.LogHours
        assertEquals(2.5, half.hours!!, 0.001)
    }

    @Test
    fun `log hours without a number asks for it`() {
        val intent = parse("log hours for louisa") as VoiceIntent.LogHours
        assertEquals(null, intent.hours)
    }

    @Test
    fun `money summary phrases`() {
        assertEquals(VoiceIntent.OutstandingSummary, parse("what's outstanding"))
        assertEquals(VoiceIntent.OutstandingSummary, parse("who owes me money"))
    }

    @Test
    fun `overdue phrasing`() {
        assertEquals(VoiceIntent.ChaseOverdue, parse("which invoices are overdue"))
    }

    @Test
    fun `mark invoice paid by number`() {
        val intent = parse("mark invoice 12 paid") as VoiceIntent.InvoicePaid
        assertEquals("12", intent.number)
    }

    @Test
    fun `pricing with words and digits`() {
        val intent = parse("price three hours at thirty") as VoiceIntent.PriceJob
        assertEquals(3.0, intent.hours, 0.001)
        assertEquals(30.0, intent.rate!!, 0.001)
        assertEquals(null, intent.materials)
        val withMaterials = parse("price 2.5 hours at 30 with 15 materials") as VoiceIntent.PriceJob
        assertEquals(2.5, withMaterials.hours, 0.001)
        assertEquals(15.0, withMaterials.materials!!, 0.001)
    }

    @Test
    fun `confirmations and cancellations`() {
        assertEquals(VoiceIntent.Confirm, parse("yes"))
        assertEquals(VoiceIntent.Confirm, parse("yeah do it"))
        assertEquals(VoiceIntent.Cancel, parse("no cancel that"))
    }

    @Test
    fun `help and stop`() {
        assertEquals(VoiceIntent.Help, parse("what can you do"))
        assertEquals(VoiceIntent.Stop, parse("that's all"))
        assertEquals(VoiceIntent.Stop, parse("hang up"))
    }

    @Test
    fun `unrelated speech is unknown`() {
        assertTrue(parse("the weather is nice today") is VoiceIntent.Unknown)
    }
}
