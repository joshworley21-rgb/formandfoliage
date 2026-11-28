package uk.co.jwgardenservices.crm.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeChoicesTest {
    @Test
    fun `times run every quarter hour and keep an odd one in order`() {
        val standard = TimeChoices.timeOptions("09:00")
        assertEquals(96, standard.size)
        assertEquals("00:00", standard.first())
        assertEquals("23:45", standard.last())
        val odd = TimeChoices.timeOptions("09:10")
        assertEquals(97, odd.size)
        assertEquals(listOf("09:00", "09:10", "09:15"), odd.subList(36, 39))
        assertEquals(96, TimeChoices.timeOptions("nonsense").size)
    }

    @Test
    fun `hours run in quarters and keep an odd figure`() {
        val steps = TimeChoices.hourOptions("")
        assertEquals("0.25", steps.first())
        assertEquals("12", steps.last())
        assertTrue("2.5" in steps)
        assertEquals(48, steps.size)
        val odd = TimeChoices.hourOptions("2.3")
        assertEquals(49, odd.size)
        assertTrue("2.3" in odd)
        assertEquals(48, TimeChoices.hourOptions("3").size)
    }

    @Test
    fun `hours read naturally`() {
        assertEquals("1 hour", TimeChoices.hoursLabel("1"))
        assertEquals("2½ hours", TimeChoices.hoursLabel("2.5"))
        assertEquals("½ hour", TimeChoices.hoursLabel("0.5"))
        assertEquals("1h 45m", TimeChoices.hoursLabel("1.75"))
        assertEquals("15m", TimeChoices.hoursLabel("0.25"))
        assertEquals("4 hours", TimeChoices.hoursLabel("4"))
        assertEquals("2.5", TimeChoices.hoursText(2.5))
        assertEquals("2", TimeChoices.hoursText(2.0))
    }
}
