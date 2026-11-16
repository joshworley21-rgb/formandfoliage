package uk.co.jwgardenservices.crm.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BusinessBrainTest {

    private val profile = """
        BUSINESS: JW Garden Services. Based in Bierton, Aylesbury.
        SERVICES:
        Garden Maintenance: weekly visits, lawn cuts.
        VOCABULARY: Bierton, Berryfields, Kingsbrook, Fairford Leys
        TRADE TERMS: Ceanothus, Alliums, sleepers
        VOICE: Short sentences.
    """.trimIndent()

    @Test
    fun `vocabulary parses the patch list`() {
        assertEquals(
            listOf("Bierton", "Berryfields", "Kingsbrook", "Fairford Leys"),
            BusinessBrain.vocabulary(profile),
        )
    }

    @Test
    fun `trade terms parse`() {
        assertEquals(
            listOf("Ceanothus", "Alliums", "sleepers"),
            BusinessBrain.tradeTerms(profile),
        )
    }

    @Test
    fun `missing sections and profiles are empty`() {
        assertEquals(emptyList<String>(), BusinessBrain.vocabulary("no markers here"))
        assertEquals(emptyList<String>(), BusinessBrain.tradeTerms(null))
    }

    @Test
    fun `the bundled asset parses`() {
        // The real asset is what ships; a typo in a marker would silently
        // drop every patch hotword, so parse the actual file on disk.
        val file = java.io.File("src/main/assets/business_knowledge.txt")
        val text = file.readText()
        assertEquals(19, BusinessBrain.vocabulary(text).size)
        assertEquals(11, BusinessBrain.tradeTerms(text).size)
    }
}

class TradeTermsTest {

    @Test
    fun `shorthand maps to the website line items`() {
        assertEquals(
            "Scheduled Garden Maintenance",
            TradeTerms.lineItemFor("log two hours maintenance at berryfields"),
        )
        assertEquals("Hedge trimming", TradeTerms.lineItemFor("hedge at the smiths"))
        assertEquals("New turf", TradeTerms.lineItemFor("laid some turf today"))
    }

    @Test
    fun `plain logging stays generic`() {
        assertNull(TradeTerms.lineItemFor("log three hours for mrs jones"))
    }
}
