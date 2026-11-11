package uk.co.jwgardenservices.crm.ui.visits

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import uk.co.jwgardenservices.crm.data.model.Client
import uk.co.jwgardenservices.crm.data.model.Invoice
import uk.co.jwgardenservices.crm.data.model.ScheduledJob
import uk.co.jwgardenservices.crm.data.model.Visit
import uk.co.jwgardenservices.crm.data.model.VisitDraft
import uk.co.jwgardenservices.crm.domain.PayMode
import uk.co.jwgardenservices.crm.domain.JobLink
import uk.co.jwgardenservices.crm.domain.findJobForVisit
import uk.co.jwgardenservices.crm.domain.formatDayMonthYear
import uk.co.jwgardenservices.crm.domain.formatTime
import uk.co.jwgardenservices.crm.domain.hoursLabel
import uk.co.jwgardenservices.crm.domain.money
import uk.co.jwgardenservices.crm.domain.parseDate
import uk.co.jwgardenservices.crm.domain.parseTimestamp
import uk.co.jwgardenservices.crm.domain.VisitMoneyInput
import uk.co.jwgardenservices.crm.domain.todayIso
import uk.co.jwgardenservices.crm.domain.visitAmount
import uk.co.jwgardenservices.crm.ui.CrmViewModel
import uk.co.jwgardenservices.crm.ui.common.DateField
import uk.co.jwgardenservices.crm.ui.common.EmptyState
import uk.co.jwgardenservices.crm.ui.common.ErrorState
import uk.co.jwgardenservices.crm.ui.common.LoadingState
import uk.co.jwgardenservices.crm.ui.common.PageHeader
import uk.co.jwgardenservices.crm.ui.common.StatCard
import uk.co.jwgardenservices.crm.ui.common.StatusChip

@Composable
fun VisitsScreen(viewModel: CrmViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val unbilled = state.visits.filter { !it.billed && !it.missed }
    val clientNames = remember(state.clients) { state.clients.associate { it.id to it.name } }

    var creating by remember { mutableStateOf(false) }
    var defaultMissed by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Visit?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }

    fun closeEditor() {
        creating = false
        editing = null
        saveError = null
    }

    if (state.loading) {
        Column(Modifier.padding(20.dp)) {
            PageHeader("Visits", "Hours, materials and the days that did not happen.")
            LoadingState()
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            PageHeader(
                title = "Visits",
                description = "Hours, materials and the days that did not happen.",
                action = {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(
                            onClick = {
                                saveError = null
                                defaultMissed = true
                                creating = true
                            },
                        ) {
                            Text("Missed")
                        }
                        Button(
                            onClick = {
                                saveError = null
                                defaultMissed = false
                                creating = true
                            },
                        ) {
                            Icon(Icons.Outlined.Add, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Log visit")
                        }
                    }
                },
            )
        }

        if (state.error != null) {
            item { ErrorState(state.error!!, viewModel::refresh) }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatCard(
                    label = "Unbilled visits",
                    value = unbilled.size.toString(),
                    icon = Icons.Outlined.Checklist,
                    modifier = Modifier.weight(1f),
                )
                StatCard(
                    label = "Ready to invoice",
                    value = money(unbilled.sumOf { it.amount }),
                    icon = Icons.Outlined.Payments,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (state.visits.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Outlined.Checklist,
                    title = "No visits logged",
                    body = "Log the first visit, or record a day you could not get in.",
                )
            }
            return@LazyColumn
        }

        items(state.visits, key = { it.id }) { visit ->
            VisitCard(
                visit = visit,
                clientName = clientNames[visit.clientId] ?: "Client",
                onClick = {
                    saveError = null
                    editing = visit
                },
            )
        }
    }

    if (creating || editing != null) {
        VisitEditorDialog(
            existing = editing,
            defaultMissed = defaultMissed,
            clients = state.clients,
            jobs = state.jobs,
            invoices = state.invoices,
            saving = state.saving,
            errorMessage = saveError,
            onDismiss = { if (!state.saving) closeEditor() },
            onSave = { draft ->
                scope.launch {
                    saveError = null
                    try {
                        viewModel.saveVisit(draft)
                        closeEditor()
                    } catch (error: Exception) {
                        saveError = error.message ?: "Could not save the visit"
                    }
                }
            },
            onDelete = editing?.let { visit ->
                if (visit.billed) {
                    null
                } else {
                    {
                        scope.launch {
                            try {
                                viewModel.deleteVisit(visit.id)
                                closeEditor()
                            } catch (error: Exception) {
                                saveError = error.message ?: "Could not delete the visit"
                            }
                        }
                        Unit
                    }
                }
            },
        )
    }
}

