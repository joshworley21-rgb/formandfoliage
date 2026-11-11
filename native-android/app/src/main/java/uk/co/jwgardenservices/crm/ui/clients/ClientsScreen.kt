package uk.co.jwgardenservices.crm.ui.clients

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import uk.co.jwgardenservices.crm.data.model.Client
import uk.co.jwgardenservices.crm.data.model.ClientDraft
import uk.co.jwgardenservices.crm.domain.filterBy
import uk.co.jwgardenservices.crm.domain.money
import uk.co.jwgardenservices.crm.ui.CrmViewModel
import uk.co.jwgardenservices.crm.ui.common.EmptyState
import uk.co.jwgardenservices.crm.ui.common.ErrorState
import uk.co.jwgardenservices.crm.ui.common.LoadingState
import uk.co.jwgardenservices.crm.ui.common.PageHeader

@Composable
fun ClientsScreen(viewModel: CrmViewModel, isStaff: Boolean) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Client?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }

    val filtered = remember(state.clients, query) {
        filterBy(state.clients, query) { client ->
            listOf(client.name, client.address, client.phone, client.email)
        }
    }

    if (state.loading) {
        Column(Modifier.padding(20.dp)) {
            PageHeader("Clients", "Rates, contact details and who to chase.")
            LoadingState()
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                PageHeader(
                    title = "Clients",
                    description = if (isStaff) {
                        "Names, addresses and notes for the round."
                    } else {
                        "Rates, contact details and who to chase."
                    },
                    action = {
                        if (!isStaff) {
                            Button(onClick = { saveError = null; creating = true }) {
                                Icon(Icons.Outlined.Add, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Add")
                            }
                        }
                    },
                )
            }

            if (state.error != null) {
                item { ErrorState(state.error!!, viewModel::refresh) }
            }

            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("Name, address or postcode") },
                    leadingIcon = {
                        Icon(Icons.Outlined.Search, contentDescription = null)
                    },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }) {
                                Icon(Icons.Outlined.Clear, contentDescription = "Clear search")
                            }
                        }
                    },
                    shape = RoundedCornerShape(16.dp),
                )
            }

            if (state.clients.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Outlined.People,
                        title = "No clients yet",
                        body = "Add the first one to start booking jobs and logging visits.",
                    )
                }
                return@LazyColumn
            }

            if (filtered.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Outlined.Search,
                        title = "Nothing matches \u201c$query\u201d",
                        body = "Try a shorter search, or a postcode without the space.",
                    )
                }
                return@LazyColumn
            }

            items(items = filtered, key = { it.id }) { client ->
                ClientCard(
                    client = client,
                    isStaff = isStaff,
                    onClick = if (isStaff) {
                        null
                    } else {
                        { saveError = null; editing = client }
                    },
                )
            }
        }
    }

    if (!isStaff && (creating || editing != null)) {
        ClientEditorDialog(
            existing = editing,
            saving = state.saving,
            errorMessage = saveError,
            onDismiss = {
                if (!state.saving) {
                    creating = false
                    editing = null
                    saveError = null
                }
            },
            onSave = { draft ->
                scope.launch {
                    saveError = null
                    try {
                        viewModel.saveClient(draft, editing)
                        creating = false
                        editing = null
                    } catch (error: Exception) {
                        saveError = error.message ?: "Could not save the client"
                    }
                }
            },
            onDelete = editing?.let { client ->
                {
                    scope.launch {
                        try {
                            viewModel.deleteClient(client.id)
                            creating = false
                            editing = null
                        } catch (error: Exception) {
                            saveError = error.message ?: "Could not delete the client"
                        }
                    }
                    Unit
                }
            },
        )
    }
}

@Composable
private fun ClientCard(client: Client, isStaff: Boolean, onClick: (() -> Unit)?) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = onClick != null) { onClick?.invoke() },
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = client.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (client.paymentReminders) {
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            imageVector = Icons.Outlined.Notifications,
                            contentDescription = "Chased for unpaid invoices",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
                Text(
                    text = client.address ?: "No address saved",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!isStaff) {
                    Text(
                        text = if (client.billingMode == "fixed") {
                            "${money(client.fixedPrice)} per visit"
                        } else {
                            "${money(client.hourlyRate)} per hour"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (!isStaff) {
                Icon(
                    imageVector = Icons.Outlined.ChevronRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ClientEditorDialog(
    existing: Client?,
    saving: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onSave: (ClientDraft) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var name by remember(existing?.id) { mutableStateOf(existing?.name.orEmpty()) }
    var address by remember(existing?.id) { mutableStateOf(existing?.address.orEmpty()) }
    var phone by remember(existing?.id) { mutableStateOf(existing?.phone.orEmpty()) }
    var email by remember(existing?.id) { mutableStateOf(existing?.email.orEmpty()) }
    var notes by remember(existing?.id) { mutableStateOf(existing?.notes.orEmpty()) }
    var billingMode by remember(existing?.id) { mutableStateOf(existing?.billingMode ?: "hourly") }
    var hourlyRate by remember(existing?.id) {
        mutableStateOf(existing?.hourlyRate?.let { if (it == 0.0) "" else it.toString() } ?: "")
    }
    var fixedPrice by remember(existing?.id) {
        mutableStateOf(existing?.fixedPrice?.let { if (it == 0.0) "" else it.toString() } ?: "")
    }
    var reminders by remember(existing?.id) { mutableStateOf(existing?.paymentReminders ?: false) }
    var confirmDelete by remember { mutableStateOf(false) }

    val valid = name.isNotBlank()

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
                    text = if (existing == null) "Add client" else "Edit client",
                    style = MaterialTheme.typography.headlineSmall,
                )

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text("Address") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text("Phone") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes") },
                    placeholder = { Text("Gate code, dog, bin day…") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                )

                Text("How this client is charged", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = billingMode == "hourly",
                        onClick = { billingMode = "hourly" },
                        label = { Text("Hourly") },
                    )
                    FilterChip(
                        selected = billingMode == "fixed",
                        onClick = { billingMode = "fixed" },
                        label = { Text("Fixed price") },
                    )
                }
                if (billingMode == "fixed") {
                    OutlinedTextField(
                        value = fixedPrice,
                        onValueChange = { fixedPrice = it },
                        label = { Text("Price per visit (£)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    OutlinedTextField(
                        value = hourlyRate,
                        onValueChange = { hourlyRate = it },
                        label = { Text("Hourly rate (£)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = reminders, onCheckedChange = { reminders = it })
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Chase this client about unpaid invoices",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = "Off by default. When on, an invoice past its due date is chased weekly.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
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

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp),
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
                        enabled = valid && !saving,
                        onClick = {
                            onSave(
                                ClientDraft(
                                    name = name,
                                    address = address,
                                    phone = phone,
                                    email = email,
                                    notes = notes,
                                    billingMode = billingMode,
                                    hourlyRate = hourlyRate.toDoubleOrNull() ?: 0.0,
                                    fixedPrice = fixedPrice.toDoubleOrNull() ?: 0.0,
                                    paymentReminders = reminders,
                                ),
                            )
                        },
                    ) {
                        Text(if (existing == null) "Add client" else "Save")
                    }
                }
            }
        }
    }

    if (confirmDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${existing?.name.orEmpty()}?") },
            text = { Text("This removes the client and every job, visit and invoice attached to them.") },
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
