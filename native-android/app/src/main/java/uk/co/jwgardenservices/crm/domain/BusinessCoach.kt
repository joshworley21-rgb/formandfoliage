package uk.co.jwgardenservices.crm.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToInt
import uk.co.jwgardenservices.crm.data.model.Client
import uk.co.jwgardenservices.crm.data.model.Invoice
import uk.co.jwgardenservices.crm.data.model.RunningCost
import uk.co.jwgardenservices.crm.data.model.ScheduledJob
import uk.co.jwgardenservices.crm.data.model.Visit

/**
 * A single month-shaped view of the business, built only from records already
 * on the phone. The coach is a set of rules over these figures, not a model
 * guessing at them.
 */
data class BusinessSnapshot(
    val monthInvoiced: Double,
    val monthPaid: Double,
    /** Value of work logged this month, whether invoiced yet or not. */
    val monthIncome: Double,
    val monthMaterials: Double,
    val monthLabour: Double,
    val monthlyCosts: Double,
    val netProfit: Double,
    val netMargin: Double,
    val outstanding: Double,
    val overdueTotal: Double,
    val overdueCount: Int,
    val unbilledTotal: Double,
    val unbilledCount: Int,
    val bookedHours: Double,
    val capacityPercent: Int,
    val topClientName: String?,
    val topClientShare: Double,
    val averageHourlyRate: Double,
    val lowestRateClient: Client?,
    val hasData: Boolean,
)

enum class CoachTone { Attention, Opportunity, Good }

enum class CoachAction { Invoices, Schedule, Costs, Clients, AddCost }

data class CoachInsight(
    val id: String,
    val tone: CoachTone,
    val area: String,
    val title: String,
    val body: String,
    val action: CoachAction? = null,
    val actionLabel: String? = null,
)

fun buildBusinessSnapshot(
    invoices: List<Invoice>,
    visits: List<Visit>,
    jobs: List<ScheduledJob>,
    clients: List<Client>,
    costs: List<RunningCost>,
    labourRate: Double = 0.0,
    today: LocalDate = LocalDate.now(),
): BusinessSnapshot {
    val month = YearMonth.from(today)
    val monthInvoices = invoices.filter {
        it.status != "draft" && parseDate(it.issueDate)?.let(YearMonth::from) == month
    }
    val monthPaidInvoices = invoices.filter {
        it.status == "paid" &&
            (parseTimestamp(it.paidAt)?.localDate() ?: parseDate(it.paidAt))
                ?.let(YearMonth::from) == month
    }
    val monthVisits = visits.filter {
        !it.missed && parseDate(it.visitDate)?.let(YearMonth::from) == month
    }
    val income = round2(monthVisits.sumOf { it.amount })
    val materials = round2(monthVisits.sumOf { it.materialsCost })
    val labour = round2(monthVisits.sumOf { it.hours } * labourRate.coerceAtLeast(0.0))
    val monthlyCosts = costs.monthlyRunningCosts()
    val net = round2(income - materials - labour - monthlyCosts)

    val outstandingInvoices = invoices.filter { it.status == "sent" }
    val outstanding = round2(outstandingInvoices.sumOf { it.total })
    val overdue = overdueInvoices(invoices, today)
    val unbilled = visits.filter { !it.billed && !it.missed }

    val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val weekEnd = weekStart.plusDays(6)
    val weekHours = jobs
        .filter { it.status != "cancelled" }
        .filter { job ->
            parseTimestamp(job.startsAt)?.localDate()?.let { it >= weekStart && it <= weekEnd } == true
        }
        .sumOf { it.durationMinutes / 60.0 }

    val since = today.minusDays(90)
    val recentVisits = visits.filter {
        !it.missed && parseDate(it.visitDate)?.let { date -> !date.isBefore(since) } == true
    }
    val recentByClient = recentVisits.groupBy { it.clientId }
        .mapValues { (_, rows) -> rows.sumOf { it.amount } }
    val recentTotal = recentByClient.values.sum()
    val top = recentByClient.maxByOrNull { it.value }

    val names = clients.associate { it.id to it.name }
    val hourlyClients = clients.filter {
        it.active && it.billingMode == "hourly" && it.hourlyRate > 0.0
    }
    val averageRate = if (hourlyClients.isEmpty()) 0.0 else {
        round2(hourlyClients.sumOf { it.hourlyRate } / hourlyClients.size)
    }
    val lowest = hourlyClients.minByOrNull { it.hourlyRate }

    return BusinessSnapshot(
        monthInvoiced = round2(monthInvoices.sumOf { it.total }),
        monthPaid = round2(monthPaidInvoices.sumOf { it.total }),
        monthIncome = income,
        monthMaterials = materials,
        monthLabour = labour,
        monthlyCosts = monthlyCosts,
        netProfit = net,
        netMargin = if (income > 0.0) net / income else 0.0,
        outstanding = outstanding,
        overdueTotal = round2(overdue.sumOf { it.total }),
        overdueCount = overdue.size,
        unbilledTotal = round2(unbilled.sumOf { it.amount }),
        unbilledCount = unbilled.size,
        bookedHours = weekHours,
        capacityPercent = ((weekHours / 40.0) * 100).roundToInt(),
        topClientName = top?.key?.let { names[it] ?: "Client" },
        topClientShare = if (recentTotal > 0.0) (top?.value ?: 0.0) / recentTotal else 0.0,
        averageHourlyRate = averageRate,
        lowestRateClient = lowest,
        hasData = invoices.isNotEmpty() || visits.isNotEmpty() || costs.isNotEmpty(),
    )
}

