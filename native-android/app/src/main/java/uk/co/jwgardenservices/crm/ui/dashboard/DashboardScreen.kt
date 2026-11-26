package uk.co.jwgardenservices.crm.ui.dashboard

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.abs
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters
import uk.co.jwgardenservices.crm.core.TaxReservePrefs
import uk.co.jwgardenservices.crm.data.model.Invoice
import uk.co.jwgardenservices.crm.data.model.ScheduledJob
import uk.co.jwgardenservices.crm.data.model.Settings
import uk.co.jwgardenservices.crm.data.model.Visit
import uk.co.jwgardenservices.crm.domain.SelfAssessment
import uk.co.jwgardenservices.crm.domain.buildReminderSummary
import uk.co.jwgardenservices.crm.domain.daysOverdue
import uk.co.jwgardenservices.crm.domain.estimateSelfAssessmentTax
import uk.co.jwgardenservices.crm.domain.estimatedTaxYearProfit
import uk.co.jwgardenservices.crm.domain.formatDayMonth
import uk.co.jwgardenservices.crm.domain.formatTime
import uk.co.jwgardenservices.crm.domain.formatWeekday
import uk.co.jwgardenservices.crm.domain.hoursLabel
import uk.co.jwgardenservices.crm.domain.localDate
import uk.co.jwgardenservices.crm.domain.money
import uk.co.jwgardenservices.crm.domain.moneyCompact
import uk.co.jwgardenservices.crm.domain.monthlyRunningCosts
import uk.co.jwgardenservices.crm.domain.overdueInvoices
import uk.co.jwgardenservices.crm.domain.parseDate
import uk.co.jwgardenservices.crm.domain.parseTimestamp
import uk.co.jwgardenservices.crm.domain.taxSetAside
import uk.co.jwgardenservices.crm.domain.ukTaxYearContaining
import uk.co.jwgardenservices.crm.domain.ukTaxYearLabel
import uk.co.jwgardenservices.crm.ui.CrmViewModel
import uk.co.jwgardenservices.crm.ui.common.ErrorState
import uk.co.jwgardenservices.crm.ui.common.HeaderIconAction
import uk.co.jwgardenservices.crm.ui.common.LoadingState
import uk.co.jwgardenservices.crm.ui.common.ScreenHeader
import uk.co.jwgardenservices.crm.ui.common.SectionCard
import uk.co.jwgardenservices.crm.ui.common.SegmentedPills
import uk.co.jwgardenservices.crm.ui.common.StatTile
import uk.co.jwgardenservices.crm.ui.onDay
import uk.co.jwgardenservices.crm.ui.theme.JwTheme
import uk.co.jwgardenservices.crm.ui.theme.JwTokens
import uk.co.jwgardenservices.crm.voice.VoiceLines

private enum class HomeTab(val label: String) {
    Week("This week"),
    Today("Today"),
    Cash("Cash flow"),
}

