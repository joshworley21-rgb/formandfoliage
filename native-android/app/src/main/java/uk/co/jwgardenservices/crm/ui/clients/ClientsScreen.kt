package uk.co.jwgardenservices.crm.ui.clients

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import uk.co.jwgardenservices.crm.ui.common.JwAlertDialog
import uk.co.jwgardenservices.crm.data.model.Client
import uk.co.jwgardenservices.crm.data.model.JobPhotoWithUrl
import uk.co.jwgardenservices.crm.data.model.ClientDraft
import uk.co.jwgardenservices.crm.domain.filterBy
import uk.co.jwgardenservices.crm.domain.money
import uk.co.jwgardenservices.crm.ui.CrmViewModel
import uk.co.jwgardenservices.crm.ui.common.ContactButtons
import uk.co.jwgardenservices.crm.ui.common.DialogTitle
import uk.co.jwgardenservices.crm.ui.common.EmptyState
import uk.co.jwgardenservices.crm.ui.common.ErrorState
import uk.co.jwgardenservices.crm.ui.common.JwDialog
import uk.co.jwgardenservices.crm.ui.common.LoadingState
import uk.co.jwgardenservices.crm.ui.common.SettingSwitchRow
import uk.co.jwgardenservices.crm.ui.theme.JwTokens
import androidx.compose.material3.OutlinedTextFieldDefaults
import uk.co.jwgardenservices.crm.ui.common.CompactScreenHeader
import uk.co.jwgardenservices.crm.ui.common.FilterPill
import uk.co.jwgardenservices.crm.ui.common.JwCard
import uk.co.jwgardenservices.crm.ui.common.PillButton
import uk.co.jwgardenservices.crm.ui.theme.JwTheme

