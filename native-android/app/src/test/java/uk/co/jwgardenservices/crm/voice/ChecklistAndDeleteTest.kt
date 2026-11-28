package uk.co.jwgardenservices.crm.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ChecklistAndDeleteTest {
    @Test
    fun `a task is found in the ways it is said`() {
        assertEquals("Buy bark", VoiceSlots.taskTitle("add a task to Simon's job to buy bark", "Simon Hart"))
        assertEquals("Strim the edges", VoiceSlots.taskTitle("add a task for the Parkers: strim the edges", "Parkers"))
        assertEquals("Pick up compost", VoiceSlots.taskTitle("put pick up compost on the Parkers' checklist", "Parkers"))
        assertEquals("Take the ladder", VoiceSlots.taskTitle("add a to-do to Rose Cottage called take the ladder", "Rose Cottage"))
        assertNull(VoiceSlots.taskTitle("add a task to the Parkers job", "Parkers"))
    }

    @Test
    fun `tasks and deletes are recognised`() {
        assertEquals(SpokenIntent.AddJobTask, IntentRules.classify("add a task to Simon's job: buy bark").intent)
        assertEquals(SpokenIntent.AddJobTask, IntentRules.classify("put strim the edges on the Parkers checklist").intent)
        assertEquals(SpokenIntent.DeleteVisit, IntentRules.classify("delete the last visit for the Parkers").intent)
        assertEquals(SpokenIntent.DeleteVisit, IntentRules.classify("remove Simon's visit on Tuesday").intent)
    }

    @Test
    fun `nearby requests are not mistaken for them`() {
        assertNotEquals(SpokenIntent.DeleteVisit, IntentRules.classify("cancel the Parkers booking on Friday").intent)
        assertNotEquals(SpokenIntent.DeleteVisit, IntentRules.classify("when was the last visit for the Parkers").intent)
        assertNotEquals(SpokenIntent.AddJobTask, IntentRules.classify("add a note for the Parkers: gate sticks").intent)
    }

    @Test
    fun `the labeller can name them and they need supporting words`() {
        val clients = listOf("Parkers", "Simon Hart")
        assertEquals(SpokenIntent.AddJobTask, LocalBrain.parseIntent("add_job_task|Parkers", "tell the Parkers checklist to include bark", clients)?.intent)
        assertNull(LocalBrain.parseIntent("delete_visit|Parkers", "what are the Parkers up to", clients))
        assertEquals(SpokenIntent.DeleteVisit, LocalBrain.parseIntent("delete_visit|Parkers", "scrap the Parkers visit", clients)?.intent)
    }
}