@Composable
fun DashboardScreen(
    viewModel: CrmViewModel,
    onOpenSchedule: () -> Unit,
    onOpenInvoices: () -> Unit,
    onOpenSecretary: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val today = LocalDate.now()
    val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val weekEnd = weekStart.plusDays(6)
    val lastWeekStart = weekStart.minusDays(7)
    val lastWeekEnd = weekStart.minusDays(1)
    val month = YearMonth.from(today)
    val taxRate by TaxReservePrefs.ratePercent.collectAsStateWithLifecycle()
    val labourRate = state.settings?.labourRate ?: 0.0
    var tab by remember { mutableStateOf(HomeTab.Week) }
    var projected by remember { mutableStateOf(false) }
    var statDrag by remember { mutableStateOf(0f) }

    val clientNames = remember(state.clients) { state.clients.associate { it.id to it.name } }
    val weekVisits = state.visits.filter { visit ->
        !visit.missed && parseDate(visit.visitDate)?.let { !it.isBefore(weekStart) && !it.isAfter(weekEnd) } == true
    }
    val lastWeekVisits = state.visits.filter { visit ->
        !visit.missed && parseDate(visit.visitDate)?.let {
            !it.isBefore(lastWeekStart) && !it.isAfter(lastWeekEnd)
        } == true
    }
    val monthVisits = state.visits.filter { visit ->
        !visit.missed && parseDate(visit.visitDate)?.let { YearMonth.from(it) == month } == true
    }
    val weekIncome = weekVisits.sumOf { it.amount }
    val weekMaterials = weekVisits.sumOf { it.materialsCost }
    val weekLabour = weekVisits.sumOf { it.hours } * labourRate
    val weekOverheads = state.runningCosts.monthlyRunningCosts() * 12.0 / 52.0
    val lastWeekNet = lastWeekVisits.sumOf { it.amount } -
        lastWeekVisits.sumOf { it.materialsCost } -
        lastWeekVisits.sumOf { it.hours } * labourRate - weekOverheads
    val weekNet = weekIncome - weekMaterials - weekLabour - weekOverheads
    val monthIncome = monthVisits.sumOf { it.amount }
    val weekProjected = weekIncome / today.dayOfWeek.value.coerceAtLeast(1) * 7
    val monthProjected = monthIncome / today.dayOfMonth.coerceAtLeast(1) *
        month.lengthOfMonth()

    val scheduled = state.jobs.filter { it.status == "scheduled" }
    val weekJobs = scheduled.filter { job ->
        parseTimestamp(job.startsAt)?.localDate()?.let {
            !it.isBefore(weekStart) && !it.isAfter(weekEnd)
        } == true
    }
    val todayJobs = scheduled.onDay(today)
    val weekHours = weekJobs.sumOf { it.durationMinutes / 60.0 }
    val capacity = ((weekHours / 40.0) * 100).toInt()

    val outstanding = state.invoices.filter { it.status == "sent" }
        .sumOf { it.total }
    val overdue = remember(state.invoices, today) { overdueInvoices(state.invoices, today) }
    val unbilled = state.visits.filter { !it.billed && !it.missed }
    val unbilledTotal = unbilled.sumOf { it.amount }
    val reminders = remember(state.jobs, state.invoices, state.visits, today) {
        buildReminderSummary(state.jobs, state.invoices, state.visits, today)
    }

    val series = (0..6).map { offset ->
        val day = weekStart.plusDays(offset.toLong())
        val visits = weekVisits.filter { parseDate(it.visitDate) == day }
        DaySeries(
            day = day,
            revenue = visits.sumOf { it.amount },
            labour = visits.sumOf { it.hours } * labourRate,
        )
    }
    val busiest = series.maxByOrNull { it.revenue }
    val topClient = weekVisits
        .groupBy { it.clientId }
        .mapValues { (_, visits) -> visits.sumOf { it.amount } }
        .maxByOrNull { it.value }

    if (state.loading) {
        Column(modifier = Modifier.fillMaxSize()) {
            ScreenHeader(title = "Home")
            LoadingState("Loading your workspace…", Modifier.padding(horizontal = JwTokens.screenPadding))
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 22.dp),
        ) {
                ScreenHeader(
                    title = "${when (LocalTime.now().hour) {
                        in 0..11 -> "Morning"
                        in 12..17 -> "Afternoon"
                        else -> "Evening"
                    }}, ${VoiceLines.OWNER_NAME}",
                    subtitle = today.formatWeekday(),
                    footerPadding = 126.dp,
                    largeTitle = true,
                    topActions = {
                        HeaderIconAction(
                            icon = Icons.Outlined.NotificationsNone,
                            contentDescription = "Reminders",
                            onClick = onOpenInvoices,
                        )
                        Spacer(Modifier.width(8.dp))
                        HeaderIconAction(
                            icon = Icons.Outlined.Mic,
                            contentDescription = "Call Secretary",
                            onClick = onOpenSecretary,
                        )
                        Spacer(Modifier.width(8.dp))
                        HeaderIconAction(
                            icon = Icons.Outlined.Refresh,
                            contentDescription = "Refresh",
                            onClick = viewModel::refresh,
                        )
                    },
                )
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .offset(y = 4.dp)
                        .fillMaxWidth()
                        .padding(horizontal = JwTokens.screenPadding)
                        .pointerInput(Unit) {
                            detectHorizontalDragGestures(
                                onDragStart = { statDrag = 0f },
                                onDragEnd = {
                                    if (abs(statDrag) > 36f) projected = !projected
                                    statDrag = 0f
                                },
                                onHorizontalDrag = { _, amount -> statDrag += amount },
                            )
                        },
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    HomeStat(
                        label = "THIS WEEK",
                        value = moneyCompact(weekIncome),
                        projectedValue = moneyCompact(weekProjected),
                        projected = projected,
                        detail = "${weekVisits.size} ${if (weekVisits.size == 1) "visit" else "visits"} · so far",
                        hint = if (projected) "Projected to Sunday" else "Swipe to see projected →",
                        alt = false,
                        modifier = Modifier
                            .weight(1f)
                            .height(126.dp),
                    )
                    HomeStat(
                        label = "THIS MONTH",
                        value = moneyCompact(monthIncome),
                        projectedValue = moneyCompact(monthProjected),
                        projected = projected,
                        detail = "${monthVisits.size} ${if (monthVisits.size == 1) "visit" else "visits"} · so far",
                        hint = if (projected) "Projected month end" else null,
                        alt = true,
                        modifier = Modifier
                            .weight(1f)
                            .height(126.dp),
                    )
                }
        }

        ReminderPill(
            reminders = reminders,
            unbilledTotal = unbilledTotal,
            onClick = onOpenInvoices,
            modifier = Modifier
                .padding(horizontal = JwTokens.screenPadding)
                .padding(bottom = 12.dp),
        )

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(
                start = JwTokens.screenPadding,
                end = JwTokens.screenPadding,
                bottom = 48.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
        if (state.error != null) {
            item {
                ErrorState(state.error!!, viewModel::refresh)
            }
        }

        item {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                shape = RoundedCornerShape(JwTokens.cardRadius),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(JwTokens.hairline, MaterialTheme.colorScheme.outline),
            ) {
                Column {
                    SegmentedPills(
                        options = HomeTab.entries,
                        selected = tab,
                        onSelect = { tab = it },
                        label = { it.label },
                        modifier = Modifier.padding(10.dp),
                    )
                    when (tab) {
                        HomeTab.Week -> WeekPanel(
                            weekIncome = weekIncome,
                            weekMaterials = weekMaterials,
                            weekLabour = weekLabour,
                            weekOverheads = weekOverheads,
                            weekNet = weekNet,
                            lastWeekNet = lastWeekNet,
                            series = series,
                            today = today,
                            busiest = busiest,
                            topClientName = topClient?.key?.let { clientNames[it] ?: "Client" },
                            topClientValue = topClient?.value ?: 0.0,
                            capacity = capacity,
                            weekHours = weekHours,
                        )
                        HomeTab.Today -> TodayPanel(
                            jobs = todayJobs,
                            clientNames = clientNames,
                            overdue = overdue,
                            clientName = { id -> clientNames[id] ?: "Client" },
                            onOpenSchedule = onOpenSchedule,
                        )
                        HomeTab.Cash -> CashPanel(
                            outstanding = outstanding,
                            overdue = overdue,
                            unbilledTotal = unbilledTotal,
                            unpaid = state.invoices.filter { it.status == "sent" },
                            clientName = { id -> clientNames[id] ?: "Client" },
                            today = today,
                            onOpenInvoices = onOpenInvoices,
                        )
                    }
                }
            }
        }

        item {
            TaxPotBand(
                ratePercent = taxRate,
                invoices = state.invoices,
                visits = state.visits,
                today = today,
            )
        }

        item {
            Text(
                text = "Business holds your running costs, profit and the coach.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
        }
    }
}