@Composable
fun ClientsScreen(viewModel: CrmViewModel, isStaff: Boolean) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Client?>(null) }
    var photoHistory by remember { mutableStateOf<Client?>(null) }
    var saveError by remember { mutableStateOf<String?>(null) }

    val filtered = remember(state.clients, query) {
        filterBy(state.clients, query) { client ->
            listOf(client.name, client.address, client.phone, client.email)
        }
    }

    if (state.loading) {
        Column(Modifier.fillMaxSize()) {
            CompactScreenHeader(title = "Clients", subtitle = "Rates and contact details")
            LoadingState(modifier = Modifier.padding(horizontal = JwTokens.screenPadding))
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        CompactScreenHeader(
            title = "Clients",
            subtitle = if (isStaff) {
                "Names, addresses and notes"
            } else {
                "Rates and contact details"
            },
            actions = {
                if (!isStaff) {
                    PillButton(
                        label = "Add client",
                        icon = Icons.Outlined.Add,
                        onClick = {
                            saveError = null
                            creating = true
                        },
                    )
                }
            },
        )
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(JwTokens.screenPadding),
            verticalArrangement = Arrangement.spacedBy(JwTokens.sectionGap),
        ) {
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
                    shape = RoundedCornerShape(JwTokens.chipRadius),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedBorderColor = JwTheme.colors.cardBorder,
                    ),
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
                    onPhotos = { photoHistory = client },
                )
            }
        }
    }

    photoHistory?.let { client ->
        ClientPhotosDialog(
            client = client,
            viewModel = viewModel,
            onDismiss = { photoHistory = null },
        )
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
            loadAliases = { id -> viewModel.voiceAliases(id) },
            loadVisitNote = { id -> viewModel.nextVisitNote(id) },
            onSave = { draft, aliasText, visitNoteText ->
                val target = editing
                scope.launch {
                    saveError = null
                    try {
                        viewModel.saveClient(draft, target)
                        if (target != null) {
                            val aliases = aliasText.split(',')
                                .map { it.trim() }
                                .filter { it.isNotEmpty() }
                            if (aliases.isNotEmpty() || aliasText.isNotBlank()) {
                                viewModel.saveVoiceAliases(target.id, aliases)
                            }
                            viewModel.saveNextVisitNote(target.id, visitNoteText.trim())
                        }
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
private fun ClientCard(client: Client, isStaff: Boolean, onClick: (() -> Unit)?, onPhotos: (() -> Unit)?) {
    JwCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = onClick != null) { onClick?.invoke() },
        padding = PaddingValues(0.dp),
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
            ContactButtons(
                phone = client.phone,
                address = client.address,
                clientName = client.name,
                defaultMessage = "Hi ${client.name}, ",
            )
            IconButton(onClick = { onPhotos?.invoke() }) {
                Icon(Icons.Outlined.PhotoCamera, contentDescription = "Job photos for ${client.name}")
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
    loadAliases: suspend (String) -> List<String>,
    onSave: (ClientDraft, String, String) -> Unit,
    onDelete: (() -> Unit)?,
    loadVisitNote: suspend (String) -> String? = { null },
) {
    var name by remember(existing?.id) { mutableStateOf(existing?.name.orEmpty()) }
    var address by remember(existing?.id) { mutableStateOf(existing?.address.orEmpty()) }
    var phone by remember(existing?.id) { mutableStateOf(existing?.phone.orEmpty()) }
    var email by remember(existing?.id) { mutableStateOf(existing?.email.orEmpty()) }
    var notes by remember(existing?.id) { mutableStateOf(existing?.notes.orEmpty()) }
    var aliases by remember(existing?.id) { mutableStateOf("") }
    var visitNote by remember(existing?.id) { mutableStateOf("") }
    var loadedAliases by remember(existing?.id) { mutableStateOf(false) }
    LaunchedEffect(existing?.id) {
        val id = existing?.id ?: return@LaunchedEffect
        aliases = runCatching { loadAliases(id) }.getOrDefault(emptyList()).joinToString(", ")
        visitNote = runCatching { loadVisitNote(id) }.getOrNull().orEmpty()
        loadedAliases = true
    }
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

    JwDialog(onDismissRequest = onDismiss, maxHeight = 720.dp) {
                DialogTitle(if (existing == null) "Add client" else "Edit client")

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

                if (loadedAliases) {
                    OutlinedTextField(
                        value = aliases,
                        onValueChange = { aliases = it },
                        label = { Text("Spoken nicknames") },
                        placeholder = { Text("the rectory, Smiths…") },
                        supportingText = { Text("What you actually call them on a call.") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = visitNote,
                        onValueChange = { visitNote = it },
                        label = { Text("Note for next visit") },
                        placeholder = { Text("Get compost, check the fence post…") },
                        supportingText = { Text("Spoken back at the start of the next call.") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Text("How this client is charged", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterPill(
                        label = "Hourly",
                        selected = billingMode == "hourly",
                        onClick = { billingMode = "hourly" },
                    )
                    FilterPill(
                        label = "Fixed price",
                        selected = billingMode == "fixed",
                        onClick = { billingMode = "fixed" },
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

                SettingSwitchRow(
                    title = "Chase this client about unpaid invoices",
                    description = "Off by default. When on, an invoice past its due date is chased weekly.",
                    checked = reminders,
                    onCheckedChange = { reminders = it },
                )

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
                                aliases,
                                visitNote,
                            )
                        },
                    ) {
                        Text(if (existing == null) "Add client" else "Save")
                    }
                }
    }

    if (confirmDelete && onDelete != null) {
        JwAlertDialog(
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

@Composable
private fun ClientPhotosDialog(
    client: Client,
    viewModel: CrmViewModel,
    onDismiss: () -> Unit,
) {
    var photos by remember { mutableStateOf<List<JobPhotoWithUrl>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(client.id) {
        try {
            photos = viewModel.clientPhotos(client.id)
        } catch (failure: Exception) {
            error = failure.message ?: "Could not load photos"
        }
    }

    JwAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Photos — ${client.name}") },
        text = {
            when {
                error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                photos == null -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) { CircularProgressIndicator(Modifier.size(28.dp)) }
                photos!!.isEmpty() -> Text("No job photos yet. Photos are added on the job sheet.")
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.heightIn(max = 420.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(photos!!.size) { index ->
                        val item = photos!![index]
                        AsyncImage(
                            model = item.url,
                            contentDescription = item.photo.caption ?: "Job photo",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(JwTokens.smallRadius))
                                .background(MaterialTheme.colorScheme.surfaceContainer),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}
