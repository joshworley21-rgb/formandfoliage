package uk.co.jwgardenservices.crm.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CreateQuoteTest {
    @Test
    fun `price and work are read from the ways it is said`() {
        assertEquals(300.0 to "Cutting the hedge", VoiceSlots.quoteParts("quote the Smiths £300 for cutting the hedge", "Smiths"))
        assertEquals(450.0 to "New lawn", VoiceSlots.quoteParts("draft a quote for Rose Cottage, 450 quid, new lawn", "Rose Cottage"))
        assertEquals(1200.0 to "Patio clean", VoiceSlots.quoteParts("make a quote for Simon £1,200 for patio clean", "Simon Hart"))
        assertEquals(80.0 to "Hedge trim", VoiceSlots.quoteParts("quote Simon for hedge trim £80", "Simon"))
    }

    @Test
    fun `a new quote is told apart from quote questions`() {
        assertEquals(SpokenIntent.CreateQuote, IntentRules.classify("quote the Smiths £300 for cutting the hedge").intent)
        assertEquals(SpokenIntent.CreateQuote, IntentRules.classify("can you draft a quote for Rose Cottage 450 quid for a new lawn").intent)
        assertNotEquals(SpokenIntent.CreateQuote, IntentRules.classify("the Smiths accepted the quote").intent)
        assertNotEquals(SpokenIntent.CreateQuote, IntentRules.classify("any quotes waiting").intent)
    }
}
