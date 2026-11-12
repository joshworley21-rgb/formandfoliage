package uk.co.jwgardenservices.crm.ui.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import uk.co.jwgardenservices.crm.data.model.Invoice
import uk.co.jwgardenservices.crm.data.model.ScheduledJob
import uk.co.jwgardenservices.crm.domain.formatDayMonth
import uk.co.jwgardenservices.crm.domain.formatTime
import uk.co.jwgardenservices.crm.domain.formatWeekday
import uk.co.jwgardenservices.crm.domain.hoursLabel
import uk.co.jwgardenservices.crm.domain.localDate
import uk.co.jwgardenservices.crm.domain.money
import uk.co.jwgardenservices.crm.domain.monthlyTotals
import uk.co.jwgardenservices.crm.domain.topClients
import uk.co.jwgardenservices.crm.domain.parseDate
import uk.co.jwgardenservices.crm.domain.parseTimestamp
import uk.co.jwgardenservices.crm.ui.CrmViewModel
import uk.co.jwgardenservices.crm.ui.common.ErrorState
import uk.co.jwgardenservices.crm.ui.common.LoadingState
import uk.co.jwgardenservices.crm.ui.common.PageHeader
import uk.co.jwgardenservices.crm.ui.common.SectionCard
import uk.co.jwgardenservices.crm.core.TaxReservePrefs
import uk.co.jwgardenservices.crm.domain.taxSetAside
import uk.co.jwgardenservices.crm.domain.ukTaxYearContaining
import uk.co.jwgardenservices.crm.domain.ukTaxYearLabel
import uk.co.jwgardenservices.crm.domain.SelfAssessment
import uk.co.jwgardenservices.crm.domain.estimateSelfAssessmentTax
import uk.co.jwgardenservices.crm.domain.estimatedTaxYearProfit
import uk.co.jwgardenservices.crm.data.model.Visit
import uk.co.jwgardenservices.crm.ui.common.StatCard
import uk.co.jwgardenservices.crm.ui.common.StatusChip
import uk.co.jwgardenservices.crm.ui.onDay

