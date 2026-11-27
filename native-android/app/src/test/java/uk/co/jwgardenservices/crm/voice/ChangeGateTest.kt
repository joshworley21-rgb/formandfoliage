package uk.co.jwgardenservices.crm.voice

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangeGateTest {
    private fun plain(reply: String?) = reply?.trimStart(TtsProsody.FAST, TtsProsody.SLOW)

    @Test
    fun `with confirmation off a change just runs`() = runTest {
        val gate = ChangeGate().apply { enabled = false }
        var saved = 0
        val reply = gate.commit("Log 4 hours for Smith") { saved++; "Logged." }
        assertEquals("Logged.", reply)
        assertEquals(1, saved)
        assertFalse(gate.isPending)
    }

    @Test
    fun `with confirmation on nothing is saved until the owner says yes`() = runTest {
        val gate = ChangeGate()
        var saved = 0
        val question = gate.commit("Log 4 hours for Smith today, £120") { saved++; "Logged." }
        assertEquals("Log 4 hours for Smith today, £120 — shall I go ahead?", plain(question))
        assertEquals(0, saved)
        assertTrue(gate.isPending)

        assertEquals("Logged.", gate.answer("yes please"))
        assertEquals(1, saved)
        assertFalse(gate.isPending)
        // A second yes has nothing to confirm and is not swallowed.
        assertNull(gate.answer("yes"))
        assertEquals(1, saved)
    }

    @Test
    fun `no drops the change`() = runTest {
        val gate = ChangeGate()
        var saved = 0
        gate.commit("Mark Smith done") { saved++; "Done." }
        val reply = gate.answer("no")
        assertEquals("Okay, I've left it. What should it be instead?", plain(reply))
        assertEquals(0, saved)
        assertFalse(gate.isPending)
    }

    @Test
    fun `a different command drops the change and is left for the caller`() = runTest {
        val gate = ChangeGate()
        var saved = 0
        gate.commit("Mark Smith done") { saved++; "Done." }
        assertNull(gate.answer("what's on tomorrow"))
        assertEquals(0, saved)
        assertFalse(gate.isPending)
    }

    @Test
    fun `a sentence that only contains yes does not approve the change`() = runTest {
        val gate = ChangeGate()
        var saved = 0
        gate.commit("Log 4 hours for Smith") { saved++; "Logged." }
        assertNull(gate.answer("yeah and also add a note for Jones"))
        assertEquals(0, saved)
    }

    @Test
    fun `noise asks again and keeps the change waiting`() = runTest {
        val gate = ChangeGate()
        var saved = 0
        gate.commit("Log 4 hours for Smith") { saved++; "Logged." }
        val again = gate.answer("um", isNoise = { it.trim() == "um" })
        assertEquals("Sorry, Log 4 hours for Smith. Shall I go ahead?", plain(again))
        assertTrue(gate.isPending)
        assertEquals("Logged.", gate.answer("yes"))
        assertEquals(1, saved)
    }

    @Test
    fun `a save nested inside an approved change does not ask again`() = runTest {
        val gate = ChangeGate()
        val saved = mutableListOf<String>()
        gate.commit("Log 4 hours for Smith and Jones") {
            listOf("Smith", "Jones").forEach { name ->
                gate.commit("Log 4 hours for $name") { saved += name; "ok" }
            }
            "Both logged."
        }
        assertTrue(saved.isEmpty())
        assertEquals("Both logged.", gate.answer("go ahead"))
        assertEquals(listOf("Smith", "Jones"), saved)
        // The exemption ends with the approved change.
        gate.commit("Another change") { "x" }
        assertTrue(gate.isPending)
    }

    @Test
    fun `a failed save is reported not swallowed`() = runTest {
        val gate = ChangeGate()
        gate.commit("Add a client called Larry") { error("the database is locked") }
        assertEquals("Sorry, that didn't save: the database is locked.", gate.answer("yes"))
        assertFalse(gate.isPending)
        // And the next change still asks.
        gate.commit("Something else") { "x" }
        assertTrue(gate.isPending)
    }

    @Test
    fun `a new change replaces one that was never answered`() = runTest {
        val gate = ChangeGate()
        var first = 0
        var second = 0
        gate.commit("First") { first++; "1" }
        gate.commit("Second") { second++; "2" }
        assertEquals("2", gate.answer("yes"))
        assertEquals(0, first)
        assertEquals(1, second)
    }

    @Test
    fun `clearing forgets the held change`() = runTest {
        val gate = ChangeGate()
        gate.commit("Mark Smith done") { "Done." }
        gate.clear()
        assertFalse(gate.isPending)
        assertNull(gate.answer("yes"))
    }
}
