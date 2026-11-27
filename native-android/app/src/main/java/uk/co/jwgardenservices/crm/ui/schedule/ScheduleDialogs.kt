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
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import uk.co.jwgardenservices.crm.ui.theme.JwTokens
import uk.co.jwgardenservices.crm.ui.common.JwAlertDialog
import uk.co.jwgardenservices.crm.ui.common.JwDialog
import uk.co.jwgardenservices.crm.ui.common.SettingSwitchRow
import uk.co.jwgardenservices.crm.data.model.Client
import uk.co.jwgardenservices.crm.data.model.JobDraft
import uk.co.jwgardenservices.crm.data.model.ScheduledJob
import uk.co.jwgardenservices.crm.data.model.SeriesDraft
import uk.co.jwgardenservices.crm.data.model.SeriesEditInput
import uk.co.jwgardenservices.crm.data.model.Visit
import uk.co.jwgardenservices.crm.ui.common.HoursDropdown
import uk.co.jwgardenservices.crm.ui.common.TimeDropdown
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
import uk.co.jwgardenservices.crm.ui.common.DialogTitle
import uk.co.jwgardenservices.crm.ui.common.EmptyState
import uk.co.jwgardenservices.crm.ui.common.ErrorState
import uk.co.jwgardenservices.crm.ui.common.LoadingState
import uk.co.jwgardenservices.crm.ui.common.StatusChip
import uk.co.jwgardenservices.crm.ui.common.OptionPicker

private val FREQUENCY_OPTIONS = listOf(
    Frequency.WEEKLY to "Weekly",
    Frequency.FORTNIGHTLY to "Fortnightly",
    Frequency.FOUR_WEEKLY to "Every 4 weeks",
    Frequency.MONTHLY to "Monthly",
    Frequency.QUARTERLY to "Quarterly",
)
private val FREQUENCY_LABELS = FREQUENCY_OPTIONS.toMap()

private enum class DeleteMode { ONE, SERIES }

@Composable
internal fun JobEditorDialog(
    existing: ScheduledJob?,
    defaultDate: LocalDate? = null,
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
    var date by remember(existing?.id, defaultDate) {
        mutableStateOf(starts?.localDate()?.toString() ?: defaultDate?.toString() ?: todayIso())
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

    JwDialog(onDismissRequest = onDismiss, maxHeight = 760.dp) {
                DialogTitle(if (creating) "Book a job" else "Edit job")

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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TimeDropdown(
                        value = time,
                        onValueChange = { time = it },
                        label = "Starts at",
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(12.dp))
                    DurationDropdown(
                        selectedMinutes = durationValue,
                        modifier = Modifier.weight(1f),
                        onPick = { duration = it.toString() },
                    )
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
                    SettingSwitchRow(
                        title = "Repeats",
                        description = "Books the whole run on the calendar at once.",
                        checked = repeats,
                        onCheckedChange = { repeats = it },
                    )

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
                        shape = RoundedCornerShape(JwTokens.smallRadius),
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

    confirmDelete?.let { mode ->
        JwAlertDialog(
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
internal fun CompleteJobDialog(
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

    JwDialog(onDismissRequest = onDismiss, maxHeight = 720.dp) {
                DialogTitle("Complete job — $clientName")

                if (alreadyLogged != null && !enteringAnyway) {
                    Text(
                        text = "A visit is already logged for $clientName on ${parseDate(alreadyLogged.visitDate)?.formatDayMonthYear() ?: alreadyLogged.visitDate}.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(JwTokens.smallRadius),
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
                        SettingSwitchRow(
                            title = "Did not happen (missed)",
                            description = "Keeps the day as a record and charges nothing.",
                            checked = missed,
                            onCheckedChange = { missed = it },
                            enabled = !saving,
                        )
                    }

                    if (!missed) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            HoursDropdown(
                                value = hours,
                                onValueChange = { hours = it },
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
                            shape = RoundedCornerShape(JwTokens.smallRadius),
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

@Composable
internal fun MoveJobDialog(
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

    JwDialog(onDismissRequest = onDismiss) {
                DialogTitle("Move ${clientName}'s job")
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

private enum class SeriesSpacing(val label: String, val frequency: Frequency?) {
    KEEP("Keep existing days", null),
    WEEKLY("Weekly", Frequency.WEEKLY),
    FORTNIGHTLY("Fortnightly", Frequency.FORTNIGHTLY),
    FOUR_WEEKLY("Every 4 weeks", Frequency.FOUR_WEEKLY),
    MONTHLY("Monthly", Frequency.MONTHLY),
    QUARTERLY("Quarterly", Frequency.QUARTERLY),
}

@Composable
internal fun SeriesDialog(
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

    JwDialog(onDismissRequest = onDismiss, maxHeight = 720.dp) {
                DialogTitle("Edit the whole run")
                Text(
                    text = "$editableCount remaining ${if (editableCount == 1) "visit" else "visits"} will move. Completed and past visits stay where they are.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                TimeDropdown(
                    value = startTime,
                    onValueChange = { startTime = it },
                    label = "Start time",
                    modifier = Modifier.fillMaxWidth(),
                )
                DurationDropdown(
                    selectedMinutes = duration.toIntOrNull() ?: 0,
                    modifier = Modifier.fillMaxWidth(),
                    onPick = { duration = it.toString() },
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

/**
 * One tap for how long the job runs: a dropdown of the usual slots instead
 * of a free-text minutes field and four floating buttons. An odd existing
 * duration (45 minutes, say) is kept in the list so editing never silently
 * rounds it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DurationDropdown(
    selectedMinutes: Int,
    modifier: Modifier = Modifier,
    onPick: (Int) -> Unit,
) {
    val standard = listOf(30, 60, 90, 120, 150, 180, 240, 300, 360, 480)
    val options = remember(selectedMinutes) {
        if (selectedMinutes > 0 && selectedMinutes !in standard) {
            (standard + selectedMinutes).sorted()
        } else {
            standard
        }
    }
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = if (selectedMinutes > 0) durationLabel(selectedMinutes) else "",
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text("How long") },
            placeholder = { Text("Pick hours") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor()
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { minutes ->
                DropdownMenuItem(
                    text = { Text(durationLabel(minutes)) },
                    onClick = {
                        onPick(minutes)
                        expanded = false
                    },
                )
            }
        }
    }
}

private fun durationLabel(minutes: Int): String = when (minutes) {
    30 -> "30 minutes"
    60 -> "1 hour"
    90 -> "1½ hours"
    120 -> "2 hours"
    150 -> "2½ hours"
    180 -> "3 hours"
    240 -> "4 hours"
    300 -> "5 hours"
    360 -> "6 hours"
    480 -> "8 hours"
    else -> if (minutes % 60 == 0) {
        "${minutes / 60} hours"
    } else {
        "${minutes / 60}h ${minutes % 60}m".trim()
    }
}
