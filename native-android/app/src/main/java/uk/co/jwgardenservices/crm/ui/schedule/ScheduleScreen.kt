package uk.co.jwgardenservices.crm.ui.schedule

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.automirrored.outlined.Assignment
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import android.widget.Toast
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters
import kotlinx.coroutines.launch
import uk.co.jwgardenservices.crm.data.model.Client
import uk.co.jwgardenservices.crm.data.model.JobDraft
import uk.co.jwgardenservices.crm.data.model.ScheduledJob
import uk.co.jwgardenservices.crm.data.model.SeriesDraft
import uk.co.jwgardenservices.crm.data.model.SeriesEditInput
import uk.co.jwgardenservices.crm.data.model.Visit
import uk.co.jwgardenservices.crm.data.model.VisitDraft
import uk.co.jwgardenservices.crm.domain.BillingSource
import uk.co.jwgardenservices.crm.voice.CalendarEvent
import uk.co.jwgardenservices.crm.voice.CalendarSync
import uk.co.jwgardenservices.crm.voice.CalendarTag
import uk.co.jwgardenservices.crm.domain.Frequency
import uk.co.jwgardenservices.crm.domain.SeriesJob
import uk.co.jwgardenservices.crm.domain.editableVisits
import uk.co.jwgardenservices.crm.domain.formatDayMonth
import uk.co.jwgardenservices.crm.domain.formatDayMonthYear
import uk.co.jwgardenservices.crm.domain.formatTime
import uk.co.jwgardenservices.crm.domain.formatWeekday
import uk.co.jwgardenservices.crm.domain.hoursLabel
import uk.co.jwgardenservices.crm.domain.localDate
import uk.co.jwgardenservices.crm.domain.money
import uk.co.jwgardenservices.crm.domain.moneyCompact
import uk.co.jwgardenservices.crm.domain.monthlyRunningCosts
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
import uk.co.jwgardenservices.crm.ui.common.ShareText
import uk.co.jwgardenservices.crm.ui.common.StatusChip
import uk.co.jwgardenservices.crm.voice.VoiceLines


private enum class RotaView(val label: String) {
    Week("Week"),
    Month("Month"),
    List("List"),
}

