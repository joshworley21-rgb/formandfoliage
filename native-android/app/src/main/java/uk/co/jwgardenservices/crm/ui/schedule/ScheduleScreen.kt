package uk.co.jwgardenservices.crm.ui.schedule

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.automirrored.outlined.Assignment
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.widget.Toast
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import kotlinx.coroutines.launch
import uk.co.jwgardenservices.crm.data.model.Client
import uk.co.jwgardenservices.crm.data.model.JobDraft
import uk.co.jwgardenservices.crm.data.model.ScheduledJob
import uk.co.jwgardenservices.crm.data.model.SeriesDraft
import uk.co.jwgardenservices.crm.data.model.SeriesEditInput
import uk.co.jwgardenservices.crm.data.model.Visit
import uk.co.jwgardenservices.crm.data.model.VisitDraft
import uk.co.jwgardenservices.crm.domain.BillingSource
import uk.co.jwgardenservices.crm.domain.Frequency
import uk.co.jwgardenservices.crm.domain.SeriesJob
import uk.co.jwgardenservices.crm.domain.editableVisits
import uk.co.jwgardenservices.crm.domain.formatDayMonth
import uk.co.jwgardenservices.crm.domain.formatDayMonthYear
import uk.co.jwgardenservices.crm.domain.formatTime
import uk.co.jwgardenservices.crm.domain.formatWeekday
import uk.co.jwgardenservices.crm.domain.localDate
import uk.co.jwgardenservices.crm.domain.money
import uk.co.jwgardenservices.crm.domain.parseDate
import uk.co.jwgardenservices.crm.domain.parseTimestamp
import uk.co.jwgardenservices.crm.domain.priceVisit
import uk.co.jwgardenservices.crm.domain.todayIso
import uk.co.jwgardenservices.crm.ui.CrmViewModel
import uk.co.jwgardenservices.crm.ui.common.DateField
import uk.co.jwgardenservices.crm.ui.common.EmptyState
import uk.co.jwgardenservices.crm.ui.common.ErrorState
import uk.co.jwgardenservices.crm.ui.common.LoadingState
import uk.co.jwgardenservices.crm.ui.common.PageHeader
import uk.co.jwgardenservices.crm.ui.common.StatusChip