/**
 * 0–100. Cash collected is weighted highest because a profitable month that
 * has not been paid is not yet a good month.
 */
data class CoachScorePart(
    val label: String,
    val points: Int,
    val max: Int,
)

private fun scoreParts(snapshot: BusinessSnapshot): List<Pair<String, Pair<Double, Int>>> {
    val cash = if (snapshot.outstanding <= 0.0) {
        25.0
    } else {
        25.0 * (1.0 - (snapshot.overdueTotal / snapshot.outstanding)).coerceIn(0.0, 1.0)
    }

    val margin = 25.0 * (snapshot.netMargin / 0.35).coerceIn(0.0, 1.0)

    val costsShare = if (snapshot.monthIncome > 0.0) {
        (snapshot.monthlyCosts + snapshot.monthMaterials + snapshot.monthLabour) /
            snapshot.monthIncome
    } else if (snapshot.monthlyCosts > 0.0) {
        1.0
    } else {
        0.0
    }
    val costsScore = 20.0 * (1.0 - ((costsShare - 0.25) / 0.35).coerceIn(0.0, 1.0))

    val billable = snapshot.monthIncome + snapshot.unbilledTotal
    val billing = if (billable <= 0.0) {
        15.0
    } else {
        15.0 * (1.0 - (snapshot.unbilledTotal / billable).coerceIn(0.0, 1.0))
    }

    val capacity = when {
        snapshot.bookedHours <= 0.0 -> 4.0
        snapshot.capacityPercent in 60..110 -> 15.0
        snapshot.capacityPercent < 60 -> 15.0 * snapshot.capacityPercent / 60.0
        else -> 15.0 * (1.0 - ((snapshot.capacityPercent - 110) / 100.0).coerceIn(0.0, 0.5))
    }

    return listOf(
        "Cash collected" to (cash to 25),
        "Margin" to (margin to 25),
        "Cost control" to (costsScore to 20),
        "Billing" to (billing to 15),
        "Capacity" to (capacity to 15),
    )
}

fun coachScoreParts(snapshot: BusinessSnapshot): List<CoachScorePart> {
    if (!snapshot.hasData) return emptyList()
    return scoreParts(snapshot).map { (label, pair) ->
        CoachScorePart(
            label = label,
            points = pair.first.roundToInt().coerceIn(0, pair.second),
            max = pair.second,
        )
    }
}

