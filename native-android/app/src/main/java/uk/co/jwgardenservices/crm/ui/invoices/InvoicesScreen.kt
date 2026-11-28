package uk.co.jwgardenservices.crm.ui.invoices

import android.util.Base64
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import uk.co.jwgardenservices.crm.ui.common.JwAlertDialog
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.co.jwgardenservices.crm.data.model.Client
import uk.co.jwgardenservices.crm.data.model.Invoice
import uk.co.jwgardenservices.crm.data.model.InvoiceEmailEvent
import uk.co.jwgardenservices.crm.data.model.NewInvoiceInput
import uk.co.jwgardenservices.crm.data.model.Settings
import uk.co.jwgardenservices.crm.data.model.Visit
import uk.co.jwgardenservices.crm.domain.formatDateTime
import uk.co.jwgardenservices.crm.domain.daysOverdue
import uk.co.jwgardenservices.crm.domain.formatDayMonth
import uk.co.jwgardenservices.crm.domain.hoursLabel
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
import uk.co.jwgardenservices.crm.domain.formatDayMonthYear
import uk.co.jwgardenservices.crm.core.TaxReservePrefs
import uk.co.jwgardenservices.crm.domain.taxReserveAmount
import uk.co.jwgardenservices.crm.domain.money
import uk.co.jwgardenservices.crm.domain.parseDate
import uk.co.jwgardenservices.crm.domain.parseTimestamp
import uk.co.jwgardenservices.crm.ui.CrmViewModel
import uk.co.jwgardenservices.crm.ui.common.DateField
import uk.co.jwgardenservices.crm.ui.common.EmptyState
import uk.co.jwgardenservices.crm.ui.common.ErrorState
import uk.co.jwgardenservices.crm.ui.common.DialogTitle
import uk.co.jwgardenservices.crm.ui.common.JwDialog
import uk.co.jwgardenservices.crm.ui.common.LoadingState
import uk.co.jwgardenservices.crm.ui.common.OptionPicker
import uk.co.jwgardenservices.crm.ui.common.StatusChip
import uk.co.jwgardenservices.crm.ui.theme.JwTheme
import uk.co.jwgardenservices.crm.ui.theme.JwTokens
import uk.co.jwgardenservices.crm.ui.common.CompactScreenHeader
import uk.co.jwgardenservices.crm.ui.common.ForestCard
import uk.co.jwgardenservices.crm.ui.common.JwCard
import uk.co.jwgardenservices.crm.ui.common.PillButton
import uk.co.jwgardenservices.crm.ui.common.PillStyle
import uk.co.jwgardenservices.crm.ui.common.SectionLabel
import uk.co.jwgardenservices.crm.ui.common.StatusTile