@Composable
private fun HomeStat(
    label: String,
    value: String,
    projectedValue: String,
    projected: Boolean,
    detail: String,
    hint: String?,
    alt: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val palette = JwTheme.colors
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(JwTokens.controlRadius),
        color = if (alt) palette.kpiCardAlt else palette.kpiCard,
        contentColor = palette.onForestMuted,
        border = BorderStroke(1.dp, palette.kpiBorder),
        shadowElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(14.dp),
        ) {
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = palette.onForestMuted.copy(alpha = 0.62f),
                maxLines = 1,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = if (projected) projectedValue else value,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = palette.brass,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = palette.onForestMuted.copy(alpha = 0.78f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.weight(1f))
            if (hint != null) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontSize = 9.sp,
                        lineHeight = 11.sp,
                        letterSpacing = (-0.15).sp,
                    ),
                    fontWeight = FontWeight.Medium,
                    color = palette.onForestMuted.copy(alpha = 0.9f),
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(4.dp)
                        .background(
                            if (!projected) palette.onForestMuted else {
                                palette.onForestMuted.copy(alpha = 0.28f)
                            },
                            CircleShape,
                        ),
                )
                Spacer(Modifier.width(4.dp))
                Box(
                    modifier = Modifier
                        .size(4.dp)
                        .background(
                            if (projected) palette.onForestMuted else {
                                palette.onForestMuted.copy(alpha = 0.28f)
                            },
                            CircleShape,
                        ),
                )
            }
        }
    }
}

