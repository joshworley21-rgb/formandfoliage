package uk.co.jwgardenservices.crm.voice

import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceSlotsTest {
    private val today = LocalDate.of(2026, 11, 26)

    @Test
    fun `a new clients name`() {
        assertEquals("Larry", VoiceSlots.newClientName("Can you add a new client for Tuesday morning called Larry"))
        assertEquals("Mrs Bell", VoiceSlots.newClientName("add a new client called Mrs Bell at 12 High Street"))
        assertEquals("Louisa and George", VoiceSlots.newClientName("new client Louisa and George"))
        assertEquals("Rose Cottage", VoiceSlots.newClientName("add a client named rose cottage for tomorrow"))
        assertEquals("Larry", VoiceSlots.newClientName("add a new client called Larry."))
    }

    @Test
    fun `no name means ask`() {
        assertNull(VoiceSlots.newClientName("add a new client"))
        assertNull(VoiceSlots.newClientName("add a new client for Tuesday morning"))
        assertNull(VoiceSlots.newClientName("what's on tomorrow"))
    }

    @Test
    fun `phone numbers`() {
        assertEquals("07123456789", VoiceSlots.phone("her number is 07123 456789"))
        assertEquals("01296123456", VoiceSlots.phone("call 01296 123 456"))
        assertNull(VoiceSlots.phone("log 3 hours for Smith"))
        assertNull(VoiceSlots.phone("it costs 0123"))
    }

    @Test
    fun `rates`() {
        assertEquals(30.0, VoiceSlots.rateIn("change the rate to £30 an hour"))
        assertEquals(27.5, VoiceSlots.rateIn("set it to £27.50"))
        assertEquals(35.0, VoiceSlots.rateIn("35 quid an hour"))
        assertNull(VoiceSlots.rateIn("log 3 hours"))
    }

    @Test
    fun `moving a job`() {
        val move = VoiceSlots.moveTargets("move the Smiths from Tuesday to Thursday at 10", today)
        assertEquals(LocalDate.of(2026, 12, 1), move.from)
        assertEquals(LocalDate.of(2026, 12, 3), move.to)
        assertEquals(LocalTime.of(10, 0), move.time)
    }

    @Test
    fun `moving a job with only a destination`() {
        val move = VoiceSlots.moveTargets("move the Smiths to Friday", today)
        assertNull(move.from)
        assertEquals(LocalDate.of(2026, 11, 27), move.to)
        assertNull(move.time)
        assertEquals(LocalDate.of(2026, 11, 27), VoiceSlots.moveTargets("reschedule the Smiths for tomorrow", today).to)
    }

    @Test
    fun `a duration after for is not a destination`() {
        val move = VoiceSlots.moveTargets("move the Smiths to Thursday for 2 hours", today)
        assertEquals(LocalDate.of(2026, 12, 3), move.to)
    }

    @Test
    fun `nothing to move to`() {
        val move = VoiceSlots.moveTargets("move the Smiths", today)
        assertNull(move.to)
        assertNull(move.time)
    }

    @Test
    fun `a phone number is read back digit by digit`() {
        assertEquals("0 7 1 2 3, 4 5 6 7 8 9", VoiceSlots.spokenDigits("07123456789"))
        assertEquals("1 2 3 4, 5 6 7 8", VoiceSlots.spokenDigits("12345678"))
    }
}