@Composable
fun ScheduleScreen(viewModel: CrmViewModel, isStaff: Boolean) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val today = LocalDate.now()
    var rotaView by remember { mutableStateOf(RotaView.Week) }
    var weekCursor by remember { mutableStateOf(today) }
    var recurringOnly by remember { mutableStateOf(false) }
    var hideMoney by remember { mutableStateOf(false) }
    var selectedDay by remember { mutableStateOf(LocalDate.now()) }
    // Tapping a day in the calendar focuses the agenda on just that day;
    // null shows the running round again.
    var focusedDay by remember { mutableStateOf<LocalDate?>(null) }

    val days = remember(selectedDay, focusedDay) {
        focusedDay?.let { listOf(it) }
            ?: (0..20).map { selectedDay.plusDays(it.toLong()) }
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

    // Google/device calendars, read live. Business events keep their titles;
    // personal ones only appear as blocked time.
    val calendarContext = LocalContext.current
    val calendarTags by CalendarSync.tagsFlow(calendarContext)
        .collectAsStateWithLifecycle(initialValue = emptyMap())
    var calendarEvents by remember { mutableStateOf<List<CalendarEvent>>(emptyList()) }
    // Re-read when the day changes or a calendar is tagged in Settings.
    LaunchedEffect(selectedDay, focusedDay, calendarTags) {
        val from = focusedDay ?: selectedDay
        calendarEvents = if (CalendarSync.hasPermission(calendarContext)) {
            runCatching { CalendarSync.events(calendarContext, from, from.plusDays(20)) }
                .getOrDefault(emptyList())
        } else {
            emptyList()
        }
    }
    val eventsByDay: Map<LocalDate, List<CalendarEvent>> = remember(calendarEvents) {
        calendarEvents
            .groupBy { it.start.localDate() }
            .mapValues { (_, events) -> events.sortedBy { it.start } }
    }
    val hasAny = jobsByDay.values.any { it.isNotEmpty() } ||
        eventsByDay.values.any { it.isNotEmpty() }

    val weekStart = weekCursor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val weekDays = (0..6).map { weekStart.plusDays(it.toLong()) }
    val weekVisits = state.visits.filter { visit ->
        !visit.missed && parseDate(visit.visitDate)?.let {
            !it.isBefore(weekStart) && !it.isAfter(weekStart.plusDays(6))
        } == true
    }
    val weekJobs = state.jobs.filter { job ->
        job.status != "cancelled" &&
            parseTimestamp(job.startsAt)?.localDate()?.let {
                !it.isBefore(weekStart) && !it.isAfter(weekStart.plusDays(6))
            } == true
    }
    val labourRate = state.settings?.labourRate ?: 0.0
    val weekIncome = weekVisits.sumOf { it.amount }
    val weekMaterials = weekVisits.sumOf { it.materialsCost }
    val weekHours = weekJobs.sumOf { it.durationMinutes / 60.0 }
    val weekLabour = weekVisits.sumOf { it.hours } * labourRate
    val weekOverheads = state.runningCosts.monthlyRunningCosts() * 12.0 / 52.0 + weekMaterials
    val weekNet = weekIncome - weekLabour - weekOverheads
    val weekSummary = weekJobs
        .groupBy { it.clientId }
        .map { (clientId, jobs) ->
            Triple(
                clientNames[clientId] ?: "Client",
                jobs.mapNotNull { parseTimestamp(it.startsAt)?.localDate() }.distinct().size,
                jobs.sumOf { it.durationMinutes } / 60.0,
            )
        }
        .sortedByDescending { it.third }
    val weekWorkedDays = weekJobs
        .mapNotNull { parseTimestamp(it.startsAt)?.localDate() }
        .distinct()
        .size

    val agendaDays = when (rotaView) {
        RotaView.Month -> focusedDay?.let { listOf(it) } ?: days
        RotaView.List -> days
        RotaView.Week -> emptyList()
    }
    var monthCursor by remember { mutableStateOf(YearMonth.now()) }
    val rangeLabel = when (rotaView) {
        RotaView.Week -> "Week of ${weekStart.formatDayMonth()} – " +
            "${weekStart.plusDays(6).formatDayMonth()}"
        RotaView.Month -> monthCursor.format(
            java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy", java.util.Locale.UK),
        )
        RotaView.List -> "Next three weeks"
    }

    var booking by remember { mutableStateOf(false) }
    var bookingDate by remember { mutableStateOf<LocalDate?>(null) }
    var editing by remember { mutableStateOf<ScheduledJob?>(null) }
    var completing by remember { mutableStateOf<ScheduledJob?>(null) }
    var seriesJob by remember { mutableStateOf<ScheduledJob?>(null) }
    var movingJob by remember { mutableStateOf<ScheduledJob?>(null) }
    var pendingSeriesMove by remember { mutableStateOf<Pair<ScheduledJob, LocalDate>?>(null) }
    var sheetJob by remember { mutableStateOf<ScheduledJob?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var route by remember { mutableStateOf<Pair<String, List<RouteStop>>?>(null) }
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
        bookingDate = null
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

    Box(modifier = Modifier.fillMaxSize()) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            PageHeader(
                title = "Rota",
                description = rangeLabel,
                action = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = {
                            when (rotaView) {
                                RotaView.Week -> weekCursor = weekCursor.minusWeeks(1)
                                RotaView.Month -> monthCursor = monthCursor.minusMonths(1)
                                RotaView.List -> selectedDay = selectedDay.minusWeeks(1)
                            }
                        }) {
                            Icon(Icons.Outlined.ChevronLeft, contentDescription = "Previous")
                        }
                        OutlinedButton(
                            onClick = {
                                selectedDay = today
                                weekCursor = today
                                monthCursor = YearMonth.now()
                                focusedDay = if (rotaView == RotaView.Month) today else null
                            },
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                            modifier = Modifier.height(34.dp),
                        ) {
                            Text("Today", style = MaterialTheme.typography.labelLarge)
                        }
                        IconButton(onClick = {
                            when (rotaView) {
                                RotaView.Week -> weekCursor = weekCursor.plusWeeks(1)
                                RotaView.Month -> monthCursor = monthCursor.plusMonths(1)
                                RotaView.List -> selectedDay = selectedDay.plusWeeks(1)
                            }
                        }) {
                            Icon(Icons.Outlined.ChevronRight, contentDescription = "Next")
                        }
                    }
                },
            )
        }

        item {
            RotaUtilities(
                recurringOnly = recurringOnly,
                hideMoney = hideMoney,
                onRecurring = { recurringOnly = !recurringOnly },
                onShare = {
                    val text = buildString {
                        appendLine("Rota — $rangeLabel")
                        weekDays.forEach { day ->
                            val jobs = jobsByDay[day].orEmpty().sortedBy { it.startsAt }
                            if (jobs.isNotEmpty()) {
                                appendLine(
                                    day.formatDayMonth() + ": " + jobs.joinToString("; ") { job ->
                                        val start = parseTimestamp(job.startsAt)?.formatTime() ?: "—"
                                        "${clientNames[job.clientId] ?: "Client"} $start"
                                    },
                                )
                            }
                        }
                    }
                    ShareText.share(appContext, text, "Share schedule")
                },
                onHideMoney = { hideMoney = !hideMoney },
            )
        }

        item {
            RotaViewTabs(
                view = rotaView,
                onView = { next ->
                    rotaView = next
                    focusedDay = if (next == RotaView.Month) selectedDay else null
                    if (next == RotaView.Week) weekCursor = selectedDay
                },
            )
        }

        if (rotaView == RotaView.Week) {
            item {
                WeekTotals(
                    income = weekIncome,
                    labour = weekLabour,
                    overheads = weekOverheads,
                    net = weekNet,
                    materials = weekMaterials,
                    hideMoney = hideMoney,
                )
            }
            item {
                WeekBoard(
                    days = weekDays,
                    jobsByDay = jobsByDay,
                    today = today,
                    clients = state.clients,
                    clientNames = clientNames,
                    isStaff = isStaff,
                    recurringOnly = recurringOnly,
                    onOpenJob = { sheetJob = it },
                    onAdd = { day ->
                        saveError = null
                        bookingDate = day
                        booking = true
                    },
                )
            }
            item {
                StaffSummary(
                    staffNames = state.team.mapNotNull {
                        it.name?.takeIf { name -> name.isNotBlank() } ?: it.email
                    },
                    ownerName = VoiceLines.OWNER_NAME,
                    days = weekWorkedDays,
                    hours = weekHours,
                )
            }
            return@LazyColumn
        }

        if (rotaView == RotaView.Month) {
            item {
                MonthCalendar(
                    jobs = jobsByDay,
                    calendarEventDays = eventsByDay.mapValues { (_, events) -> events.size },
                    month = monthCursor,
                    selectedDay = selectedDay,
                    onMonthChange = { monthCursor = it },
                    onSelectDay = { day ->
                        selectedDay = day
                        focusedDay = day
                        monthCursor = YearMonth.from(day)
                        scope.launch { listState.animateScrollToItem(2) }
                    },
                    canDrag = !isStaff,
                    clientName = { id -> clientNames[id] ?: "Client" },
                    onMove = { job, day -> moveJobTo(job, day) },
                )
            }
        }

        val focused = focusedDay
        if (focused != null && rotaView == RotaView.Month) {
            item {
                TextButton(onClick = { focusedDay = null }) {
                    Text("Showing ${focused.formatDayMonth()} — show the whole round")
                }
            }
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
                    body = when {
                        !CalendarSync.hasPermission(calendarContext) ->
                            "Allow calendar access in Settings → Google Calendar to show " +
                                "Google events here. Jobs booked in the app still appear."
                        calendarTags.values.none { it != CalendarTag.Ignore } ->
                            "Calendar access is on, but no calendar is tagged yet. In Settings " +
                                "→ Google Calendar set yours to Mixed (Personal: prefix)."
                        else -> "Book the first job with the button above. Repeating runs go on " +
                            "the calendar all at once."
                    },
                )
            }
        }

        agendaDays.forEach { day ->
            val jobs = jobsByDay[day].orEmpty().sortedBy { it.startsAt }
            val events = eventsByDay[day].orEmpty()
            if (day == selectedDay || jobs.isNotEmpty() || events.isNotEmpty()) {
                item(key = "day-$day") { DayHeading(day = day, isToday = day == today) }
                if (day == selectedDay && jobs.isNotEmpty()) {
                    val stops = jobs.map { job ->
                        val client = state.clients.firstOrNull { it.id == job.clientId }
                        RouteStop(
                            time = parseTimestamp(job.startsAt),
                            client = client?.name ?: clientNames[job.clientId] ?: "Client",
                            address = client?.address,
                            phone = client?.phone,
                        )
                    }
                    if (stops.any { !it.address.isNullOrBlank() }) {
                        item(key = "route-$day") {
                            TextButton(
                                onClick = { route = day.formatDayMonthYear() to stops },
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Navigation,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("Route")
                            }
                        }
                    }
                }
                if (jobs.isEmpty() && events.isEmpty()) {
                    item(key = "empty-$day") {
                        Text(
                            text = "Nothing booked on this day.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                        )
                    }
                } else {
                    items(events, key = { "cal-${it.eventId}-${it.start.epochSecond}" }) { event ->
                        CalendarEventRow(event)
                    }
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

    if (!isStaff) {
        Surface(
            onClick = {
                saveError = null
                bookingDate = today
                booking = true
            },
            shape = CircleShape,
            color = MaterialTheme.colorScheme.tertiary,
            contentColor = MaterialTheme.colorScheme.onTertiary,
            shadowElevation = 8.dp,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(20.dp)
                .size(56.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Add, contentDescription = "Add a job")
            }
        }
    }
    }

    route?.let { (title, stops) ->
        RouteDialog(
            title = "Route for $title",
            stops = stops,
            onDismiss = { route = null },
        )
    }

    if (booking) {
        JobEditorDialog(
            existing = null,
            defaultDate = bookingDate,
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
private fun RotaUtilities(
    recurringOnly: Boolean,
    hideMoney: Boolean,
    onRecurring: () -> Unit,
    onShare: () -> Unit,
    onHideMoney: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        UtilityLink(
            icon = Icons.Outlined.Repeat,
            label = "Recurring visits",
            active = recurringOnly,
            onClick = onRecurring,
        )
        UtilityLink(
            icon = Icons.Outlined.Share,
            label = "Share schedule",
            onClick = onShare,
        )
        Spacer(Modifier.weight(1f))
        UtilityLink(
            icon = if (hideMoney) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
            label = if (hideMoney) "Show £" else "Hide £",
            active = hideMoney,
            onClick = onHideMoney,
        )
    }
}

@Composable
private fun UtilityLink(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = if (active) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = if (active) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}

@Composable
private fun RotaViewTabs(view: RotaView, onView: (RotaView) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.secondary,
    ) {
        Row(modifier = Modifier.padding(3.dp)) {
            RotaView.entries.forEach { option ->
                val active = option == view
                Surface(
                    onClick = { onView(option) },
                    shape = RoundedCornerShape(50),
                    color = if (active) MaterialTheme.colorScheme.primary else Color.Transparent,
                    contentColor = if (active) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = option.label,
                        style = MaterialTheme.typography.labelLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun RotaControls(
    view: RotaView,
    onView: (RotaView) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onPrevious) {
            Icon(Icons.Outlined.ChevronLeft, contentDescription = "Previous")
        }
        OutlinedButton(
            onClick = onToday,
            shape = RoundedCornerShape(10.dp),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            modifier = Modifier.height(34.dp),
        ) {
            Text("Today", style = MaterialTheme.typography.labelLarge)
        }
        IconButton(onClick = onNext) {
            Icon(Icons.Outlined.ChevronRight, contentDescription = "Next")
        }
        Spacer(Modifier.weight(1f))
        Row(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.secondary, RoundedCornerShape(50))
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            RotaView.entries.forEach { option ->
                val active = option == view
                Surface(
                    onClick = { onView(option) },
                    shape = RoundedCornerShape(50),
                    color = if (active) MaterialTheme.colorScheme.primary else Color.Transparent,
                    contentColor = if (active) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                ) {
                    Text(
                        text = option.label,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun WeekTotals(
    income: Double,
    labour: Double,
    overheads: Double,
    net: Double,
    materials: Double,
    hideMoney: Boolean,
) {
    fun value(amount: Double): String = if (hideMoney) "•••" else moneyCompact(amount)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "WEEK TOTALS",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.55f),
            )
            Spacer(Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                WeekFigure("Income", value(income), Modifier.weight(1f), brass = true)
                WeekFigure("Labour", value(labour), Modifier.weight(1f), brass = true)
                WeekFigure("Overheads", value(overheads), Modifier.weight(1f), brass = true)
                WeekFigure("Net profit", value(net), Modifier.weight(1f), brass = true)
            }
            if (materials > 0.0) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Overheads include ${if (hideMoney) "materials" else moneyCompact(materials)} logged this week.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.5f),
                )
            }
        }
    }
}

@Composable
private fun WeekFigure(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    brass: Boolean = false,
) {
    Column(modifier = modifier) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = if (brass) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onPrimary,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.55f),
        )
    }
}

