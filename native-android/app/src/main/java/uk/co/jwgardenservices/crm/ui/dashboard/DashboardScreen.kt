package uk.co.jwgardenservices.crm.ui.dashboard

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
import uk.co.jwgardenservices.crm.domain.formatShortWeekday
import uk.co.jwgardenservices.crm.domain.formatTime
import uk.co.jwgardenservices.crm.domain.formatWeekday
import uk.co.jwgardenservices.crm.domain.hoursLabel
import uk.co.jwgardenservices.crm.domain.localDate
import uk.co.jwgardenservices.crm.domain.money
import uk.co.jwgardenservices.crm.domain.parseDate
import uk.co.jwgardenservices.crm.domain.parseTimestamp
import uk.co.jwgardenservices.crm.ui.CrmViewModel
import uk.co.jwgardenservices.crm.ui.common.ErrorState
import uk.co.jwgardenservices.crm.ui.common.LoadingState
import uk.co.jwgardenservices.crm.ui.common.PageHeader
import uk.co.jwgardenservices.crm.ui.common.SectionCard
import uk.co.jwgardenservices.crm.ui.common.StatCard
import uk.co.jwgardenservices.crm.ui.common.StatusChip
import uk.co.jwgardenservices.crm.ui.onDay

@Composable
fun DashboardScreen(
    viewModel: CrmViewModel,
    onOpenSchedule: () -> Unit,
    onOpenInvoices: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val today = LocalDate.now()

    val scheduled = state.jobs.filter { it.status == "scheduled" }
    val todayJobs = scheduled.onDay(today)
    val upcomingAll = scheduled
        .filter { parseTimestamp(it.startsAt)?.localDate()?.isAfter(today) == true }
        .sortedBy { it.startsAt }
    val upcoming = upcomingAll.take(8)

    val monthVisits = state.visits.filter { visit ->
        parseDate(visit.visitDate)?.let { it.year == today.year && it.month == today.month } == true
    }
    val monthHours = monthVisits.sumOf { it.hours }
    val monthValue = monthVisits.sumOf { it.amount }

    val unbilled = state.visits.filter { !it.billed && !it.missed }
    val unbilledTotal = unbilled.sumOf { it.amount }
    val outstandingTotal = state.invoices.filter { it.status != "paid" }.sumOf { it.total }

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
            )
        }

        if (state.error != null) {
            item { ErrorState(state.error!!, viewModel::refresh) }
        }

        item { TodayBand(today = today, jobs = todayJobs, todayMinutes = todayMinutes) }

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
                        value = (upcomingAll.size + todayJobs.size).toString(),
                        icon = Icons.Outlined.CalendarMonth,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        item {
            SectionCard(
                title = "Next 7 days",
                actionLabel = "Calendar",
                onAction = onOpenSchedule,
            ) {
                if (upcoming.isEmpty()) {
                    Text(
                        text = "Nothing booked yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(20.dp),
                    )
                } else {
                    upcoming.forEachIndexed { index, job ->
                        if (index > 0) HorizontalDivider(Modifier.padding(horizontal = 20.dp))
                        UpcomingRow(job = job, clientName = clientNames[job.clientId] ?: "Client")
                    }
                    Spacer(Modifier.height(8.dp))
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

        if (unbilledTotal > 0 || outstandingTotal > 0) {
            item {
                Text(
                    text = "${money(unbilledTotal)} ready to bill · ${money(outstandingTotal)} awaiting payment.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TodayBand(today: LocalDate, jobs: List<ScheduledJob>, todayMinutes: Int) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
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
                            text = "Job booked",
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
            }
        }
    }
}

@Composable
private fun UpcomingRow(job: ScheduledJob, clientName: String) {
    val starts = parseTimestamp(job.startsAt)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.width(52.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = starts?.formatShortWeekday()?.uppercase() ?: "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = starts?.localDate()?.dayOfMonth?.toString() ?: "",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(clientName, style = MaterialTheme.typography.titleMedium)
            Text(
                text = "${starts?.formatTime() ?: "—"} · ${job.durationMinutes} min",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