@Composable
private fun ReminderPill(
    reminders: uk.co.jwgardenservices.crm.domain.ReminderSummary,
    unbilledTotal: Double,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = JwTheme.colors
    val text = when {
        reminders.overdue.isNotEmpty() ->
            "${reminders.overdue.size} ${if (reminders.overdue.size == 1) "invoice" else "invoices"} overdue"
        unbilledTotal > 0.0 -> "${money(unbilledTotal)} ready to invoice"
        reminders.tomorrowJobs.isNotEmpty() ->
            "${reminders.tomorrowJobs.size} booked tomorrow"
        else -> "No reminders today"
    }
    val actionLabel = if (reminders.overdue.isEmpty() && unbilledTotal <= 0.0 &&
        reminders.tomorrowJobs.isEmpty()
    ) {
        "+ Add"
    } else {
        "Review"
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(JwTokens.chipRadius),
        color = MaterialTheme.colorScheme.secondary,
        border = BorderStroke(JwTokens.hairline, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.PushPin,
                contentDescription = null,
                modifier = Modifier.size(17.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Surface(
                shape = RoundedCornerShape(JwTokens.chipRadius),
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Text(
                    text = actionLabel,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                )
            }
        }
    }
}

private data class DaySeries(val day: LocalDate, val revenue: Double, val labour: Double)

@Composable
private fun WeekPanel(
    weekIncome: Double,
    weekMaterials: Double,
    weekLabour: Double,
    weekOverheads: Double,
    weekNet: Double,
    lastWeekNet: Double,
    series: List<DaySeries>,
    today: LocalDate,
    busiest: DaySeries?,
    topClientName: String?,
    topClientValue: Double,
    capacity: Int,
    weekHours: Double,
) {
    val delta = weekNet - lastWeekNet
    Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (delta < 0) "↓" else "↑",
                style = MaterialTheme.typography.titleMedium,
                color = if (delta < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = "${money(kotlin.math.abs(delta))} net",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (delta < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
            Text(
                text = " vs last week",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = "THIS WEEK, DAY BY DAY",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        WeekBars(series = series, today = today)
        Spacer(Modifier.height(14.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        SummaryRow("Busiest day", if ((busiest?.revenue ?: 0.0) > 0.0) {
            "${busiest!!.day.formatWeekday().substringBefore(" ")} · ${money(busiest.revenue)}"
        } else {
            "—"
        })
        SummaryRow("Top client", if (topClientName != null) "$topClientName · ${money(topClientValue)}" else "—")
        SummaryRow("Capacity", "$capacity% · ${hoursLabel(weekHours)} of 40h")
        Spacer(Modifier.height(8.dp))
        Text(
            text = "PROFIT & LOSS",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SummaryRow("Income", money(weekIncome))
        SummaryRow("Labour", money(weekLabour))
        SummaryRow("Materials", money(weekMaterials))
        SummaryRow("Overheads", money(weekOverheads))
        SummaryRow("Net profit", money(weekNet), emphasis = true)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun WeekBars(series: List<DaySeries>, today: LocalDate) {
    val max = maxOf(1.0, series.maxOfOrNull { maxOf(it.revenue, it.labour) } ?: 1.0)
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            series.forEach { day ->
                val isToday = day.day == today
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .background(
                            if (isToday) MaterialTheme.colorScheme.secondary else Color.Transparent,
                            RoundedCornerShape(JwTokens.tinyRadius),
                        )
                        .padding(vertical = 5.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(
                        modifier = Modifier.height(78.dp),
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        Box(
                            modifier = Modifier
                                .width(7.dp)
                                .height((78 * (day.revenue / max)).dp.coerceAtLeast(3.dp))
                                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)),
                        )
                        Box(
                            modifier = Modifier
                                .width(7.dp)
                                .height((78 * (day.labour / max)).dp.coerceAtLeast(3.dp))
                                .background(
                                    MaterialTheme.colorScheme.outline,
                                    RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp),
                                ),
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = day.day.formatWeekday().take(3),
                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                        color = if (isToday) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Legend(MaterialTheme.colorScheme.primary, "Revenue")
            Legend(MaterialTheme.colorScheme.outline, "Labour")
        }
    }
}

@Composable
private fun Legend(colour: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(colour, RoundedCornerShape(JwTokens.microRadius)),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SummaryRow(label: String, value: String, emphasis: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (emphasis) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun TodayPanel(
    jobs: List<ScheduledJob>,
    clientNames: Map<String, String>,
    overdue: List<Invoice>,
    clientName: (String) -> String,
    onOpenSchedule: () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        if (overdue.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(JwTokens.controlRadius),
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "${overdue.size} ${if (overdue.size == 1) "invoice is" else "invoices are"} overdue · ${money(overdue.sumOf { it.total })}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
        }
        if (jobs.isEmpty()) {
            Text(
                text = "Nothing booked today. Tap Rota to book the first one.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        } else {
            jobs.forEachIndexed { index, job ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .background(
                                if (index == 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline,
                                CircleShape,
                            ),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = clientNames[job.clientId] ?: "Client",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = "${parseTimestamp(job.startsAt)?.formatTime() ?: "—"} · ${job.durationMinutes} min",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = job.notes.orEmpty().take(24),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(80.dp),
                        textAlign = TextAlign.End,
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Surface(
            onClick = onOpenSchedule,
            shape = RoundedCornerShape(JwTokens.smallRadius),
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "Open rota",
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun CashPanel(
    outstanding: Double,
    overdue: List<Invoice>,
    unbilledTotal: Double,
    unpaid: List<Invoice>,
    clientName: (String) -> String,
    today: LocalDate,
    onOpenInvoices: () -> Unit,
) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "OWED TO YOU",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = money(outstanding),
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = money(unbilledTotal),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "unbilled",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        if (unpaid.isEmpty()) {
            Text(
                text = "No unpaid invoices.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        } else {
            unpaid.take(5).forEachIndexed { index, invoice ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                val late = daysOverdue(invoice, today)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = clientName(invoice.clientId),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "${invoice.number}${if (late > 0) " · $late ${if (late == 1L) "day" else "days"} over" else ""}",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (late > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = money(invoice.total),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Surface(
            onClick = onOpenInvoices,
            shape = RoundedCornerShape(JwTokens.smallRadius),
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "Open invoices",
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(vertical = 10.dp),
            )
        }
    }
}

/** The running tax pot for the current UK self-assessment year (6 April - 5 April). */
@Composable
private fun TaxPotBand(
    ratePercent: Double,
    invoices: List<Invoice>,
    visits: List<Visit>,
    today: LocalDate,
) {
    val pot = remember(invoices, ratePercent, today) { taxSetAside(invoices, ratePercent, today) }
    val estimate = remember(invoices, visits, today) {
        estimateSelfAssessmentTax(estimatedTaxYearProfit(invoices, visits, today))
    }
    val year = remember(today) { ukTaxYearContaining(today) }
    if (ratePercent <= 0.0) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(JwTokens.bandRadius),
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ) {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    text = "Income tax",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Set your tax rate in Settings → Tax and this card will show what " +
                        "to put aside and an estimated HMRC bill.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        return
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(JwTokens.bandRadius),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(
                text = "Tax to set aside: ${money(pot)}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "${if (ratePercent % 1.0 == 0.0) ratePercent.toInt() else ratePercent}% " +
                    "of invoiced this tax year (${ukTaxYearLabel(year)})",
                style = MaterialTheme.typography.bodySmall,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f))
            val detail = if (estimate.profit <= SelfAssessment.PERSONAL_ALLOWANCE) {
                "Under the £12,570 allowance so far — no income tax or Class 4 on this profit."
            } else {
                "Income tax ${money(estimate.incomeTax)} + Class 4 NICs ${money(estimate.class4)} " +
                    "after the £12,570 allowance."
            }
            Text(
                text = "Estimated HMRC bill: ~${money(estimate.total)}",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                text = "Net after tax: ${money((estimate.profit - estimate.total).coerceAtLeast(0.0))}",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}
