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

    @Test
    fun `day and month abbreviations speak naturally`() {
        assertEquals(
            "the Smiths on the fourteenth of November",
            TtsProsody.forPhonon("the Smiths on 14 Nov"),
        )
        assertEquals(
            "the first of November",
            TtsProsody.forPhonon("1st Nov"),
        )
        assertEquals(
            "the fourteenth of September",
            TtsProsody.forPhonon("14 September"),
        )
    }

    @Test
    fun `slash dates expand`() {
        assertEquals(
            "the third of May",
            TtsProsody.forPhonon("03/05"),
        )
        assertEquals(
            "the fourteenth of November twenty twenty five",
            TtsProsody.forPhonon("14/11/2025"),
        )
    }

    @Test
    fun `a year after a date is spoken`() {
        assertEquals(
            "the fourteenth of November twenty twenty five",
            TtsProsody.forPhonon("14 Nov 2025"),
        )
        assertEquals(
            "the fifth of June two thousand and five",
            TtsProsody.forPhonon("5 Jun 2005"),
        )
    }

    @Test
    fun `standalone abbreviations and weekdays expand`() {
        assertEquals("November", TtsProsody.forPhonon("Nov"))
        assertEquals(
            "Thursday the fourteenth of November",
            TtsProsody.forPhonon("Thu 14 Nov"),
        )
        assertEquals(
            "see you Monday",
            TtsProsody.forPhonon("see you Mon"),
        )
    }

    @Test
    fun `full month names are left alone`() {
        assertTrue(TtsProsody.forPhonon("14 November").contains("November"))
        assertTrue(!TtsProsody.forPhonon("14 November").contains("Novemberber"))
    }
}