@Composable
private fun VisitCard(visit: Visit, clientName: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = clientName,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (visit.missed) "—" else money(visit.amount),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.width(6.dp))
                Icon(
                    imageVector = Icons.Outlined.ChevronRight,
                    contentDescription = "Edit visit",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.padding(top = 6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = parseDate(visit.visitDate)?.formatDayMonthYear() ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                when {
                    visit.missed -> StatusChip(
                        label = "Missed",
                        color = MaterialTheme.colorScheme.error,
                        container = MaterialTheme.colorScheme.error.copy(alpha = 0.10f),
                    )
                    visit.billed -> StatusChip(
                        label = "Invoiced",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        container = MaterialTheme.colorScheme.secondary,
                    )
                    else -> StatusChip(
                        label = "Unbilled",
                        color = MaterialTheme.colorScheme.primary,
                        container = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    )
                }
            }
            if (!visit.missed) {
                Text(
                    text = hoursLabel(visit.hours) +
                        if (visit.materialsCost > 0) {
                            " · materials ${money(visit.materialsCost)}" +
                                (visit.materialsNote?.let { " ($it)" } ?: "")
                        } else {
                            ""
                        },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!visit.description.isNullOrBlank()) {
                Text(
                    text = visit.description.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun VisitEditorDialog(
    existing: Visit?,
    defaultMissed: Boolean,
    clients: List<Client>,
    jobs: List<ScheduledJob>,
    invoices: List<Invoice>,
    saving: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onSave: (VisitDraft) -> Unit,
    onDelete: (() -> Unit)?,
) {
    val initialClient = existing?.clientId ?: clients.firstOrNull()?.id.orEmpty()
    val initialClientRecord = clients.find { it.id == initialClient }
    val initialMode = if (initialClientRecord?.billingMode == "fixed") PayMode.DAY else PayMode.HOURLY
    fun rateFor(client: Client?, mode: PayMode): String =
        ((if (mode == PayMode.DAY) client?.fixedPrice else client?.hourlyRate) ?: 0.0)
            .let { if (it == 0.0) "" else it.toString() }

    var clientId by remember(existing?.id) { mutableStateOf(initialClient) }
    var payMode by remember(existing?.id) { mutableStateOf(initialMode) }
    var rate by remember(existing?.id) {
        mutableStateOf(rateFor(initialClientRecord, initialMode))
    }
    var days by remember(existing?.id) { mutableStateOf("1") }
    var date by remember(existing?.id) { mutableStateOf(existing?.visitDate ?: todayIso()) }
    var hours by remember(existing?.id) {
        mutableStateOf(existing?.hours?.takeIf { it > 0 }?.toString() ?: "2")
    }
    var materials by remember(existing?.id) {
        mutableStateOf(existing?.materialsCost?.takeIf { it > 0 }?.toString() ?: "0")
    }
    var materialsNote by remember(existing?.id) { mutableStateOf(existing?.materialsNote.orEmpty()) }
    var description by remember(existing?.id) { mutableStateOf(existing?.description.orEmpty()) }
    var missed by remember(existing?.id) { mutableStateOf(existing?.missed ?: defaultMissed) }
    var confirmDelete by remember { mutableStateOf(false) }

    val invoiceStatus = existing?.invoiceId?.let { id -> invoices.find { it.id == id }?.status }
    val locked = existing?.billed == true && invoiceStatus != "draft"
    val client = clients.find { it.id == clientId }

    val parsedHours = hours.toDoubleOrNull() ?: 0.0
    val parsedMaterials = materials.toDoubleOrNull() ?: 0.0
    val amount = visitAmount(
        VisitMoneyInput(
            locked = locked,
            missed = missed,
            payMode = payMode,
            hours = hours,
            materials = materials,
            rate = rate,
            days = days,
        ),
    )

    val matchedJob = if (existing == null && !locked) {
        findJobForVisit(
            jobs.mapNotNull { job ->
                parseTimestamp(job.startsAt)?.let { starts ->
                    JobLink(job.id, job.clientId, starts, job.status, job.visitId)
                }
            },
            clientId,
            date,
        )
    } else {
        null
    }

    val dateValid = parseDate(date) != null
    val canSave = clientId.isNotBlank() && dateValid && !saving

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
                    text = if (existing == null) "Log a visit" else "Edit visit",
                    style = MaterialTheme.typography.headlineSmall,
                )

                if (locked) {
                    Text(
                        text = "This visit is on an invoice that has been sent, so its hours, materials and charge are fixed. You can still change what the work was.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                ClientPicker(
                    clients = clients,
                    selectedId = clientId,
                    enabled = !locked,
                    onSelect = { selected ->
                        clientId = selected
                        val selectedClient = clients.find { it.id == selected }
                        payMode = if (selectedClient?.billingMode == "fixed") {
                            PayMode.DAY
                        } else {
                            PayMode.HOURLY
                        }
                        rate = rateFor(selectedClient, payMode)
                        days = "1"
                    },
                )

                DateField(
                    value = date,
                    onValueChange = { date = it },
                    label = "Date",
                    enabled = !locked,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = missed,
                        onCheckedChange = { missed = it },
                        enabled = !locked,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Missed visit",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = "Kept as a record, charged at nothing, and left out of invoices.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                if (!missed) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = hours,
                            onValueChange = { hours = it },
                            label = { Text("Hours") },
                            singleLine = true,
                            enabled = !locked,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = materials,
                            onValueChange = { materials = it },
                            label = { Text("Materials (£)") },
                            singleLine = true,
                            enabled = !locked,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (parsedMaterials > 0 && !locked) {
                        OutlinedTextField(
                            value = materialsNote,
                            onValueChange = { materialsNote = it },
                            label = { Text("What the materials were") },
                            placeholder = { Text("2 bags compost, bark chippings") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    if (!locked) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = payMode == PayMode.HOURLY,
                                onClick = {
                                    payMode = PayMode.HOURLY
                                    rate = rateFor(client, PayMode.HOURLY)
                                },
                                label = { Text("Hourly rate") },
                            )
                            FilterChip(
                                selected = payMode == PayMode.DAY,
                                onClick = {
                                    payMode = PayMode.DAY
                                    rate = rateFor(client, PayMode.DAY)
                                },
                                label = { Text("Day rate") },
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedTextField(
                                value = rate,
                                onValueChange = { rate = it },
                                label = {
                                    Text(if (payMode == PayMode.DAY) "Day rate (£)" else "Hourly rate (£)")
                                },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.weight(1f),
                            )
                            if (payMode == PayMode.DAY) {
                                OutlinedTextField(
                                    value = days,
                                    onValueChange = { days = it },
                                    label = { Text("Days worked") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                        Text(
                            text = "The rate comes from the client record; change it here for this visit only.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(if (missed) "What happened" else "Work done") },
                    placeholder = {
                        Text(
                            if (missed) "Gate locked, ground too wet, cancelled on the day…"
                            else "Mowed lawns, hedge trim, cleared beds…",
                        )
                    },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.secondary,
                ) {
                    Text(
                        text = when {
                            locked -> "Invoiced at: ${money(existing?.amount ?: 0.0)}"
                            missed -> "Charge: nothing (this visit did not happen)"
                            else -> "Charge: ${money(amount)} (" +
                                (if (payMode == PayMode.DAY) "day rate" else "hourly rate") +
                                " + materials)"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }

                if (matchedJob != null) {
                    Text(
                        text = "Saving will mark the ${matchedJob.startsAt.formatTime()} job on ${parseDate(date)?.formatDayMonthYear() ?: date} as ${if (missed) "missed" else "done"}.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
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
                    if (onDelete != null) {
                        TextButton(onClick = { confirmDelete = true }, enabled = !saving) {
                            Text("Delete", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss, enabled = !saving) { Text("Cancel") }
                    Spacer(Modifier.width(6.dp))
                    Button(
                        enabled = canSave,
                        onClick = {
                            onSave(
                                VisitDraft(
                                    id = existing?.id,
                                    clientId = clientId,
                                    visitDate = date,
                                    hours = parsedHours,
                                    materialsCost = parsedMaterials,
                                    materialsNote = materialsNote,
                                    description = description,
                                    amount = amount,
                                    missed = missed,
                                    completesJobId = matchedJob?.id,
                                    jobOutcome = if (missed) "missed" else "done",
                                ),
                            )
                        },
                    ) {
                        Text(if (existing == null) "Save visit" else "Save changes")
                    }
                }
            }
        }
    }

    if (confirmDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this visit?") },
            text = { Text("It will be removed from the log and from any pending sync.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete()
                    },
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun ClientPicker(
    clients: List<Client>,
    selectedId: String,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val selected = clients.find { it.id == selectedId }

    Box(modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { open = true },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = selected?.name ?: "Choose client",
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Start,
            )
            Icon(Icons.Outlined.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            clients.forEach { client ->
                DropdownMenuItem(
                    text = { Text(client.name) },
                    onClick = {
                        open = false
                        onSelect(client.id)
                    },
                )
            }
        }
    }
}
