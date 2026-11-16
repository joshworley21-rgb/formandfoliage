package uk.co.jwgardenservices.crm.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The shaping the voice actually speaks with — money, breaths and pace. */
class TtsProsodyTest {

    @Test
    fun `pace markers extract and strip`() {
        val (fast, fastText) = TtsProsody.extract("${TtsProsody.FAST}Sorted, done.")
        assertEquals(1.1f, fast, 0.001f)
        assertEquals("Sorted, done.", fastText)

        val (slow, slowText) = TtsProsody.extract("${TtsProsody.SLOW}Draft invoice, £360.")
        assertEquals(0.95f, slow, 0.001f)
        assertEquals("Draft invoice, £360.", slowText)

        val (none, plain) = TtsProsody.extract("Plain reply.")
        assertEquals(1f, none, 0.001f)
        assertEquals("Plain reply.", plain)
    }

    @Test
    fun `lead ins take an ellipsis`() {
        assertEquals("Right then... two visits.", TtsProsody.smooth("Right then, two visits."))
        assertEquals("No worries... I'll stick that in drafts.", TtsProsody.smooth("No worries, I'll stick that in drafts."))
    }

    @Test
    fun `phonon money reads like a tradesperson`() {
        val shaped = TtsProsody.forPhonon("Draft invoice for Simon, £180.")
        assertTrue(shaped.contains("one hundred and eighty quid"))
        assertTrue(!shaped.contains("£"))

        val pence = TtsProsody.forPhonon("Logged 2 hours, £87.50.")
        assertTrue(pence.contains("eighty seven pounds fifty"))

        val large = TtsProsody.forPhonon("In drafts: £845.")
        assertTrue(large.contains("845 pounds"))
        assertTrue(!large.contains("£845"))
    }

    @Test
    fun `phonon commas become em-dash breaths`() {
        val shaped = TtsProsody.forPhonon("First one: the Smiths, lawn and borders.")
        assertTrue(shaped.contains("the Smiths — lawn and borders"))
    }

    @Test
    fun `kokoro path keeps commas and the pound sign`() {
        val shaped = TtsProsody.smooth("Logged 3.5 hours for the Smiths, £87.50.")
        assertTrue(shaped.contains("£87.50"))
        assertTrue(!shaped.contains("—"))
    }
}