@Composable
fun InvoicesScreen(viewModel: CrmViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val invoices = state.invoices
    val unpaid = invoices.filter { it.status == "sent" }
    val owed = unpaid.sumOf { it.total }
    val clientNames = remember(state.clients) { state.clients.associate { it.id to it.name } }
    val warning = JwTheme.colors.attention
    val taxRate by TaxReservePrefs.ratePercent.collectAsStateWithLifecycle()

    var creating by remember { mutableStateOf(false) }
    var createError by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<Invoice?>(null) }
    var editingDraft by remember { mutableStateOf<Invoice?>(null) }
    var draftError by remember { mutableStateOf<String?>(null) }
    var historyId by remember { mutableStateOf<String?>(null) }
    var busyId by remember { mutableStateOf<String?>(null) }
    // Tapping a status tile in the summary card filters the list beneath it.
    var statusFilter by remember { mutableStateOf<String?>(null) }
    val overdueCount = invoices.count { daysOverdue(it) > 0 }
    val visibleInvoices = when (statusFilter) {
        "overdue" -> invoices.filter { daysOverdue(it) > 0 }
        null -> invoices
        else -> invoices.filter { it.status == statusFilter }
    }

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    fun sharePdf(invoice: Invoice) {
        scope.launch {
            busyId = invoice.id
            try {
                val client = state.clients.find { it.id == invoice.clientId }
                    ?: error("This invoice's client is missing")
                val items = viewModel.invoiceItems(invoice.id)
                val file = withContext(Dispatchers.IO) {
                    InvoicePdf.build(context, invoice, client, items, state.settings)
                }
                PdfSharing.share(context, file)
            } catch (error: Exception) {
                toast(error.message ?: "Could not build the PDF")
            } finally {
                busyId = null
            }
        }
    }

    fun shareViaWhatsApp(invoice: Invoice) {
        scope.launch {
            busyId = invoice.id
            try {
                val client = state.clients.find { it.id == invoice.clientId }
                    ?: error("This invoice's client is missing")
                val items = viewModel.invoiceItems(invoice.id)
                val file = withContext(Dispatchers.IO) {
                    InvoicePdf.build(context, invoice, client, items, state.settings)
                }
                val due = invoice.dueDate?.let { parseDate(it)?.formatDayMonth() }
                val message = buildString {
                    append("Hi ${client.name}, here's invoice ${invoice.number} for ${money(invoice.total)}")
                    if (due != null) append(" — due $due")
                    append(". Thanks!")
                }
                PdfSharing.shareViaWhatsApp(context, file, message)
            } catch (error: Exception) {
                toast(error.message ?: "Could not build the PDF")
            } finally {
                busyId = null
            }
        }
    }

    fun send(invoice: Invoice) {
        scope.launch {
            busyId = invoice.id
            try {
                val client = state.clients.find { it.id == invoice.clientId }
                    ?: error("This invoice's client is missing")
                val items = viewModel.invoiceItems(invoice.id)
                val file = withContext(Dispatchers.IO) {
                    InvoicePdf.build(context, invoice, client, items, state.settings)
                }
                val base64 = withContext(Dispatchers.IO) {
                    Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
                }
                val result = viewModel.sendInvoice(invoice.id, base64)
                if (result.sent) {
                    toast("Invoice emailed to ${result.to ?: "the client"}")
                } else {
                    toast(result.reason ?: "Email is not set up yet")
                }
            } catch (error: Exception) {
                toast(error.message ?: "Could not send the invoice")
            } finally {
                busyId = null
            }
        }
    }

    fun markPaid(invoice: Invoice) {
        scope.launch {
            try {
                viewModel.markInvoicePaid(invoice.id)
                toast("${invoice.number} marked paid")
            } catch (error: Exception) {
                toast(error.message ?: "Could not mark it paid")
            }
        }
    }

    fun delete(invoice: Invoice) {
        scope.launch {
            try {
                viewModel.deleteInvoice(invoice.id)
                deleting = null
                toast("${invoice.number} deleted")
            } catch (error: Exception) {
                toast(error.message ?: "Could not delete the invoice")
            }
        }
    }

    if (state.loading) {
        Column(Modifier.fillMaxSize()) {
            CompactScreenHeader(title = "Invoices", subtitle = "Owed, sent and paid")
            LoadingState(modifier = Modifier.padding(horizontal = JwTokens.screenPadding))
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        CompactScreenHeader(
            title = "Invoices",
            subtitle = "Owed, sent and paid",
            actions = {
                PillButton(
                    label = "New invoice",
                    enabled = state.clients.isNotEmpty(),
                    onClick = {
                        createError = null
                        creating = true
                    },
                )
            },
        )
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(
                start = JwTokens.screenPadding,
                end = JwTokens.screenPadding,
                top = JwTokens.sectionGap,
                bottom = 32.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(JwTokens.sectionGap),
        ) {
        if (state.error != null) {
            item { ErrorState(state.error!!, viewModel::refresh) }
        }

        item {
            OwedBand(
                owed = owed,
                unpaidCount = unpaid.size,
                counts = mapOf(
                    "draft" to invoices.count { it.status == "draft" },
                    "sent" to unpaid.size,
                    "overdue" to overdueCount,
                    "paid" to invoices.count { it.status == "paid" },
                ),
                filter = statusFilter,
                onFilter = { key -> statusFilter = if (statusFilter == key) null else key },
            )
        }

        item {
            ReadyToBillCard(
                clients = state.clients,
                visits = state.visits,
                clientNames = clientNames,
                busy = state.saving,
                vatRate = state.settings?.takeIf { it.vatRegistered }?.vatRate ?: 0.0,
                onCreate = { client, visitIds ->
                    scope.launch {
                        try {
                            val result = viewModel.createInvoice(
                                NewInvoiceInput(clientId = client.id, visitIds = visitIds),
                            )
                            toast("Invoice ${result.number} created for ${client.name}")
                        } catch (error: Exception) {
                            toast(error.message ?: "Could not create the invoice")
                        }
                    }
                },
            )
        }

        if (invoices.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.AutoMirrored.Outlined.ReceiptLong,
                    title = "No invoices yet",
                    body = "Log some visits, then create an invoice from the button above.",
                )
            }
            return@LazyColumn
        }

        if (visibleInvoices.isEmpty()) {
            item {
                Text(
                    text = "No $statusFilter invoices. Tap the tile again to show them all.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        items(visibleInvoices, key = { it.id }) { invoice ->
            InvoiceCard(
                invoice = invoice,
                clientName = clientNames[invoice.clientId] ?: "Client",
                warning = warning,
                taxRatePercent = taxRate,
                busy = busyId == invoice.id,
                lastEvent = state.invoiceEmailEvents.firstOrNull { it.invoiceId == invoice.id },
                events = state.invoiceEmailEvents.filter { it.invoiceId == invoice.id },
                showHistory = historyId == invoice.id,
                onHistory = {
                    historyId = if (historyId == invoice.id) null else invoice.id
                },
                onPdf = { sharePdf(invoice) },
                onWhatsApp = { shareViaWhatsApp(invoice) },
                onSend = { send(invoice) },
                onPaid = { markPaid(invoice) },
                onEdit = {
                    draftError = null
                    editingDraft = invoice
                },
                onDelete = { deleting = invoice },
            )
        }
        }
    }

    if (creating) {
        NewInvoiceDialog(
            clients = state.clients,
            visits = state.visits,
            settings = state.settings,
            saving = state.saving,
            errorMessage = createError,
            onDismiss = {
                if (!state.saving) {
                    creating = false
                    createError = null
                }
            },
            onCreate = { input ->
                scope.launch {
                    createError = null
                    try {
                        val result = viewModel.createInvoice(input)
                        creating = false
                        toast("Invoice ${result.number} created")
                    } catch (error: Exception) {
                        createError = error.message ?: "Could not create the invoice"
                    }
                }
            },
        )
    }

    editingDraft?.let { invoice ->
        DraftInvoiceDialog(
            invoice = invoice,
            saving = state.saving,
            errorMessage = draftError,
            onDismiss = {
                if (!state.saving) {
                    editingDraft = null
                    draftError = null
                }
            },
            onSave = { dueDate, notes ->
                scope.launch {
                    draftError = null
                    try {
                        viewModel.updateInvoiceDraft(invoice.id, dueDate, notes)
                        editingDraft = null
                        toast("Invoice updated")
                    } catch (error: Exception) {
                        draftError = error.message ?: "Could not update the invoice"
                    }
                }
            },
            onRebuild = {
                scope.launch {
                    draftError = null
                    try {
                        val result = viewModel.rebuildInvoice(invoice.id)
                        editingDraft = null
                        toast(
                            "${result.number} rebuilt — ${result.lines} " +
                                (if (result.lines == 1) "line" else "lines") +
                                ", ${money(result.total)}",
                        )
                    } catch (error: Exception) {
                        draftError = error.message ?: "Could not rebuild the invoice"
                    }
                }
            },
        )
    }

    deleting?.let { invoice ->
        JwAlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${invoice.number}?") },
            text = {
                Text(
                    "This removes the invoice, its lines and its email history. Any visits on it go back to unbilled so you can invoice them again.",
                )
            },
            confirmButton = {
                TextButton(onClick = { delete(invoice) }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun OwedBand(
    owed: Double,
    unpaidCount: Int,
    counts: Map<String, Int>,
    filter: String?,
    onFilter: (String) -> Unit,
) {
    val palette = JwTheme.colors
    ForestCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            SectionLabel("Owed to you", onForest = true)
            SectionLabel("$unpaidCount unpaid", onForest = true)
        }
        Text(
            text = money(owed),
            style = MaterialTheme.typography.displaySmall,
            color = palette.brass,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StatusTile(
                label = "Drafts",
                count = counts["draft"] ?: 0,
                icon = Icons.Outlined.Description,
                tint = palette.brass,
                active = filter == "draft",
                onClick = { onFilter("draft") },
                modifier = Modifier.weight(1f),
            )
            StatusTile(
                label = "Sent",
                count = counts["sent"] ?: 0,
                icon = Icons.AutoMirrored.Outlined.Send,
                tint = palette.onForestMuted,
                active = filter == "sent",
                onClick = { onFilter("sent") },
                modifier = Modifier.weight(1f),
            )
            StatusTile(
                label = "Overdue",
                count = counts["overdue"] ?: 0,
                icon = Icons.Outlined.ErrorOutline,
                tint = MaterialTheme.colorScheme.errorContainer,
                active = filter == "overdue",
                onClick = { onFilter("overdue") },
                modifier = Modifier.weight(1f),
            )
            StatusTile(
                label = "Paid",
                count = counts["paid"] ?: 0,
                icon = Icons.Outlined.CheckCircle,
                tint = palette.callBar,
                active = filter == "paid",
                onClick = { onFilter("paid") },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun InvoiceCard(
    invoice: Invoice,
    clientName: String,
    warning: androidx.compose.ui.graphics.Color,
    taxRatePercent: Double,
    busy: Boolean,
    lastEvent: InvoiceEmailEvent?,
    events: List<InvoiceEmailEvent>,
    showHistory: Boolean,
    onHistory: () -> Unit,
    onPdf: () -> Unit,
    onWhatsApp: () -> Unit,
    onSend: () -> Unit,
    onPaid: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val over = daysOverdue(invoice)
    JwCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = invoice.number,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = clientName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = money(invoice.total),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
            }

            if (invoice.status != "draft" && taxRatePercent > 0.0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Save for tax: ~" + money(taxReserveAmount(invoice.subtotal, taxRatePercent)),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${if (taxRatePercent % 1.0 == 0.0) taxRatePercent.toInt() else taxRatePercent}% of net",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(Modifier.padding(top = 10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = parseDate(invoice.issueDate)?.formatDayMonthYear() ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (over > 0) {
                    StatusChip(
                        label = "$over ${if (over == 1L) "day" else "days"} over",
                        color = warning,
                        container = warning.copy(alpha = 0.12f),
                    )
                } else {
                    val label = when (invoice.status) {
                        "paid" -> "Paid"
                        "sent" -> "Sent"
                        else -> "Draft"
                    }
                    val color = if (invoice.status == "paid") {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    val container = if (invoice.status == "paid") {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                    } else {
                        MaterialTheme.colorScheme.secondary
                    }
                    StatusChip(label = label, color = color, container = container)
                }
            }

            val attempt = if (lastEvent == null) {
                "Not sent yet"
            } else {
                "Last email: ${eventLabel(lastEvent.status)}" +
                    (lastEvent.recipient?.let { " to $it" } ?: "") +
                    (parseTimestamp(lastEvent.createdAt)?.let { " · ${it.formatDateTime()}" } ?: "")
            }
            Text(
                text = attempt,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )

            if (busy) {
                Text(
                    text = "Working…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 10.dp))

            // Three buttons in one row left each too narrow ("WhatsAp / p"):
            // the main action gets the full width, the two shares sit below it.
            Button(
                onClick = onSend,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    when {
                        invoice.status == "draft" -> "Send"
                        over > 0 -> "Send reminder"
                        else -> "Resend"
                    },
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onPdf,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Share PDF", maxLines = 1, softWrap = false)
                }
                OutlinedButton(
                    onClick = onWhatsApp,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("WhatsApp", maxLines = 1, softWrap = false)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onHistory, enabled = !busy) {
                    Text(if (showHistory) "Hide history" else "History")
                }
                if (invoice.status == "draft") {
                    TextButton(onClick = onEdit, enabled = !busy) { Text("Edit draft") }
                }
                if (invoice.status != "paid") {
                    TextButton(onClick = onPaid, enabled = !busy) { Text("Mark paid") }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDelete, enabled = !busy) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            }

            if (showHistory) {
                EmailHistory(events)
            }
        }
    }
}

@Composable
private fun EmailHistory(events: List<InvoiceEmailEvent>) {
    HorizontalDivider(Modifier.padding(top = 10.dp, bottom = 8.dp))
    Text(
        text = "EMAIL HISTORY",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (events.isEmpty()) {
        Text(
            text = "No email attempts recorded for this invoice yet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    } else {
        Column(
            modifier = Modifier.padding(top = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            events.forEach { event ->
                Column {
                    Text(
                        text = eventLabel(event.status) +
                            (event.recipient?.let { " · $it" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                        color = eventColor(event.status),
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = parseTimestamp(event.createdAt)?.formatDateTime() ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (!event.detail.isNullOrBlank()) {
                        Text(
                            text = event.detail.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private fun eventLabel(status: String): String = when (status) {
    "sent" -> "Sent"
    "failed" -> "Failed"
    "skipped" -> "Not sent"
    "delivered" -> "Delivered"
    "opened" -> "Opened"
    "clicked" -> "Link clicked"
    "bounced" -> "Bounced"
    "complained" -> "Marked as spam"
    "delivery_delayed" -> "Delayed"
    else -> status
}

@Composable
private fun eventColor(status: String): Color = when (status) {
    "failed", "bounced", "complained" -> MaterialTheme.colorScheme.error
    "sent", "delivered", "opened", "clicked" -> MaterialTheme.colorScheme.primary
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun NewInvoiceDialog(
    clients: List<Client>,
    visits: List<Visit>,
    settings: Settings?,
    saving: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onCreate: (NewInvoiceInput) -> Unit,
) {
    val initialClient = remember {
        clients.firstOrNull { client ->
            visits.any { it.clientId == client.id && !it.billed && !it.missed }
        } ?: clients.firstOrNull()
    }
    var clientId by remember { mutableStateOf(initialClient?.id.orEmpty()) }
    var selected by remember {
        mutableStateOf(
            visits
                .filter { it.clientId == initialClient?.id && !it.billed && !it.missed }
                .map { it.id }
                .toSet(),
        )
    }
    var retainer by remember { mutableStateOf("0") }
    var retainerDescription by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var dueDate by remember { mutableStateOf(LocalDate.now().plusDays(14).toString()) }

    val available = visits
        .filter { it.clientId == clientId && !it.billed && !it.missed }
        .sortedBy { it.visitDate }
    val retainerValue = retainer.toDoubleOrNull() ?: 0.0
    val subtotal = available.filter { it.id in selected }.sumOf { it.amount } + retainerValue
    val vatRate = if (settings?.vatRegistered == true) settings.vatRate else 0.0
    val vat = subtotal * vatRate / 100.0
    val dueDateValid = dueDate.isBlank() || parseDate(dueDate) != null
    val canCreate = clientId.isNotBlank() &&
        (selected.isNotEmpty() || retainerValue > 0) &&
        dueDateValid &&
        !saving

    JwDialog(onDismissRequest = onDismiss, maxHeight = 780.dp) {
                DialogTitle("New invoice")

                OptionPicker(
                    options = clients,
                    selected = clients.find { it.id == clientId },
                    label = { it?.name ?: "Choose client" },
                    enabled = true,
                    onSelect = { client ->
                        clientId = client.id
                        selected = visits
                            .filter { it.clientId == client.id && !it.billed && !it.missed }
                            .map { it.id }
                            .toSet()
                    },
                )

                Text("Unbilled visits", style = MaterialTheme.typography.titleSmall)
                if (available.isEmpty()) {
                    Text(
                        text = "No unbilled visits for this client. You can still invoice a retainer below.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        available.forEach { visit ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = visit.id in selected,
                                    onCheckedChange = { checked ->
                                        selected = if (checked) {
                                            selected + visit.id
                                        } else {
                                            selected - visit.id
                                        }
                                    },
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = parseDate(visit.visitDate)?.formatDayMonthYear()
                                            ?: visit.visitDate,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                    if (!visit.description.isNullOrBlank()) {
                                        Text(
                                            text = visit.description.orEmpty(),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                Text(
                                    text = money(visit.amount),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = retainer,
                        onValueChange = { retainer = it },
                        label = { Text("Retainer / extra (£)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = retainerDescription,
                        onValueChange = { retainerDescription = it },
                        label = { Text("Description") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }

                DateField(
                    value = dueDate,
                    onValueChange = { dueDate = it },
                    label = "Due date",
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Invoice notes") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )

                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(JwTokens.smallRadius),
                    color = MaterialTheme.colorScheme.secondary,
                ) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                        Text(
                            text = "Before VAT: ${money(subtotal)}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (vat > 0) {
                            Text(
                                text = "Total incl. VAT: ${money(subtotal + vat)}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
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
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onDismiss, enabled = !saving) { Text("Cancel") }
                    Spacer(Modifier.width(6.dp))
                    Button(
                        enabled = canCreate,
                        onClick = {
                            onCreate(
                                NewInvoiceInput(
                                    clientId = clientId,
                                    visitIds = selected.toList(),
                                    retainerDescription = retainerDescription.trim().ifBlank { null },
                                    retainerAmount = retainerValue,
                                    notes = notes.trim().ifBlank { null },
                                    dueDate = dueDate.trim().ifBlank { null },
                                ),
                            )
                        },
                    ) {
                        Text("Create invoice")
                    }
                }
    }
}

/**
 * Sent, due, and still not paid. Uses the real due date when there is one and
 * falls back to 30 days after issue — matching the web app's corrected rule.
 */
@Composable
private fun DraftInvoiceDialog(
    invoice: Invoice,
    saving: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onSave: (dueDate: String?, notes: String?) -> Unit,
    onRebuild: () -> Unit,
) {
    var dueDate by remember(invoice.id) { mutableStateOf(invoice.dueDate.orEmpty()) }
    var notes by remember(invoice.id) { mutableStateOf(invoice.notes.orEmpty()) }

    JwDialog(onDismissRequest = onDismiss, maxHeight = 720.dp) {
                DialogTitle("Edit ${invoice.number}")
                Text(
                    text = "This invoice has not been sent, so it can still be changed. Once it goes out it is fixed at whatever the customer received.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                DateField(
                    value = dueDate,
                    onValueChange = { dueDate = it },
                    label = "Due date",
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes on the invoice") },
                    placeholder = { Text("Thanks — payment within 14 days please.") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (errorMessage != null) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Button(
                    onClick = {
                        if (!saving) onSave(dueDate.trim().ifBlank { null }, notes.trim().ifBlank { null })
                    },
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Save changes")
                }

                HorizontalDivider()

                Text("The lines", style = MaterialTheme.typography.titleSmall)
                Text(
                    text = "The lines come from the visits on this invoice — currently ${money(invoice.total)}. Correct a visit's hours or materials on the Visits screen, then rebuild and the invoice catches up. Its number and date stay as they are.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = { if (!saving) onRebuild() },
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Rebuild from visits")
                }
    }
}

/** The invoice Bill all would create: every visit, the totals, then confirm or back out. */
@Composable
private fun BillPreviewDialog(
    client: Client,
    visits: List<Visit>,
    vatRate: Double,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val subtotal = visits.sumOf { it.amount }
    val vat = subtotal * vatRate / 100.0
    JwAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Draft invoice for ${client.name}") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                visits.forEach { visit ->
                    Row(verticalAlignment = Alignment.Top) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = parseDate(visit.visitDate)?.formatDayMonth() ?: visit.visitDate,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            val detail = listOfNotNull(
                                visit.hours.takeIf { it > 0 }?.let { hoursLabel(it) },
                                visit.description?.takeIf { it.isNotBlank() },
                                visit.materialsCost.takeIf { it > 0 }?.let { "materials ${money(it)}" },
                            ).joinToString(" · ")
                            if (detail.isNotEmpty()) {
                                Text(
                                    text = detail,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Text(money(visit.amount), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                HorizontalDivider()
                if (vat > 0) {
                    Row {
                        Text("Subtotal", modifier = Modifier.weight(1f))
                        Text(money(subtotal))
                    }
                    Row {
                        Text("VAT ${vatRate}%", modifier = Modifier.weight(1f))
                        Text(money(vat))
                    }
                }
                Row {
                    Text("Total", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text(money(subtotal + vat), style = MaterialTheme.typography.titleMedium)
                }
                Text(
                    text = "This makes a draft. Nothing is sent to ${client.name} until you tap Send.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Create draft") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * One row per client with unbilled work. A single tap builds an invoice from
 * every unbilled visit — the recurring-clients shortcut so the monthly round
 * does not need a visit-by-visit picker.
 */
@Composable
private fun ReadyToBillCard(
    clients: List<Client>,
    visits: List<Visit>,
    clientNames: Map<String, String>,
    busy: Boolean,
    vatRate: Double = 0.0,
    onCreate: (Client, List<String>) -> Unit,
) {
    // Bill all shows the invoice it would make first; nothing is created until Create draft.
    var preview by remember { mutableStateOf<Pair<Client, List<Visit>>?>(null) }
    preview?.let { (client, rows) ->
        BillPreviewDialog(
            client = client,
            visits = rows,
            vatRate = vatRate,
            onDismiss = { preview = null },
            onConfirm = {
                preview = null
                onCreate(client, rows.map { it.id })
            },
        )
    }
    val groups = remember(visits) {
        visits
            .filter { !it.billed && !it.missed }
            .groupBy { it.clientId }
            .map { (clientId, rows) ->
                Triple(clientId, rows.sortedBy { it.visitDate }, rows.sumOf { it.amount })
            }
            .sortedByDescending { it.third }
    }
    if (groups.isEmpty()) return
    val totalReady = groups.sumOf { it.third }
    val visitCount = groups.sumOf { it.second.size }
    JwCard(modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Ready to bill", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "$visitCount unbilled ${if (visitCount == 1) "visit" else "visits"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = money(totalReady),
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            HorizontalDivider()
            groups.forEach { (clientId, rows, total) ->
                val client = clients.firstOrNull { it.id == clientId } ?: return@forEach
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = client.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            text = "${rows.size} unbilled ${if (rows.size == 1) "visit" else "visits"} · ${money(total)}" +
                                " · since ${parseDate(rows.first().visitDate)?.formatDayMonth() ?: rows.first().visitDate}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    PillButton(
                        label = "Bill all",
                        onClick = { preview = client to rows },
                        enabled = !busy,
                        style = PillStyle.Forest,
                    )
                }
                HorizontalDivider()
            }
            Text(
                text = "Bill all shows the invoice first. Confirming makes a draft in the list below, " +
                    "so you can still adjust it before sending.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}
