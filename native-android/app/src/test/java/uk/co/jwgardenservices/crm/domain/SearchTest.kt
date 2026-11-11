package uk.co.jwgardenservices.crm.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTest {
    private data class Record(val name: String, val address: String?)

    private val records = listOf(
        Record("Mrs Smith", "1 High Street, Bierton HP22 7DG"),
        Record("José García", "Aylesbury"),
        Record("O'Brien", "Wendover"),
    )

    @Test
    fun `accents and punctuation are ignored`() {
        assertEquals("jose garcia", normalise("José García"))
        assertEquals("o brien", normalise("O'Brien"))
        assertEquals("hp22 7dg", normalise("HP22 7DG"))
    }

    @Test
    fun `an empty query has no terms`() {
        assertEquals(emptyList<String>(), searchTerms("   "))
        assertEquals(listOf("smith", "hp22"), searchTerms(" Smith  HP22 "))
    }

    @Test
    fun `every word must appear somewhere`() {
        assertTrue(matches(listOf("Mrs Smith", "HP22 7DG"), searchTerms("smith hp22")))
        assertFalse(matches(listOf("Mrs Smith", "HP22 7DG"), searchTerms("smith hp23")))
    }

    @Test
    fun `a postcode matches with or without its space`() {
        assertTrue(matches(listOf("HP22 7DG"), searchTerms("hp227dg")))
    }

    @Test
    fun `filterBy finds a name and a postcode together`() {
        val found = filterBy(records, "smith hp22") { listOf(it.name, it.address) }
        assertEquals(listOf("Mrs Smith"), found.map { it.name })
    }

    @Test
    fun `an empty query returns everything`() {
        assertEquals(records, filterBy(records, "") { listOf(it.name) })
    }

    @Test
    fun `a name typed without its apostrophe still matches`() {
        val found = filterBy(records, "obrien") { listOf(it.name, it.address) }
        assertEquals(listOf("O'Brien"), found.map { it.name })
    }
}