fun coachScore(snapshot: BusinessSnapshot): Int {
    if (!snapshot.hasData) return 0
    return coachScoreParts(snapshot).sumOf { it.points }.coerceIn(0, 100)
}

fun scoreLabel(score: Int): String = when {
    score >= 80 -> "Strong"
    score >= 60 -> "Steady"
    score >= 40 -> "Needs work"
    else -> "At risk"
}

fun coachInsights(
    snapshot: BusinessSnapshot,
    costs: List<RunningCost>,
    clients: List<Client>,
): List<CoachInsight> {
    val insights = mutableListOf<CoachInsight>()

    if (snapshot.overdueCount > 0) {
        insights += CoachInsight(
            id = "overdue",
            tone = CoachTone.Attention,
            area = "Cash flow",
            title = "Chase ${money(snapshot.overdueTotal)} in late invoices",
            body = "${snapshot.overdueCount} ${if (snapshot.overdueCount == 1) "invoice is" else "invoices are"} " +
                "past their due date. The oldest is the one that changes the month.",
            action = CoachAction.Invoices,
            actionLabel = "Review invoices",
        )
    }

    if (snapshot.unbilledTotal > 0.0) {
        insights += CoachInsight(
            id = "unbilled",
            tone = CoachTone.Opportunity,
            area = "Billing",
            title = "${money(snapshot.unbilledTotal)} of work is ready to invoice",
            body = "${snapshot.unbilledCount} ${if (snapshot.unbilledCount == 1) "visit is" else "visits are"} " +
                "logged but not billed. Raising them now is the quickest cash in the business.",
            action = CoachAction.Invoices,
            actionLabel = "Raise invoices",
        )
    }

    if (snapshot.monthIncome <= 0.0 && snapshot.monthlyCosts > 0.0) {
        insights += CoachInsight(
            id = "no-income",
            tone = CoachTone.Attention,
            area = "Profit",
            title = "No income logged this month",
            body = "Running costs still apply. Log completed visits so the month " +
                "does not look worse than it is.",
            action = CoachAction.Schedule,
            actionLabel = "Open rota",
        )
    } else if (snapshot.netProfit < 0.0) {
        insights += CoachInsight(
            id = "loss",
            tone = CoachTone.Attention,
            area = "Profit",
            title = "This month is ${money(kotlin.math.abs(snapshot.netProfit))} down",
            body = "Income ${money(snapshot.monthIncome)} less materials, labour and running " +
                "costs. Check the cost breakdown before the month closes.",
            action = CoachAction.Costs,
            actionLabel = "See costs",
        )
    } else if (snapshot.monthIncome > 0.0 && snapshot.netMargin < 0.15) {
        insights += CoachInsight(
            id = "thin-margin",
            tone = CoachTone.Opportunity,
            area = "Profit",
            title = "Margin is ${(snapshot.netMargin * 100).roundToInt()}%",
            body = "${money(snapshot.netProfit)} is left from ${money(snapshot.monthIncome)}. " +
                "A small price rise or a cheaper recurring cost moves this fastest.",
            action = CoachAction.Costs,
            actionLabel = "Review costs",
        )
    } else if (snapshot.monthIncome > 0.0) {
        insights += CoachInsight(
            id = "healthy-margin",
            tone = CoachTone.Good,
            area = "Profit",
            title = "${(snapshot.netMargin * 100).roundToInt()}% left after costs",
            body = "${money(snapshot.netProfit)} from ${money(snapshot.monthIncome)} of logged work.",
        )
    }

    if (costs.isEmpty()) {
        insights += CoachInsight(
            id = "no-costs",
            tone = CoachTone.Opportunity,
            area = "Running costs",
            title = "Add what the business costs to run",
            body = "Van, fuel, insurance and tools turn income into profit. " +
                "Add them once and the coach can tell you what is really left.",
            action = CoachAction.AddCost,
            actionLabel = "Add first cost",
        )
    } else if (snapshot.monthIncome > 0.0) {
        val share = (snapshot.monthlyCosts + snapshot.monthMaterials + snapshot.monthLabour) /
            snapshot.monthIncome
        if (share > 0.5) {
            insights += CoachInsight(
                id = "cost-share",
                tone = CoachTone.Attention,
                area = "Running costs",
                title = "Costs take ${(share * 100).roundToInt()}% of income",
                body = "${money(snapshot.monthlyCosts)} a month before materials. " +
                    "Look for the largest category in the breakdown.",
                action = CoachAction.Costs,
                actionLabel = "Open costs",
            )
        } else if (share < 0.25) {
            insights += CoachInsight(
                id = "lean-costs",
                tone = CoachTone.Good,
                area = "Running costs",
                title = "Costs are ${(share * 100).roundToInt()}% of income",
                body = "Lean for the work being done. Keep the cost list current " +
                    "so the margin stays honest.",
                action = CoachAction.Costs,
                actionLabel = "See costs",
            )
        }
    }

    if (snapshot.bookedHours > 0.0 && snapshot.capacityPercent < 50) {
        insights += CoachInsight(
            id = "spare-capacity",
            tone = CoachTone.Opportunity,
            area = "Capacity",
            title = "The week is only ${snapshot.capacityPercent}% booked",
            body = "${hoursLabel(snapshot.bookedHours)} on the rota out of a 40-hour week. " +
                "There is room for another regular round.",
            action = CoachAction.Schedule,
            actionLabel = "Open rota",
        )
    } else if (snapshot.capacityPercent > 110) {
        insights += CoachInsight(
            id = "over-capacity",
            tone = CoachTone.Opportunity,
            area = "Capacity",
            title = "Booked ${snapshot.capacityPercent}% of a 40-hour week",
            body = "${hoursLabel(snapshot.bookedHours)} scheduled. Busy enough to " +
                "review prices or move the least profitable visits.",
            action = CoachAction.Costs,
            actionLabel = "Check costs",
        )
    }

    if (snapshot.topClientShare > 0.4 && snapshot.topClientName != null) {
        insights += CoachInsight(
            id = "client-mix",
            tone = CoachTone.Opportunity,
            area = "Client mix",
            title = "${snapshot.topClientName} is ${(snapshot.topClientShare * 100).roundToInt()}% of recent work",
            body = "A single client carrying this much is a risk if they pause. " +
                "One more regular round would spread it.",
            action = CoachAction.Clients,
            actionLabel = "See clients",
        )
    }

    val lowest = snapshot.lowestRateClient
    if (lowest != null && snapshot.averageHourlyRate > 0.0) {
        val gap = 1.0 - (lowest.hourlyRate / snapshot.averageHourlyRate)
        if (gap >= 0.2) {
            insights += CoachInsight(
                id = "lowest-rate",
                tone = CoachTone.Opportunity,
                area = "Pricing",
                title = "${lowest.name} is on ${money(lowest.hourlyRate)}/hr",
                body = "Your average hourly client is ${money(snapshot.averageHourlyRate)}. " +
                    "Worth checking whether that rate is deliberate.",
                action = CoachAction.Clients,
                actionLabel = "Review rates",
            )
        }
    }

    if (clients.count { it.active } > 0 && insights.none { it.tone == CoachTone.Good }) {
        insights += CoachInsight(
            id = "baseline",
            tone = CoachTone.Good,
            area = "Baseline",
            title = "The basics are in place",
            body = "${clients.count { it.active }} active ${if (clients.count { it.active } == 1) "client" else "clients"} " +
                "on the books. Add costs and log visits to sharpen the picture.",
        )
    }

    return insights.sortedWith(
        compareBy<CoachInsight> { it.tone.ordinal }.thenBy { it.id },
    )
}
