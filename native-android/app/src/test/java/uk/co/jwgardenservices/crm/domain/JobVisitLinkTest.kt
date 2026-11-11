package uk.co.jwgardenservices.crm.domain

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JobVisitLinkTest {
    private val zone = ZoneId.of("Europe/London")

    private fun instant(local: String) = LocalDateTime.parse(local).atZone(zone).toInstant()

    private fun job(
        id: String,
        clientId: String = "c1",
        local: String = "2026-03-17T09:00:00",
        status: String = "scheduled",
        visitId: String? = null,
    ) = JobLink(id, clientId, instant(local), status, visitId)

    @Test
    fun `an early morning job stays on its local day`() {
        assertEquals("2026-03-17", localDay(instant("2026-03-17T08:00:00"), zone))
    }

    @Test
    fun `a logged visit matches the scheduled job that day`() {
        val matched = findJobForVisit(listOf(job("j1")), "c1", "2026-03-17")
        assertEquals("j1", matched?.id)
    }

    @Test
    fun `a done job is not matched`() {
        assertNull(findJobForVisit(listOf(job("j1", status = "done")), "c1", "2026-03-17"))
    }

    @Test
    fun `a job already linked to a visit is not matched again`() {
        assertNull(findJobForVisit(listOf(job("j1", visitId = "v1")), "c1", "2026-03-17"))
    }

    @Test
    fun `another client or another day does not match`() {
        assertNull(findJobForVisit(listOf(job("j1", clientId = "c2")), "c1", "2026-03-17"))
        assertNull(findJobForVisit(listOf(job("j1")), "c1", "2026-03-18"))
    }

    @Test
    fun `earliest job wins when a client has two on one day`() {
        val jobs = listOf(
            job("afternoon", local = "2026-03-17T13:00:00"),
            job("morning", local = "2026-03-17T09:00:00"),
        )
        assertEquals("morning", findJobForVisit(jobs, "c1", "2026-03-17")?.id)
    }

    @Test
    fun `the visit already logged for a job is found`() {
        val visits = listOf(VisitLink("v1", "c1", "2026-03-17"))
        assertEquals("v1", findVisitForJob(visits, "c1", instant("2026-03-17T09:00:00"), emptySet())?.id)
    }

    @Test
    fun `a visit already claimed by another job is not offered again`() {
        val visits = listOf(VisitLink("v1", "c1", "2026-03-17"))
        assertNull(findVisitForJob(visits, "c1", instant("2026-03-17T09:00:00"), setOf("v1")))
    }

    @Test
    fun `a visit for another client or day is not matched`() {
        val visits = listOf(VisitLink("v1", "c2", "2026-03-17"))
        assertNull(findVisitForJob(visits, "c1", instant("2026-03-17T09:00:00")))
    }
}
