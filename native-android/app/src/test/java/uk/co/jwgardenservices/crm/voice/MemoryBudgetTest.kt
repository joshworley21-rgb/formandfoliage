package uk.co.jwgardenservices.crm.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryBudgetTest {
    @Test
    fun `a load must leave the reserve free`() {
        assertTrue(MemoryBudget.canLoad(freeMb = 1500, needMb = 1200))
        assertFalse(MemoryBudget.canLoad(freeMb = 1400, needMb = 1200))
        assertTrue(MemoryBudget.canLoad(freeMb = 1200, needMb = 1200, reserveMb = 0))
    }

    @Test
    fun `memory pressure sheds optional models in order`() {
        assertEquals(MemoryBudget.Shed.Nothing, MemoryBudget.shedFor(0))
        assertEquals(MemoryBudget.Shed.SecondLook, MemoryBudget.shedFor(5))
        assertEquals(MemoryBudget.Shed.Router, MemoryBudget.shedFor(10))
        assertEquals(MemoryBudget.Shed.AllOptional, MemoryBudget.shedFor(15))
        assertEquals(MemoryBudget.Shed.Nothing, MemoryBudget.shedFor(20))
    }

    @Test
    fun `a turn with no reply says what it is doing, then gives up`() {
        assertEquals(TurnWatch.Step.Wait, TurnWatch.next(1_000, replyStarted = false, statusSpoken = false))
        assertEquals(TurnWatch.Step.Status, TurnWatch.next(4_000, false, false))
        assertEquals(TurnWatch.Step.Wait, TurnWatch.next(6_000, false, true))
        assertEquals(TurnWatch.Step.GiveUp, TurnWatch.next(15_000, false, true))
        assertEquals(TurnWatch.Step.GiveUp, TurnWatch.next(15_000, false, false))
    }

    @Test
    fun `once the reply is speaking the watch stands down`() {
        assertEquals(TurnWatch.Step.Wait, TurnWatch.next(60_000, replyStarted = true, statusSpoken = false))
    }
}