@Composable
fun DashboardScreen(
    viewModel: CrmViewModel,
    onOpenSchedule: () -> Unit,
    onOpenInvoices: () -> Unit,
    onOpenAssistant: () -> Unit = {},
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val today = LocalDate.now()
    val taxRate by TaxReservePrefs.ratePercent.collectAsStateWithLifecycle()

    val scheduled = state.jobs.filter { it.status == "scheduled" }
    val todayJobs = scheduled.onDay(today)
    val weekAhead = today.plusDays(7)
    val weekJobs = scheduled.filter {
        parseTimestamp(it.startsAt)?.localDate()?.let { d -> !d.isBefore(today) && !d.isAfter(weekAhead) } == true
    }
    val monthVisits = state.visits.filter { visit ->
        parseDate(visit.visitDate)?.let { it.year == today.year && it.month == today.month } == true
    }
    val monthHours = monthVisits.sumOf { it.hours }
    val monthValue = monthVisits.sumOf { it.amount }

    val unbilled = state.visits.filter { !it.billed && !it.missed }
    val unbilledTotal = unbilled.sumOf { it.amount }

    val monthTotals = remember(state.invoices) { monthlyTotals(state.invoices, today) }
    val topClientRows = remember(state.invoices) { topClients(state.invoices, today.minusMonths(12)) }
    val todayMinutes = todayJobs.sumOf { it.durationMinutes }
    val recentInvoices = state.invoices.take(6)
    val clientNames = remember(state.clients) {
        state.clients.associate { it.id to it.name }
    }

    if (state.loading) {
        Column(modifier = Modifier.padding(20.dp)) {
            PageHeader("Dashboard", "Today's round first, then hours and billing.")
            LoadingState("Loading your workspace…")
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            PageHeader(
                title = "Dashboard",
                description = "Today's round first, then hours and billing.",
                onRefresh = viewModel::refresh,
                onAssistant = onOpenAssistant,
            )
        }

        if (state.error != null) {
            item { ErrorState(state.error!!, viewModel::refresh) }
        }

        item {
            TodayBand(
                today = today,
                jobs = todayJobs,
                todayMinutes = todayMinutes,
                clientNames = clientNames,
                onOpenSchedule = onOpenSchedule,
            )
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
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    StatCard(
                        label = "Hours this month",
                        value = hoursLabel(monthHours),
                        icon = Icons.Outlined.AccessTime,
                        modifier = Modifier.weight(1f),
                    )
                    StatCard(
                        label = "Value logged",
                        value = money(monthValue),
                        icon = Icons.AutoMirrored.Outlined.ReceiptLong,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    StatCard(
                        label = "Unbilled work",
                        value = money(unbilledTotal),
                        icon = Icons.Outlined.Payments,
                        modifier = Modifier.weight(1f),
                    )
                    StatCard(
                        label = "Jobs next 7 days",
                        value = weekJobs.size.toString(),
                        icon = Icons.Outlined.CalendarMonth,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        item {
            SectionCard(
                title = "Billing",
                actionLabel = "Invoices",
                onAction = onOpenInvoices,
            ) {
                if (recentInvoices.isEmpty()) {
                    Text(
                        text = "No invoices yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(20.dp),
                    )
                } else {
                    recentInvoices.forEachIndexed { index, invoice ->
                        if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 20.dp))
                        InvoiceRow(invoice = invoice, clientName = clientNames[invoice.clientId] ?: "Client")
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
        }

        item { RevenueCard(months = monthTotals) }

        item {
            TopClientsCard(
                clients = topClientRows,
                clientName = { id -> clientNames[id] ?: "Client" },
            )
        }
    }
}

@Composable
private fun TodayBand(
    today: LocalDate,
    jobs: List<ScheduledJob>,
    todayMinutes: Int,
    clientNames: Map<String, String>,
    onOpenSchedule: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenSchedule),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = today.formatWeekday().uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                )
                if (todayMinutes > 0) {
                    Text(
                        text = hoursLabel(todayMinutes / 60.0),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (jobs.isEmpty()) {
                    "Nothing booked"
                } else {
                    "${jobs.size} ${if (jobs.size == 1) "job" else "jobs"} today"
                },
                style = MaterialTheme.typography.displaySmall,
            )
            Spacer(Modifier.height(16.dp))
            if (jobs.isEmpty()) {
                Text(
                    text = "Tap Schedule to book the first one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f),
                )
            } else {
                jobs.take(3).forEach { job ->
                    val starts = parseTimestamp(job.startsAt)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = starts?.formatTime() ?: "—",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.width(56.dp),
                        )
                        Text(
                            text = clientNames[job.clientId] ?: "Client",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f),
                        )
                        Text(
                            text = "${job.durationMinutes} min",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f),
                        )
                    }
                }
                if (jobs.size > 3) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = "…and ${jobs.size - 3} more · tap to open the calendar",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f),
                    )
                }
            }
        }
    }
}

@Composable
private fun InvoiceRow(invoice: Invoice, clientName: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(clientName, style = MaterialTheme.typography.titleMedium)
            Text(
                text = "${invoice.number} · ${parseDate(invoice.issueDate)?.formatDayMonth() ?: ""}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        InvoiceStatusChip(invoice.status)
        Spacer(Modifier.width(12.dp))
        Text(
            text = money(invoice.total),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun InvoiceStatusChip(status: String) {
    val (content, container) = invoiceColors(status)
    val label = when (status) {
        "paid" -> "Paid"
        "sent" -> "Sent"
        else -> "Draft"
    }
    StatusChip(label = label, color = content, container = container)
}

@Composable
private fun invoiceColors(status: String): Pair<Color, Color> = when (status) {
    "paid" -> MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    "sent" -> MaterialTheme.colorScheme.onSurfaceVariant to MaterialTheme.colorScheme.secondary
    else -> MaterialTheme.colorScheme.onSurfaceVariant to MaterialTheme.colorScheme.secondary
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
    if (ratePercent <= 0.0 || pot <= 0.0) return
    val year = remember(today) { ukTaxYearContaining(today) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Column {
                Text(
                    text = "Tax to set aside: ${money(pot)}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "${if (ratePercent % 1.0 == 0.0) ratePercent.toInt() else ratePercent}% of invoiced this tax year " +
                        "(${ukTaxYearLabel(year)}) · adjust in Settings",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.25f))

            Column {
                val detail = if (estimate.profit <= SelfAssessment.PERSONAL_ALLOWANCE) {
                    "Under the £12,570 allowance so far — no income tax or Class 4 on this profit."
                } else {
                    "Income tax ${money(estimate.incomeTax)} + Class 4 NICs ${money(estimate.class4)} " +
                        "after the £12,570 allowance (~${estimate.effectiveRate}% of profit)"
                }
                Text(
                    text = "Estimated HMRC bill: ~${money(estimate.total)}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = detail + " · from invoiced minus materials logged",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
