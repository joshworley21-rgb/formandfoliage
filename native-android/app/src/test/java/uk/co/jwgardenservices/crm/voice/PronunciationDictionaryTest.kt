package uk.co.jwgardenservices.crm.voice

import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test

class PronunciationDictionaryTest {
    @After
    fun reset() {
        PronunciationDictionary.setDynamicTerms(emptyList())
        PronunciationDictionary.userEntries = emptyMap()
    }

    @Test
    fun `UK places and trade terms are respelled for speech`() {
        assertEquals("Beerton", PronunciationDictionary.apply("Bierton"))
        assertEquals("Steel", PronunciationDictionary.apply("Stihl"))
        assertEquals("Buckinghamshur", PronunciationDictionary.apply("Buckinghamshire"))
    }

    @Test
    fun `dynamic client and village names are canonicalised`() {
        PronunciationDictionary.setDynamicTerms(listOf("Smiths", "Bierton"))
        assertEquals("Smiths", PronunciationDictionary.apply("smiths"))
        assertTrue(PronunciationDictionary.canonicalTerms().contains("Smiths"))
    }

    @Test
    fun `the shared recogniser terms include built-in dictionary entries`() {
        val terms = PronunciationDictionary.canonicalTerms().map { it.lowercase() }
        assertTrue(terms.contains("bierton"))
        assertTrue(terms.contains("aylesbury"))
        assertTrue(terms.contains("stihl"))
    }
}