@Composable
private fun WeekBoard(
    days: List<LocalDate>,
    jobsByDay: Map<LocalDate, List<ScheduledJob>>,
    today: LocalDate,
    clients: List<Client>,
    clientNames: Map<String, String>,
    isStaff: Boolean,
    recurringOnly: Boolean,
    onOpenJob: (ScheduledJob) -> Unit,
    onAdd: (LocalDate) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        days.forEach { day ->
            val jobs = jobsByDay[day].orEmpty()
                .filter { !recurringOnly || it.seriesId != null }
                .sortedBy { it.startsAt }
            Column(modifier = Modifier.width(150.dp)) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.secondary,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    border = if (day == today) {
                        BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                    } else {
                        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    },
                ) {
                    Column(
                        modifier = Modifier.padding(vertical = 7.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = day.format(java.time.format.DateTimeFormatter.ofPattern(
                                "EEE",
                                java.util.Locale.UK,
                            )).uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (day == today) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        Text(
                            text = day.dayOfMonth.toString(),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (day == today) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                }
                Spacer(Modifier.height(7.dp))
                jobs.forEach { job ->
                    WeekJobCard(
                        job = job,
                        clientName = clientNames[job.clientId] ?: "Client",
                        address = clients.firstOrNull { it.id == job.clientId }?.address,
                        onClick = { onOpenJob(job) },
                    )
                    Spacer(Modifier.height(6.dp))
                }
                if (!isStaff) {
                    DashedAddSlot(onClick = { onAdd(day) })
                }
            }
        }
    }
}

