package uk.co.jwgardenservices.crm.ui.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import uk.co.jwgardenservices.crm.data.model.Client
import uk.co.jwgardenservices.crm.data.model.ScheduledJob
import uk.co.jwgardenservices.crm.data.model.VisitDraft
import uk.co.jwgardenservices.crm.domain.BillingSource
import uk.co.jwgardenservices.crm.domain.formatDayMonth
import uk.co.jwgardenservices.crm.domain.formatShortWeekday
import uk.co.jwgardenservices.crm.domain.formatTime
import uk.co.jwgardenservices.crm.domain.localDate
import uk.co.jwgardenservices.crm.domain.money
import uk.co.jwgardenservices.crm.domain.parseTimestamp
import uk.co.jwgardenservices.crm.domain.priceVisit
import uk.co.jwgardenservices.crm.ui.CrmUiState
import uk.co.jwgardenservices.crm.ui.CrmViewModel
import uk.co.jwgardenservices.crm.ui.common.HoursDropdown
import uk.co.jwgardenservices.crm.ui.common.JwAlertDialog
import uk.co.jwgardenservices.crm.ui.common.JwCard
import uk.co.jwgardenservices.crm.ui.common.PillButton
import uk.co.jwgardenservices.crm.ui.common.PillStyle
import uk.co.jwgardenservices.crm.ui.schedule.CompleteJobDialog
import uk.co.jwgardenservices.crm.voice.VoicePlans
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Bookings from the last 30 days that have finished but were never marked done
 * or missed, oldest first. A job still under way is left alone until its booked
 * time is up.
 */
fun jobsNeedingMarking(jobs: List<ScheduledJob>, now: Instant = Instant.now()): List<ScheduledJob> {
    val since = now.minus(30, ChronoUnit.DAYS)
    return VoicePlans.unlogged(jobs, now, null).filter { job ->
        val start = parseTimestamp(job.startsAt) ?: return@filter false
        val end = start.plus(job.durationMinutes.coerceAtLeast(0).toLong(), ChronoUnit.MINUTES)
        !start.isBefore(since) && !end.isAfter(now)
    }
}

/**
 * Home's "Needs marking" card: the gap between the Rota and Ready to bill.
 * Hidden when there is nothing to mark. Logging always asks for the hours.
 */
