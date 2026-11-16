package uk.co.jwgardenservices.crm.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The semantic fallback is the layer that catches British trade slang the
 * word-for-word rules miss. These cases are the contract: clear slang routes,
 * ambiguous or live-data turns do not.
 */
class SemanticMatcherTest {

    private fun intent(phrase: String): SpokenIntent? =
        SemanticMatcher.classify(phrase)?.first

    @Test
    fun `slang for logging hours routes locally`() {
        assertEquals(SpokenIntent.LogHours, intent("stick simon down for a half day"))
        assertEquals(SpokenIntent.LogHours, intent("chalk up four hours for the browns"))
        assertEquals(SpokenIntent.LogHours, intent("simon's all squared away, three hours"))
        assertEquals(SpokenIntent.LogHours, intent("log two hours"))
    }

    @Test
    fun `other phrasings of the common commands route`() {
        assertEquals(SpokenIntent.MarkJobDone, intent("smiths sorted"))
        assertEquals(SpokenIntent.WhoOwesMe, intent("anyone owing money"))
        assertEquals(SpokenIntent.CheckSchedule, intent("read me my diary"))
        assertEquals(SpokenIntent.NextJob, intent("what is coming up next"))
        assertEquals(SpokenIntent.TodayEarnings, intent("what have i taken today"))
        assertEquals(SpokenIntent.ClientList, intent("read out my clients"))
        assertEquals(
            SpokenIntent.NextJobForClient,
            intent("when is the smiths next in"),
        )
    }

    @Test
    fun `live data and quotes stay for the cloud`() {
        assertNull(intent("what's the weather tomorrow"))
        assertNull(intent("what's the traffic like"))
        assertNull(intent("price up a hedge trim for mrs jones"))
        assertNull(intent("how much for forty square metres of turf"))
    }

    @Test
    fun `nonsense and greetings do not route`() {
        assertNull(intent("tell me a joke"))
        assertNull(intent("hello"))
        assertNull(intent("what can you do"))
        assertNull(intent("how are we doing"))
    }

    @Test
    fun `a match reports a score`() {
        val match = SemanticMatcher.classify("stick simon down for a half day")
        assertNotNull(match)
        assertEquals(true, (match?.second ?: 0.0) > 0.9)
    }
}
