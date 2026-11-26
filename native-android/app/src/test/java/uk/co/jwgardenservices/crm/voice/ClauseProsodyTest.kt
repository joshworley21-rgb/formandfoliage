package uk.co.jwgardenservices.crm.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class ClauseProsodyTest {
    @Test
    fun `short lead-ins are lifted`() {
        assertEquals(ClauseProsody.SHORT_LEAD_IN, ClauseProsody.speedMultiplier("Got it,"))
        assertEquals(ClauseProsody.SHORT_LEAD_IN, ClauseProsody.speedMultiplier("All logged —"))
        assertEquals(ClauseProsody.SHORT_LEAD_IN, ClauseProsody.speedMultiplier("Right."))
    }

    @Test
    fun `figures and dates are slowed`() {
        assertEquals(ClauseProsody.FIGURES, ClauseProsody.speedMultiplier("That comes to £180."))
        assertEquals(ClauseProsody.FIGURES, ClauseProsody.speedMultiplier("3 hours tomorrow"))
        assertEquals(ClauseProsody.FIGURES, ClauseProsody.speedMultiplier("at 14:30"))
        assertEquals(ClauseProsody.FIGURES, ClauseProsody.speedMultiplier("HP22 7DG"))
    }

    @Test
    fun `ordinary clauses stay neutral`() {
        assertEquals(
            ClauseProsody.STANDARD,
            ClauseProsody.speedMultiplier("the garden needs a tidy before the weekend"),
        )
    }
}
