package uk.co.jwgardenservices.crm.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmModelTest {
    private val both = setOf(LlmModel.Small, LlmModel.Large)

    @Test
    fun `the bigger model leads when it is chosen, installed and memory allows`() {
        assertEquals(
            listOf(LlmModel.Large, LlmModel.Small),
            LlmModel.candidates(LlmModel.Large, both, availableMb = 4_000),
        )
    }

    @Test
    fun `short memory falls back to the small model`() {
        assertEquals(
            listOf(LlmModel.Small),
            LlmModel.candidates(LlmModel.Large, both, availableMb = 1_500),
        )
    }

    @Test
    fun `a chosen model that is not installed is skipped`() {
        assertEquals(
            listOf(LlmModel.Small),
            LlmModel.candidates(LlmModel.Large, setOf(LlmModel.Small), availableMb = 6_000),
        )
    }

    @Test
    fun `the small model alone is never listed twice`() {
        assertEquals(
            listOf(LlmModel.Small),
            LlmModel.candidates(LlmModel.Small, both, availableMb = 6_000),
        )
    }

    @Test
    fun `nothing is offered when nothing fits or is installed`() {
        assertTrue(LlmModel.candidates(LlmModel.Large, both, availableMb = 300).isEmpty())
        assertTrue(LlmModel.candidates(LlmModel.Small, emptySet(), availableMb = 8_000).isEmpty())
    }

    @Test
    fun `the memory threshold is inclusive`() {
        assertEquals(
            listOf(LlmModel.Large, LlmModel.Small),
            LlmModel.candidates(LlmModel.Large, both, availableMb = LlmModel.Large.minFreeMb),
        )
        assertEquals(
            listOf(LlmModel.Small),
            LlmModel.candidates(LlmModel.Large, both, availableMb = LlmModel.Large.minFreeMb - 1),
        )
    }

    @Test
    fun `ids round trip and unknown ids mean the small model`() {
        LlmModel.entries.forEach { assertEquals(it, LlmModel.fromId(it.id)) }
        assertEquals(LlmModel.Small, LlmModel.fromId(null))
        assertEquals(LlmModel.Small, LlmModel.fromId("something-else"))
    }

    @Test
    fun `models have distinct files so installing one never overwrites the other`() {
        assertEquals(LlmModel.entries.size, LlmModel.entries.map { it.fileName }.toSet().size)
    }
}