@Composable
private fun DashedAddSlot(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .drawBehind {
                drawRoundRect(
                    color = Color(0xFF9CA88F),
                    style = Stroke(
                        width = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(9f, 7f)),
                    ),
                    cornerRadius = CornerRadius(10.dp.toPx()),
                )
            }
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "+ Add",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun WeekJobCard(
    job: ScheduledJob,
    clientName: String,
    address: String?,
    onClick: () -> Unit,
) {
    val starts = parseTimestamp(job.startsAt)
    val end = starts?.plusSeconds((job.durationMinutes * 60).toLong())
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .heightIn(min = 68.dp)
                    .background(
                        if (job.status == "done") {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.tertiary
                        },
                    ),
            )
            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp)) {
                Text(
                    text = clientName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                if (!address.isNullOrBlank()) {
                    Text(
                        text = address,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "${starts?.formatTime() ?: "—"}–${end?.formatTime() ?: "—"}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "${job.durationMinutes} min",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun StaffSummary(
    staffNames: List<String>,
    ownerName: String,
    days: Int,
    hours: Double,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Staff Summary — This Week",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.Outlined.KeyboardArrowUp,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(18.dp),
                )
            }
            if (staffNames.isEmpty()) {
                StaffRow(ownerName, days, hours)
            } else {
                staffNames.forEachIndexed { index, name ->
                    if (index > 0) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                    StaffRow(name, null, null)
                }
                Text(
                    text = "Staff hours need jobs assigned to people; that isn't recorded yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun StaffRow(name: String, days: Int?, hours: Double?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = days?.let { "$it ${if (it == 1) "day" else "days"}" } ?: "— days",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = hours?.let { "${it.toInt()} hrs" } ?: "— hrs",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun WeekClientSummary(summary: List<Triple<String, Int, Double>>) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(
                    text = "Clients — this week",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
            if (summary.isEmpty()) {
                Text(
                    text = "Nothing on the rota this week yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                summary.take(10).forEachIndexed { index, (name, days, hours) ->
                    if (index > 0) {
                        HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = name,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "$days ${if (days == 1) "day" else "days"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = "${hours.toInt()} hrs",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
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
private fun CalendarEventRow(event: CalendarEvent) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (event.allDay) "All day" else event.start.formatTime(),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(64.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = event.title,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = if (event.business) {
                        "${event.calendarName} · Google Calendar"
                    } else {
                        "Personal · busy only"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
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

