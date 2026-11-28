package uk.co.jwgardenservices.crm.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmModelTest {
    private val both = setOf(LlmModel.Small, LlmModel.Large)

    @Test
    fun `the wanted model comes first, with the small one behind it`() {
        assertEquals(
            listOf(LlmModel.Large, LlmModel.Small),
            LlmModel.candidates(LlmModel.Large, both, availableMb = 4_000),
        )
        assertEquals(
            listOf(LlmModel.Small, LlmModel.Large),
            LlmModel.candidates(LlmModel.Small, both, availableMb = 4_000),
        )
    }

    @Test
    fun `a model that does not fit the free memory is skipped`() {
        assertEquals(
            listOf(LlmModel.Small),
            LlmModel.candidates(LlmModel.Large, both, availableMb = LlmModel.Large.minFreeMb - 1),
        )
        assertTrue(LlmModel.candidates(LlmModel.Large, both, availableMb = 300).isEmpty())
    }

    @Test
    fun `a missing model is skipped and the other one stands in`() {
        assertEquals(
            listOf(LlmModel.Small),
            LlmModel.candidates(LlmModel.Large, setOf(LlmModel.Small), availableMb = 6_000),
        )
        assertEquals(
            listOf(LlmModel.Large),
            LlmModel.candidates(LlmModel.Small, setOf(LlmModel.Large), availableMb = 6_000),
        )
        assertTrue(LlmModel.candidates(LlmModel.Small, emptySet(), availableMb = 8_000).isEmpty())
    }

    @Test
    fun `an unknown id means the small model`() {
        assertEquals(LlmModel.Small, LlmModel.fromId(null))
        assertEquals(LlmModel.Small, LlmModel.fromId("gemma3-1b"))
        assertEquals(LlmModel.Large, LlmModel.fromId("qwen3-1.7b"))
    }

    @Test
    fun `only the clever model takes the short prompt and reasons by default`() {
        assertEquals(false, LlmModel.Small.leanPrompts)
        assertEquals(true, LlmModel.Large.leanPrompts)
        assertEquals(true, LlmModel.Large.reasonsByDefault)
        assertEquals(347_251_840L, LlmModel.Small.bytes)
        assertEquals(977_184_032L, LlmModel.Large.bytes)
    }
}
