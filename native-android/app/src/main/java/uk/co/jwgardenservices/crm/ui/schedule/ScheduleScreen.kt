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

private val FREQUENCY_OPTIONS = listOf(
    Frequency.WEEKLY to "Weekly",
    Frequency.FORTNIGHTLY to "Fortnightly",
    Frequency.FOUR_WEEKLY to "Every 4 weeks",
    Frequency.MONTHLY to "Monthly",
    Frequency.QUARTERLY to "Quarterly",
)
private val FREQUENCY_LABELS = FREQUENCY_OPTIONS.toMap()

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
    var sheetJob by remember { mutableStateOf<ScheduledJob?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var monthCursor by remember { mutableStateOf(YearMonth.now()) }
    val listState = rememberLazyListState()
    val appContext = LocalContext.current

    fun moveJobTo(job: ScheduledJob, day: LocalDate) {
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

    fun closeDialogs() {
        booking = false
        editing = null
        completing = null
        seriesJob = null
        movingJob = null
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

private enum class DeleteMode { ONE, SERIES }

@Composable
private fun JobEditorDialog(
    existing: ScheduledJob?,
    clients: List<Client>,
    saving: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onSave: (JobDraft) -> Unit,
    onBookSeries: (SeriesDraft) -> Unit,
    onDelete: ((wholeSeries: Boolean) -> Unit)?,
    onEditSeries: (() -> Unit)? = null,
) {
    val starts = parseTimestamp(existing?.startsAt)
    var clientId by remember(existing?.id) {
        mutableStateOf(existing?.clientId ?: clients.firstOrNull()?.id.orEmpty())
    }
    var date by remember(existing?.id) {
        mutableStateOf(starts?.localDate()?.toString() ?: todayIso())
    }
    var time by remember(existing?.id) {
        mutableStateOf(starts?.formatTime() ?: "09:00")
    }
    var duration by remember(existing?.id) {
        mutableStateOf((existing?.durationMinutes ?: 60).toString())
    }
    var notes by remember(existing?.id) { mutableStateOf(existing?.notes.orEmpty()) }
    var repeats by remember { mutableStateOf(false) }
    var frequency by remember { mutableStateOf(Frequency.FORTNIGHTLY) }
    var occurrences by remember { mutableStateOf("12") }
    var confirmDelete by remember { mutableStateOf<DeleteMode?>(null) }

    val durationValue = duration.toIntOrNull() ?: 0
    val dateValid = parseDate(date) != null
    val timeValid = runCatching { LocalTime.parse(time) }.isSuccess
    val canSave = clientId.isNotBlank() && dateValid && timeValid && durationValue > 0 && !saving
    val creating = existing == null

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
                .heightIn(max = 760.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = if (creating) "Book a job" else "Edit job",
                    style = MaterialTheme.typography.headlineSmall,
                )

                OptionPicker(
                    options = clients,
                    selected = clients.find { it.id == clientId },
                    label = { it?.name ?: "Choose client" },
                    enabled = true,
                    onSelect = { clientId = it.id },
                )

                DateField(
                    value = date,
                    onValueChange = { date = it },
                    label = "Date",
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = time,
                        onValueChange = { time = it },
                        label = { Text("Start (HH:mm)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = duration,
                        onValueChange = { duration = it },
                        label = { Text("Minutes") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DurationPreset("1h", 60) { duration = it.toString() }
                    DurationPreset("2h", 120) { duration = it.toString() }
                    DurationPreset("4h", 240) { duration = it.toString() }
                    DurationPreset("8h", 480) { duration = it.toString() }
                }

                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes") },
                    placeholder = { Text("Mow, edge, hedge front") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (creating) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = repeats, onCheckedChange = { repeats = it })
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Repeats",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                text = "Books the whole run on the calendar at once.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    if (repeats) {
                        OptionPicker(
                            options = FREQUENCY_OPTIONS.map { it.first },
                            selected = frequency,
                            label = { value ->
                                value?.let { FREQUENCY_LABELS[it] ?: it.wire } ?: "Frequency"
                            },
                            enabled = true,
                            onSelect = { frequency = it },
                        )
                        OutlinedTextField(
                            value = occurrences,
                            onValueChange = { occurrences = it },
                            label = { Text("Number of visits (1–60)") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                if (existing?.seriesId != null && onEditSeries != null) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.secondary,
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = "Part of a repeating run",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                text = "Move the time or rhythm of every visit still to come, without touching the ones already done.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            OutlinedButton(
                                onClick = onEditSeries,
                                enabled = !saving,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Edit the whole run")
                            }
                        }
                    }
                }

                if (errorMessage != null) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                HorizontalDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (existing != null && onDelete != null) {
                        TextButton(
                            onClick = { confirmDelete = DeleteMode.ONE },
                            enabled = !saving,
                        ) {
                            Text("Delete", color = MaterialTheme.colorScheme.error)
                        }
                        if (existing.seriesId != null) {
                            TextButton(
                                onClick = { confirmDelete = DeleteMode.SERIES },
                                enabled = !saving,
                            ) {
                                Text("Delete run", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss, enabled = !saving) { Text("Cancel") }
                    Spacer(Modifier.width(6.dp))
                    Button(
                        enabled = canSave,
                        onClick = {
                            val draft = JobDraft(
                                id = existing?.id,
                                clientId = clientId,
                                date = date,
                                time = time,
                                durationMinutes = durationValue,
                                notes = notes,
                            )
                            if (creating && repeats) {
                                onBookSeries(
                                    SeriesDraft(
                                        clientId = clientId,
                                        date = date,
                                        time = time,
                                        durationMinutes = durationValue,
                                        notes = notes,
                                        frequency = frequency,
                                        occurrences = occurrences.toIntOrNull() ?: 1,
                                    ),
                                )
                            } else {
                                onSave(draft)
                            }
                        },
                    ) {
                        Text(if (creating) "Book" else "Save")
                    }
                }
            }
        }
    }

    confirmDelete?.let { mode ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = {
                Text(if (mode == DeleteMode.SERIES) "Delete the whole run?" else "Delete this job?")
            },
            text = {
                Text(
                    if (mode == DeleteMode.SERIES) {
                        "Every remaining visit in this repeating run will be removed. Completed visits stay."
                    } else {
                        "This visit will be removed from the calendar."
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = null
                        onDelete?.invoke(mode == DeleteMode.SERIES)
                    },
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun CompleteJobDialog(
    job: ScheduledJob,
    client: Client?,
    clientName: String,
    isStaff: Boolean,
    alreadyLogged: Visit?,
    saving: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onUseLogged: () -> Unit,
    onComplete: (
        hours: Double,
        materials: Double,
        materialsNote: String?,
        description: String,
        missed: Boolean,
        amount: Double,
    ) -> Unit,
) {
    var hours by remember(job.id) {
        mutableStateOf((job.durationMinutes / 60.0).toString())
    }
    var materials by remember(job.id) { mutableStateOf("0") }
    var materialsNote by remember(job.id) { mutableStateOf("") }
    var description by remember(job.id) { mutableStateOf(job.notes.orEmpty()) }
    var missed by remember(job.id) { mutableStateOf(false) }
    var enteringAnyway by remember(job.id) { mutableStateOf(false) }

    val hoursValue = hours.toDoubleOrNull() ?: 0.0
    val materialsValue = materials.toDoubleOrNull() ?: 0.0
    val amount = if (missed) {
        0.0
    } else {
        client?.let {
            priceVisit(
                BillingSource(it.billingMode, it.hourlyRate, it.fixedPrice),
                hoursValue,
                materialsValue,
            )
        } ?: 0.0
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
                .heightIn(max = 720.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Complete job — $clientName",
                    style = MaterialTheme.typography.headlineSmall,
                )

                if (alreadyLogged != null && !enteringAnyway) {
                    Text(
                        text = "A visit is already logged for $clientName on ${parseDate(alreadyLogged.visitDate)?.formatDayMonthYear() ?: alreadyLogged.visitDate}.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.secondary,
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                text = "${alreadyLogged.hours} h · ${money(alreadyLogged.amount)}",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            if (!alreadyLogged.description.isNullOrBlank()) {
                                Text(
                                    text = alreadyLogged.description.orEmpty(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    Button(
                        onClick = onUseLogged,
                        enabled = !saving,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Complete using this visit")
                    }
                    TextButton(
                        onClick = { enteringAnyway = true },
                        enabled = !saving,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Log separate hours instead")
                    }
                } else {
                    if (!isStaff) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Switch(
                                checked = missed,
                                onCheckedChange = { missed = it },
                                enabled = !saving,
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Did not happen (missed)",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    text = "The job is closed as missed and nothing is charged.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    if (!missed) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedTextField(
                                value = hours,
                                onValueChange = { hours = it },
                                label = { Text("Hours") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.weight(1f),
                            )
                            if (!isStaff) {
                                OutlinedTextField(
                                    value = materials,
                                    onValueChange = { materials = it },
                                    label = { Text("Materials (£)") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                        if (!isStaff && materialsValue > 0) {
                            OutlinedTextField(
                                value = materialsNote,
                                onValueChange = { materialsNote = it },
                                label = { Text("What the materials were") },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }

                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = { Text(if (missed) "What happened" else "Work done") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    if (!isStaff) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.secondary,
                        ) {
                            Text(
                                text = if (missed) {
                                    "Charge: nothing (this job did not happen)"
                                } else {
                                    "Visit total: ${money(amount)}"
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            )
                        }
                    }

                    if (errorMessage != null) {
                        Text(
                            text = errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    HorizontalDivider()

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(onClick = onDismiss, enabled = !saving) { Text("Cancel") }
                        Spacer(Modifier.width(6.dp))
                        Button(
                            enabled = !saving,
                            onClick = {
                                onComplete(
                                    hoursValue,
                                    materialsValue,
                                    materialsNote,
                                    description,
                                    missed,
                                    amount,
                                )
                            },
                        ) {
                            Text(
                                when {
                                    isStaff -> "Complete job"
                                    missed -> "Save missed job"
                                    else -> "Log visit & complete"
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MoveJobDialog(
    job: ScheduledJob,
    clientName: String,
    saving: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onMove: (date: String, time: String) -> Unit,
) {
    val starts = parseTimestamp(job.startsAt)
    val time = starts?.formatTime() ?: "09:00"
    val startDay = starts?.localDate() ?: LocalDate.now()
    var date by remember(job.id) { mutableStateOf(startDay.toString()) }

    val presets = listOf(
        "Next day" to startDay.plusDays(1),
        "In 2 days" to startDay.plusDays(2),
        "Next week" to startDay.plusDays(7),
    )
    val dateValid = parseDate(date) != null

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Move ${clientName}'s job", style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = "Currently ${startDay.formatDayMonthYear()} at $time. The time stays the same wherever it lands" +
                        (if (job.seriesId != null) ", and only this visit moves — the rest of the run stays put." else "."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    presets.forEach { (label, day) ->
                        OutlinedButton(
                            onClick = { onMove(day.toString(), time) },
                            enabled = !saving,
                            modifier = Modifier.weight(1f),
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(label, style = MaterialTheme.typography.bodySmall)
                                Text(
                                    text = day.formatDayMonth(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                DateField(
                    value = date,
                    onValueChange = { date = it },
                    label = "Or pick a day",
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (errorMessage != null) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                HorizontalDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss, enabled = !saving) { Text("Cancel") }
                    Spacer(Modifier.width(6.dp))
                    Button(
                        enabled = dateValid && !saving,
                        onClick = { onMove(date, time) },
                    ) {
                        Text("Move")
                    }
                }
            }
        }
    }
}

private enum class SeriesSpacing(val label: String, val frequency: Frequency?) {
    KEEP("Keep existing days", null),
    WEEKLY("Weekly", Frequency.WEEKLY),
    FORTNIGHTLY("Fortnightly", Frequency.FORTNIGHTLY),
    FOUR_WEEKLY("Every 4 weeks", Frequency.FOUR_WEEKLY),
    MONTHLY("Monthly", Frequency.MONTHLY),
    QUARTERLY("Quarterly", Frequency.QUARTERLY),
}

@Composable
private fun SeriesDialog(
    job: ScheduledJob,
    jobs: List<ScheduledJob>,
    saving: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onSave: (SeriesEditInput) -> Unit,
) {
    val starts = parseTimestamp(job.startsAt)
    var startTime by remember(job.id) { mutableStateOf(starts?.formatTime() ?: "09:00") }
    var duration by remember(job.id) { mutableStateOf(job.durationMinutes.toString()) }
    var notes by remember(job.id) { mutableStateOf(job.notes.orEmpty()) }
    var spacing by remember(job.id) { mutableStateOf(SeriesSpacing.KEEP) }

    val editableCount = remember(jobs) {
        editableVisits(
            jobs.mapNotNull { row ->
                parseTimestamp(row.startsAt)?.let { instant ->
                    SeriesJob(row.id, instant, row.status)
                }
            },
            Instant.now(),
        ).size
    }
    val durationValue = duration.toIntOrNull() ?: 0
    val timeValid = runCatching { LocalTime.parse(startTime) }.isSuccess
    val canSave = job.seriesId != null &&
        editableCount > 0 &&
        timeValid &&
        durationValue > 0 &&
        !saving

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
                .heightIn(max = 720.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Edit the whole run", style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = "$editableCount remaining ${if (editableCount == 1) "visit" else "visits"} will move. Completed and past visits stay where they are.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = startTime,
                    onValueChange = { startTime = it },
                    label = { Text("Start time (HH:mm)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = duration,
                    onValueChange = { duration = it },
                    label = { Text("Minutes per visit") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )

                OptionPicker(
                    options = SeriesSpacing.entries,
                    selected = spacing,
                    label = { it?.label ?: "Keep existing days" },
                    enabled = !saving,
                    onSelect = { spacing = it },
                )
                Text(
                    text = "Keep existing days only moves the clock. Choosing a frequency re-spaces the remaining visits from the first one.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (errorMessage != null) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                HorizontalDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss, enabled = !saving) { Text("Cancel") }
                    Spacer(Modifier.width(6.dp))
                    Button(
                        enabled = canSave,
                        onClick = {
                            job.seriesId?.let { seriesId ->
                                onSave(
                                    SeriesEditInput(
                                        seriesId = seriesId,
                                        startTime = startTime,
                                        frequency = spacing.frequency,
                                        durationMinutes = durationValue,
                                        notes = notes,
                                    ),
                                )
                            }
                        },
                    ) {
                        Text("Save run")
                    }
                }
            }
        }
    }
}

@Composable
private fun DurationPreset(label: String, minutes: Int, onPick: (Int) -> Unit) {
    OutlinedButton(onClick = { onPick(minutes) }) {
        Text(label)
    }
}

@Composable
private fun <T> OptionPicker(
    options: List<T>,
    selected: T?,
    label: (T?) -> String,
    enabled: Boolean,
    onSelect: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { open = true },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = label(selected),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Start,
            )
            Icon(Icons.Outlined.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(label(option)) },
                    onClick = {
                        open = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}
