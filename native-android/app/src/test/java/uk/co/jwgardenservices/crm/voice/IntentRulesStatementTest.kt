package uk.co.jwgardenservices.crm.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class IntentRulesStatementTest {
    @Test
    fun `reading something out is not a request to log hours`() {
        assertNotEquals(
            SpokenIntent.LogHours,
            IntentRules.classify("I can see there's three visits logs for Louisa and George").intent,
        )
        assertNotEquals(SpokenIntent.LogHours, IntentRules.classify("there are four hours logged for Simon").intent)
    }

    @Test
    fun `real log requests still log hours`() {
        assertEquals(SpokenIntent.LogHours, IntentRules.classify("log three hours for Simon").intent)
        assertEquals(SpokenIntent.LogHours, IntentRules.classify("put four hours down for the Parkers").intent)
    }
}
