package uk.co.jwgardenservices.crm.domain

import java.time.LocalDate
import uk.co.jwgardenservices.crm.data.model.Client
import uk.co.jwgardenservices.crm.data.model.ScheduledJob
import uk.co.jwgardenservices.crm.data.model.Visit

/**
 * The Rota's weekly money, built from what actually happened plus what is
 * still booked.
 *
 * A logged visit is the source of truth when it exists. A scheduled job with
 * no visit yet is valued from the client's own rate (hourly duration or fixed
 * price), so a week with bookings no longer reads £0 while the diary is full.
 */
data class RotaWeekTotals(
    val income: Double,
    val labour: Double,
    val overheads: Double,
    val net: Double,
    val materials: Double,
    val hours: Double,
    val visitIncome: Double,
    val scheduledIncome: Double,
)

fun rotaWeekTotals(
    weekStart: LocalDate,
    weekEnd: LocalDate,
    visits: List<Visit>,
    jobs: List<ScheduledJob>,
    clients: List<Client>,
    labourRate: Double,
    weeklyOverheads: Double,
): RotaWeekTotals {
    val inWeek = { date: LocalDate -> !date.isBefore(weekStart) && !date.isAfter(weekEnd) }
    val clientsById = clients.associateBy { it.id }

    val weekVisits = visits.filter { visit ->
        !visit.missed && parseDate(visit.visitDate)?.let(inWeek) == true
    }
    val visitsById = weekVisits.associateBy { it.id }
    val visitIncome = weekVisits.sumOf { it.amount }
    val materials = weekVisits.sumOf { it.materialsCost }
    val visitHours = weekVisits.sumOf { it.hours }

    val weekJobs = jobs.filter { job ->
        job.status != "cancelled" &&
            parseTimestamp(job.startsAt)?.localDate()?.let(inWeek) == true
    }
    var scheduledIncome = 0.0
    var scheduledHours = 0.0
    for (job in weekJobs) {
        if (job.status == "missed") continue
        val jobDate = parseTimestamp(job.startsAt)?.localDate()
        val linkedVisit = job.visitId?.let { visitsById[it] }
            ?: if (job.status == "done") {
                weekVisits.firstOrNull {
                    it.clientId == job.clientId && parseDate(it.visitDate) == jobDate
                }
            } else {
                null
            }
        if (linkedVisit != null) continue
        val durationHours = job.durationMinutes / 60.0
        scheduledHours += durationHours
        val client = clientsById[job.clientId]
        scheduledIncome += when {
            client == null -> 0.0
            client.billingMode.equals("fixed", ignoreCase = true) -> client.fixedPrice
            else -> client.hourlyRate * durationHours
        }
    }

    val income = round2(visitIncome + scheduledIncome)
    val hours = visitHours + scheduledHours
    val labour = round2(hours * labourRate)
    val overheads = round2(weeklyOverheads + materials)
    val net = round2(income - labour - overheads)
    return RotaWeekTotals(
        income = income,
        labour = labour,
        overheads = overheads,
        net = net,
        materials = round2(materials),
        hours = hours,
        visitIncome = round2(visitIncome),
        scheduledIncome = round2(scheduledIncome),
    )
}
