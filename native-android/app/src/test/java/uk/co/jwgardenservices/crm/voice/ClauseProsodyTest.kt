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

    @Test
    fun `the steady voice keeps only a share of each pace swing`() {
        assertEquals(1f, ClauseProsody.steady(1f), 0f)
        assertEquals(1f + (ClauseProsody.FIGURES - 1f) * ClauseProsody.STEADY_SHARE, ClauseProsody.steady(ClauseProsody.FIGURES), 1e-6f)
        // Pitch moves with pace, so the gap between a lead-in and a figure must shrink a lot.
        val full = ClauseProsody.SHORT_LEAD_IN - ClauseProsody.FIGURES
        val steady = ClauseProsody.steady(ClauseProsody.SHORT_LEAD_IN) - ClauseProsody.steady(ClauseProsody.FIGURES)
        assertEquals(true, steady < full * 0.4f)
    }
}