@Composable
fun ScheduleScreen(viewModel: CrmViewModel, isStaff: Boolean) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val today = LocalDate.now()
    var selectedDay by remember { mutableStateOf(LocalDate.now()) }

    val days = remember(selectedDay) {
        (0..20).map { selectedDay.plusDays(it.toLong()) }
    }
    val jobsByDay: Map<LocalDate, List<ScheduledJob>> = remember(state.jobs) {
        state.jobs
            .filter { it.status != "cancelled" }
            .mapNotNull { job ->
                parseTimestamp(job.startsAt)?.localDate()?.let { day -> day to job }
            }
            .groupBy({ it.first }, { it.second })
    }
    val clientNames = remember(state.clients) { state.clients.associate { it.id to it.name } }
    val claimedVisitIds = remember(state.jobs) {
        state.jobs.mapNotNull { it.visitId }.toSet()
    }
    val hasAny = jobsByDay.values.any { it.isNotEmpty() }

    var booking by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ScheduledJob?>(null) }
    var completing by remember { mutableStateOf<ScheduledJob?>(null) }
    var seriesJob by remember { mutableStateOf<ScheduledJob?>(null) }
    var movingJob by remember { mutableStateOf<ScheduledJob?>(null) }
    var pendingSeriesMove by remember { mutableStateOf<Pair<ScheduledJob, LocalDate>?>(null) }
    var sheetJob by remember { mutableStateOf<ScheduledJob?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var monthCursor by remember { mutableStateOf(YearMonth.now()) }
    val listState = rememberLazyListState()
    val appContext = LocalContext.current

    fun moveSingle(job: ScheduledJob, day: LocalDate) {
        val starts = parseTimestamp(job.startsAt) ?: return
        scope.launch {
            try {
                viewModel.saveJob(
                    JobDraft(
                        id = job.id,
                        clientId = job.clientId,
                        date = day.toString(),
                        time = starts.formatTime(),
                        durationMinutes = job.durationMinutes,
                        notes = job.notes,
                    ),
                )
                Toast.makeText(
                    appContext,
                    "Moved to ${day.formatDayMonth()}",
                    Toast.LENGTH_SHORT,
                ).show()
            } catch (error: Exception) {
                Toast.makeText(
                    appContext,
                    error.message ?: "Could not move the job",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    fun moveJobTo(job: ScheduledJob, day: LocalDate) {
        // Part of a repeating run? Ask before carrying the rest of the run with it.
        if (job.seriesId != null) {
            pendingSeriesMove = job to day
            return
        }
        moveSingle(job, day)
    }

    fun closeDialogs() {
        booking = false
        editing = null
        completing = null
        seriesJob = null
        movingJob = null
        pendingSeriesMove = null
        sheetJob = null
        saveError = null
    }

    if (state.loading) {
        Column(Modifier.padding(20.dp)) {
            PageHeader("Schedule", "Plan the round and tick jobs off as you go.")
            LoadingState()
        }
        return
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            PageHeader(
                title = "Schedule",
                description = "Book the round, then tick it off as you go.",
                action = {
                    if (!isStaff) {
                        Button(
                            enabled = state.clients.isNotEmpty(),
                            onClick = {
                                saveError = null
                                booking = true
                            },
                        ) {
                            Icon(Icons.Outlined.Add, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Book job")
                        }
                    }
                },
            )
        }

        item {
            MonthCalendar(
                jobs = jobsByDay,
                month = monthCursor,
                selectedDay = selectedDay,
                onMonthChange = { monthCursor = it },
                onSelectDay = { day ->
                    selectedDay = day
                    monthCursor = YearMonth.from(day)
                    scope.launch { listState.animateScrollToItem(2) }
                },
                canDrag = !isStaff,
                clientName = { id -> clientNames[id] ?: "Client" },
                onMove = { job, day -> moveJobTo(job, day) },
            )
        }

        if (state.error != null) {
            item { ErrorState(state.error!!, viewModel::refresh) }
        }

        if (state.clients.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Outlined.EventAvailable,
                    title = if (isStaff) "No clients yet" else "Add a client first",
                    body = if (isStaff) {
                        "Your employer's clients will appear here once they are added."
                    } else {
                        "A job belongs to a client, so add one from the Clients tab and then book it here."
                    },
                )
            }
            return@LazyColumn
        }

        if (!hasAny) {
            item {
                EmptyState(
                    icon = Icons.Outlined.EventAvailable,
                    title = "Nothing booked",
                    body = "Book the first job with the button above. Repeating runs go on the calendar all at once.",
                )
            }
        }

        days.forEach { day ->
            val jobs = jobsByDay[day].orEmpty().sortedBy { it.startsAt }
            if (day == selectedDay || jobs.isNotEmpty()) {
                item(key = "day-$day") { DayHeading(day = day, isToday = day == today) }
                if (jobs.isEmpty()) {
                    item(key = "empty-$day") {
                        Text(
                            text = "Nothing booked on this day.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                        )
                    }
                } else {
                    items(jobs, key = { it.id }) { job ->
                        JobRow(
                            job = job,
                            clientName = clientNames[job.clientId] ?: "Client",
                            isStaff = isStaff,
                            onSheet = { sheetJob = job },
                            onEdit = {
                                saveError = null
                                editing = job
                            },
                            onMove = {
                                saveError = null
                                movingJob = job
                            },
                            onComplete = {
                                saveError = null
                                completing = job
                            },
                        )
                    }
                }
                item { Spacer(Modifier.padding(bottom = 6.dp)) }
            }
        }
    }

    if (booking) {
        JobEditorDialog(
            existing = null,
            clients = state.clients,
            saving = state.saving,
            errorMessage = saveError,
            onDismiss = { if (!state.saving) closeDialogs() },
            onSave = { draft ->
                scope.launch {
                    saveError = null
                    try {
                        viewModel.saveJob(draft)
                        closeDialogs()
                    } catch (error: Exception) {
                        saveError = error.message ?: "Could not book the job"
                    }
                }
            },
            onBookSeries = { draft ->
                scope.launch {
                    saveError = null
                    try {
                        viewModel.bookSeries(draft)
                        closeDialogs()
                    } catch (error: Exception) {
                        saveError = error.message ?: "Could not book the run"
                    }
                }
            },
            onDelete = null,
        )
    }

    editing?.let { job ->
        JobEditorDialog(
            existing = job,
            clients = state.clients,
            saving = state.saving,
            errorMessage = saveError,
            onDismiss = { if (!state.saving) closeDialogs() },
            onSave = { draft ->
                scope.launch {
                    saveError = null
                    try {
                        viewModel.saveJob(draft)
                        closeDialogs()
                    } catch (error: Exception) {
                        saveError = error.message ?: "Could not save the job"
                    }
                }
            },
            onBookSeries = {},
            onEditSeries = {
                editing = null
                saveError = null
                seriesJob = job
            },
            onDelete = { wholeSeries ->
                scope.launch {
                    saveError = null
                    try {
                        viewModel.deleteJob(
                            id = job.id,
                            wholeSeriesId = if (wholeSeries) job.seriesId else null,
                        )
                        closeDialogs()
                    } catch (error: Exception) {
                        saveError = error.message ?: "Could not delete the job"
                    }
                }
            },
        )
    }

    completing?.let { job ->
        val jobDay = parseTimestamp(job.startsAt)?.localDate()?.toString()
        val alreadyLogged = jobDay?.let { day ->
            state.visits.firstOrNull {
                it.clientId == job.clientId && it.visitDate == day && it.id !in claimedVisitIds
            }
        }
        CompleteJobDialog(
            job = job,
            client = if (isStaff) null else state.clients.find { it.id == job.clientId },
            clientName = clientNames[job.clientId] ?: "Client",
            isStaff = isStaff,
            alreadyLogged = if (isStaff) null else alreadyLogged,
            saving = state.saving,
            errorMessage = saveError,
            onDismiss = { if (!state.saving) closeDialogs() },
            onUseLogged = {
                alreadyLogged?.let { visit ->
                    scope.launch {
                        saveError = null
                        try {
                            viewModel.completeJobWithVisit(job.id, visit.id)
                            closeDialogs()
                        } catch (error: Exception) {
                            saveError = error.message ?: "Could not complete the job"
                        }
                    }
                }
            },
            onComplete = { hours, materials, materialsNote, description, missed, amount ->
                if (isStaff) {
                    scope.launch {
                        saveError = null
                        try {
                            viewModel.staffCompleteJob(job.id, hours, description)
                            closeDialogs()
                        } catch (error: Exception) {
                            saveError = error.message ?: "Could not complete the job"
                        }
                    }
                } else {
                    jobDay?.let { day ->
                        scope.launch {
                            saveError = null
                            try {
                                viewModel.saveVisit(
                                    VisitDraft(
                                        clientId = job.clientId,
                                        visitDate = day,
                                        hours = hours,
                                        materialsCost = materials,
                                        materialsNote = materialsNote,
                                        description = description,
                                        amount = amount,
                                        missed = missed,
                                        completesJobId = job.id,
                                        jobOutcome = if (missed) "missed" else "done",
                                    ),
                                )
                                closeDialogs()
                            } catch (error: Exception) {
                                saveError = error.message ?: "Could not complete the job"
                            }
                        }
                    }
                }
            },
        )
    }

    sheetJob?.let { job ->
        JobSheetDialog(
            job = job,
            clientName = clientNames[job.clientId] ?: "Client",
            clientPhone = state.clients.firstOrNull { it.id == job.clientId }?.phone,
            clientAddress = state.clients.firstOrNull { it.id == job.clientId }?.address,
            viewModel = viewModel,
            onClose = { sheetJob = null },
        )
    }

    movingJob?.let { job ->
        MoveJobDialog(
            job = job,
            clientName = clientNames[job.clientId] ?: "Client",
            saving = state.saving,
            errorMessage = saveError,
            onDismiss = {
                if (!state.saving) {
                    movingJob = null
                    saveError = null
                }
            },
            onMove = { date, time ->
                scope.launch {
                    saveError = null
                    try {
                        viewModel.saveJob(
                            JobDraft(
                                id = job.id,
                                clientId = job.clientId,
                                date = date,
                                time = time,
                                durationMinutes = job.durationMinutes,
                                notes = job.notes,
                            ),
                        )
                        movingJob = null
                    } catch (error: Exception) {
                        saveError = error.message ?: "Could not move the job"
                    }
                }
            },
        )
    }

    pendingSeriesMove?.let { (job, day) ->
        val currentDay = parseTimestamp(job.startsAt)?.localDate()?.formatDayMonth() ?: "this visit"
        AlertDialog(
            onDismissRequest = { pendingSeriesMove = null },
            title = { Text("Move the whole run?") },
            text = {
                Text(
                    "This visit on $currentDay is part of a repeating run. Move just this visit, " +
                        "or this visit and every future one in the run? Done and past visits are left alone.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingSeriesMove = null
                        scope.launch {
                            try {
                                viewModel.moveSeriesFrom(job.id, day)
                                Toast.makeText(
                                    appContext,
                                    "Run moved to ${day.formatDayMonth()}",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            } catch (error: Exception) {
                                Toast.makeText(
                                    appContext,
                                    error.message ?: "Could not move the run",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    },
                ) { Text("This and future") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        pendingSeriesMove = null
                        moveSingle(job, day)
                    }) { Text("Just this one") }
                    TextButton(onClick = { pendingSeriesMove = null }) { Text("Cancel") }
                }
            },
        )
    }

    seriesJob?.let { job ->
        SeriesDialog(
            job = job,
            jobs = state.jobs.filter { it.seriesId == job.seriesId },
            saving = state.saving,
            errorMessage = saveError,
            onDismiss = {
                if (!state.saving) {
                    seriesJob = null
                    saveError = null
                }
            },
            onSave = { input ->
                scope.launch {
                    saveError = null
                    try {
                        viewModel.editSeries(input)
                        seriesJob = null
                    } catch (error: Exception) {
                        saveError = error.message ?: "Could not update the run"
                    }
                }
            },
        )
    }
}

@Composable
private fun DayHeading(day: LocalDate, isToday: Boolean) {
    if (isToday) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("TODAY", style = MaterialTheme.typography.labelSmall)
                Text(
                    text = day.formatDayMonthYear(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    } else {
        Text(
            text = day.formatWeekday().uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun JobRow(
    job: ScheduledJob,
    clientName: String,
    isStaff: Boolean,
    onSheet: () -> Unit,
    onEdit: () -> Unit,
    onMove: () -> Unit,
    onComplete: () -> Unit,
) {
    val starts = parseTimestamp(job.startsAt)
    val scheduled = job.status == "scheduled"
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isStaff, onClick = onEdit),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = starts?.formatTime() ?: "—",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(58.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(clientName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = buildString {
                            append("${job.durationMinutes} min")
                            if (job.seriesId != null) append(" · repeating run")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onSheet) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.Assignment,
                        contentDescription = "Job sheet",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when (job.status) {
                    "done" -> StatusChip(
                        label = "Done",
                        color = MaterialTheme.colorScheme.primary,
                        container = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    )
                    "missed" -> StatusChip(
                        label = "Missed",
                        color = MaterialTheme.colorScheme.error,
                        container = MaterialTheme.colorScheme.error.copy(alpha = 0.10f),
                    )
                    else -> Unit
                }
            }

            if (!job.notes.isNullOrBlank()) {
                Text(
                    text = job.notes.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            if (scheduled) {
                Row(
                    modifier = Modifier.padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(onClick = onComplete, modifier = Modifier.weight(1f)) {
                        Text("Complete")
                    }
                    if (!isStaff) {
                        OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f)) {
                            Text("Edit")
                        }
                        OutlinedButton(onClick = onMove, modifier = Modifier.weight(1f)) {
                            Text("Move")
                        }
                    }
                }
            } else if (job.visitId != null) {
                Text(
                    text = "Visit logged",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

