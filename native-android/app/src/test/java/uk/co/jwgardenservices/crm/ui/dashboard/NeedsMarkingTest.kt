package uk.co.jwgardenservices.crm.ui.dashboard

import org.junit.Assert.assertEquals
import org.junit.Test
import uk.co.jwgardenservices.crm.data.model.ScheduledJob
import java.time.Instant

class NeedsMarkingTest {
    private val now = Instant.parse("2026-11-28T12:00:00Z")

    private fun job(id: String, starts: String, status: String = "scheduled", visitId: String? = null) =
        ScheduledJob(id = id, userId = "u", clientId = "c", startsAt = starts, status = status, visitId = visitId)

    @Test
    fun `only past, unmarked jobs from the last 30 days, oldest first`() {
        val jobs = listOf(
            job("recent", "2026-11-27T09:00:00Z"),
            job("older", "2026-11-10T09:00:00Z"),
            job("future", "2026-11-29T09:00:00Z"),
            job("done", "2026-11-26T09:00:00Z", status = "done"),
            job("logged", "2026-11-25T09:00:00Z", visitId = "v1"),
            job("stale", "2026-10-01T09:00:00Z"),
        )
        assertEquals(listOf("older", "recent"), jobsNeedingMarking(jobs, now).map { it.id })
    }
}