@Composable
fun NeedsMarkingCard(state: CrmUiState, viewModel: CrmViewModel) {
    val jobs = remember(state.jobs) { jobsNeedingMarking(state.jobs) }
    if (jobs.isEmpty()) return
    val scope = rememberCoroutineScope()
    val clients = state.clients.associateBy { it.id }
    var logging by remember { mutableStateOf<ScheduledJob?>(null) }
    var missing by remember { mutableStateOf<ScheduledJob?>(null) }
    var markingAll by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun name(job: ScheduledJob) = clients[job.clientId]?.name ?: "A client"
    fun slot(job: ScheduledJob): String = parseTimestamp(job.startsAt)
        ?.let { "${it.formatShortWeekday()} ${it.formatDayMonth()} · ${it.formatTime()}" }
        .orEmpty()

    fun save(job: ScheduledJob, hours: Double, materials: Double, note: String?, description: String, missed: Boolean, amount: Double, after: () -> Unit) {
        val day = parseTimestamp(job.startsAt)?.localDate()?.toString() ?: return
        scope.launch {
            error = null
            try {
                viewModel.saveVisit(
                    VisitDraft(
                        clientId = job.clientId,
                        visitDate = day,
                        hours = hours,
                        materialsCost = materials,
                        materialsNote = note,
                        description = description,
                        amount = amount,
                        missed = missed,
                        completesJobId = job.id,
                        jobOutcome = if (missed) "missed" else "done",
                    ),
                )
                after()
            } catch (failure: Exception) {
                error = failure.message ?: "Could not save that visit"
            }
        }
    }

    JwCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Needs marking", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "${jobs.size} past ${if (jobs.size == 1) "job" else "jobs"} not logged yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (jobs.size > 1) {
                PillButton(
                    label = "Mark all",
                    onClick = { markingAll = true },
                    enabled = !state.saving,
                    style = PillStyle.Forest,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        jobs.take(8).forEach { job ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(name(job), style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = slot(job),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { missing = job }, enabled = !state.saving) { Text("Missed") }
                Spacer(Modifier.width(4.dp))
                PillButton(
                    label = "Log",
                    onClick = { logging = job },
                    enabled = !state.saving,
                    style = PillStyle.Forest,
                )
            }
            HorizontalDivider()
        }
        if (jobs.size > 8) {
            Text(
                text = "and ${jobs.size - 8} more — mark these first.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }

    logging?.let { job ->
        CompleteJobDialog(
            job = job,
            client = clients[job.clientId],
            clientName = name(job),
            isStaff = false,
            alreadyLogged = null,
            saving = state.saving,
            errorMessage = error,
            onDismiss = { if (!state.saving) logging = null },
            onUseLogged = {},
            onComplete = { hours, materials, note, description, missed, amount ->
                save(job, hours, materials, note, description, missed, amount) { logging = null }
            },
            askHours = true,
        )
    }

    missing?.let { job ->
        JwAlertDialog(
            onDismissRequest = { missing = null },
            title = { Text("Mark as missed?") },
            text = {
                Text("${name(job)}, ${slot(job)}. It's kept as a record, charged at nothing and left off invoices.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        save(job, 0.0, 0.0, null, "Missed visit", true, 0.0) { missing = null }
                    },
                ) { Text("Mark missed") }
            },
            dismissButton = { TextButton(onClick = { missing = null }) { Text("Cancel") } },
        )
    }

    if (markingAll) {
        MarkAllDialog(
            jobs = jobs,
            clients = clients,
            slot = ::slot,
            saving = state.saving,
            onDismiss = { markingAll = false },
            onConfirm = { entries ->
                markingAll = false
                entries.forEach { (job, hours) ->
                    val client = clients[job.clientId]
                    val amount = client?.let {
                        priceVisit(BillingSource(it.billingMode, it.hourlyRate, it.fixedPrice), hours, 0.0)
                    } ?: 0.0
                    save(job, hours, 0.0, null, job.notes ?: "Completed", false, amount) {}
                }
            },
        )
    }
}

/** Every job with its own hours to fill in; nothing is saved until each one has hours. */
@Composable
private fun MarkAllDialog(
    jobs: List<ScheduledJob>,
    clients: Map<String, Client>,
    slot: (ScheduledJob) -> String,
    saving: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (List<Pair<ScheduledJob, Double>>) -> Unit,
) {
    val hours = remember { mutableStateMapOf<String, String>() }
    val ready = jobs.all { (hours[it.id]?.toDoubleOrNull() ?: 0.0) > 0 }
    val total = jobs.sumOf { job ->
        val h = hours[job.id]?.toDoubleOrNull() ?: 0.0
        clients[job.clientId]?.let { priceVisit(BillingSource(it.billingMode, it.hourlyRate, it.fixedPrice), h, 0.0) } ?: 0.0
    }
    JwAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Mark ${jobs.size} jobs done") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Pick the hours for each. Use Log on a single job for materials or notes.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                jobs.forEach { job ->
                    Column {
                        Text(clients[job.clientId]?.name ?: "A client", style = MaterialTheme.typography.titleSmall)
                        Text(slot(job), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        HoursDropdown(
                            value = hours[job.id].orEmpty(),
                            onValueChange = { hours[job.id] = it },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                HorizontalDivider()
                Row {
                    Text("Total", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text(money(total), style = MaterialTheme.typography.titleMedium)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = ready && !saving,
                onClick = { onConfirm(jobs.map { it to (hours[it.id]?.toDoubleOrNull() ?: 0.0) }) },
            ) { Text("Mark done") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
