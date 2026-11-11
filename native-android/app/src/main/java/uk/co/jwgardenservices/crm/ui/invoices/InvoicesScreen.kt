package uk.co.jwgardenservices.crm.ui.invoices

import android.util.Base64
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.co.jwgardenservices.crm.data.model.BankTransaction
import uk.co.jwgardenservices.crm.data.model.Client
import uk.co.jwgardenservices.crm.data.model.Invoice
import uk.co.jwgardenservices.crm.data.model.InvoiceEmailEvent
import uk.co.jwgardenservices.crm.data.model.NewInvoiceInput
import uk.co.jwgardenservices.crm.data.model.Settings
import uk.co.jwgardenservices.crm.data.model.Visit
import uk.co.jwgardenservices.crm.domain.formatDateTime
import uk.co.jwgardenservices.crm.domain.formatDayMonth
import uk.co.jwgardenservices.crm.domain.formatDayMonthYear
import uk.co.jwgardenservices.crm.domain.money
import uk.co.jwgardenservices.crm.domain.parseDate
import uk.co.jwgardenservices.crm.domain.parseTimestamp
import uk.co.jwgardenservices.crm.ui.CrmViewModel
import uk.co.jwgardenservices.crm.ui.common.DateField
import uk.co.jwgardenservices.crm.ui.common.EmptyState
import uk.co.jwgardenservices.crm.ui.common.ErrorState
import uk.co.jwgardenservices.crm.ui.common.LoadingState
import uk.co.jwgardenservices.crm.ui.common.OptionPicker
import uk.co.jwgardenservices.crm.ui.common.PageHeader
import uk.co.jwgardenservices.crm.ui.common.StatusChip
import uk.co.jwgardenservices.crm.ui.theme.DarkWarning
import uk.co.jwgardenservices.crm.ui.theme.LightWarning

@Composable
fun InvoicesScreen(viewModel: CrmViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val invoices = state.invoices
    val unpaid = invoices.filter { it.status != "paid" }
    val owed = unpaid.sumOf { it.total }
    val clientNames = remember(state.clients) { state.clients.associate { it.id to it.name } }
    val warning = if (isSystemInDarkTheme()) DarkWarning else LightWarning

    var creating by remember { mutableStateOf(false) }
    var createError by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<Invoice?>(null) }
    var editingDraft by remember { mutableStateOf<Invoice?>(null) }
    var draftError by remember { mutableStateOf<String?>(null) }
    var historyId by remember { mutableStateOf<String?>(null) }
    var busyId by remember { mutableStateOf<String?>(null) }

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
        Column(Modifier.padding(20.dp)) {
            PageHeader("Invoices", "What is owed, and what has been sent.")
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
                title = "Invoices",
                description = "What is owed, and what has been sent.",
                action = {
                    Button(
                        enabled = state.clients.isNotEmpty(),
                        onClick = {
                            createError = null
                            creating = true
                        },
                    ) {
                        Text("New invoice")
                    }
                },
            )
        }

        if (state.error != null) {
            item { ErrorState(state.error!!, viewModel::refresh) }
        }

        item { OwedBand(owed = owed, count = unpaid.size) }

        if (state.paymentsToReview.isNotEmpty()) {
            item {
                PaymentsToReview(
                    viewModel = viewModel,
                    payments = state.paymentsToReview,
                    unpaid = unpaid,
                    clients = state.clients,
                )
            }
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

        items(invoices, key = { it.id }) { invoice ->
            InvoiceCard(
                invoice = invoice,
                clientName = clientNames[invoice.clientId] ?: "Client",
                warning = warning,
                busy = busyId == invoice.id,
                lastEvent = state.invoiceEmailEvents.firstOrNull { it.invoiceId == invoice.id },
                events = state.invoiceEmailEvents.filter { it.invoiceId == invoice.id },
                showHistory = historyId == invoice.id,
                onHistory = {
                    historyId = if (historyId == invoice.id) null else invoice.id
                },
                onPdf = { sharePdf(invoice) },
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
        AlertDialog(
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
private fun PaymentsToReview(
    viewModel: CrmViewModel,
    payments: List<BankTransaction>,
    unpaid: List<Invoice>,
    clients: List<Client>,
) {
    if (payments.isEmpty()) return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var chosen by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var busyId by remember { mutableStateOf<String?>(null) }
    val clientNames = remember(clients) { clients.associate { it.id to it.name } }

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.06f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(
                    imageVector = Icons.Outlined.AccountBalance,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(
                        text = if (payments.size == 1) {
                            "A payment has arrived"
                        } else {
                            "${payments.size} payments have arrived"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Money paid into your Monzo account. Confirm what each one settles and the invoice is marked paid.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            payments.forEachIndexed { index, payment ->
                if (index > 0) HorizontalDivider()
                val selectedId = chosen[payment.id] ?: payment.suggestedInvoiceId.orEmpty()
                val selectedInvoice = unpaid.firstOrNull { it.id == selectedId }
                val busy = busyId == payment.id

                Column(
                    modifier = Modifier.padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = money(payment.amount),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = payment.counterparty ?: payment.description ?: "Payment in",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = parseTimestamp(payment.postedAt)?.formatDayMonth() ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    if (!payment.reference.isNullOrBlank()) {
                        Text(
                            text = "Reference: ${payment.reference}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = payment.matchReason ?: "Nothing here says which invoice this is for.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    OptionPicker(
                        options = unpaid,
                        selected = selectedInvoice,
                        label = { invoice ->
                            if (invoice == null) {
                                "Choose an invoice"
                            } else {
                                "${invoice.number} · ${clientNames[invoice.clientId] ?: "Client"} · ${money(invoice.total)}"
                            }
                        },
                        enabled = unpaid.isNotEmpty() && !busy,
                        onSelect = { invoice ->
                            chosen = chosen + (payment.id to invoice.id)
                        },
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(
                            onClick = {
                                scope.launch {
                                    busyId = payment.id
                                    try {
                                        viewModel.confirmPayment(payment.id, selectedId)
                                        toast("${selectedInvoice?.number ?: "Invoice"} marked paid")
                                    } catch (error: Exception) {
                                        toast(error.message ?: "Could not match the payment")
                                    } finally {
                                        busyId = null
                                    }
                                }
                            },
                            enabled = selectedId.isNotBlank() && !busy,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Confirm")
                        }
                        TextButton(
                            onClick = {
                                scope.launch {
                                    busyId = payment.id
                                    try {
                                        viewModel.ignorePayment(payment.id)
                                        toast("Payment dismissed")
                                    } catch (error: Exception) {
                                        toast(error.message ?: "Could not dismiss it")
                                    } finally {
                                        busyId = null
                                    }
                                }
                            },
                            enabled = !busy,
                        ) {
                            Text("Not an invoice")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OwedBand(owed: Double, count: Int) {
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
                Text("OWED TO YOU", style = MaterialTheme.typography.labelSmall)
                Text("$count unpaid", style = MaterialTheme.typography.labelSmall)
            }
            Text(
                text = money(owed),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun InvoiceCard(
    invoice: Invoice,
    clientName: String,
    warning: androidx.compose.ui.graphics.Color,
    busy: Boolean,
    lastEvent: InvoiceEmailEvent?,
    events: List<InvoiceEmailEvent>,
    showHistory: Boolean,
    onHistory: () -> Unit,
    onPdf: () -> Unit,
    onSend: () -> Unit,
    onPaid: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val over = daysOverdue(invoice)
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (over > 0) warning.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outline,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = invoice.number,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onPdf,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Share PDF")
                }
                Button(
                    onClick = onSend,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (invoice.status == "draft") "Send" else "Resend")
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

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
                .heightIn(max = 780.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("New invoice", style = MaterialTheme.typography.headlineSmall)

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
                    shape = RoundedCornerShape(12.dp),
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
                Text("Edit ${invoice.number}", style = MaterialTheme.typography.headlineSmall)
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
    }
}

private fun daysOverdue(invoice: Invoice, today: LocalDate = LocalDate.now()): Long {
    if (invoice.status == "paid") return 0
    val due = parseDate(invoice.dueDate)
        ?: parseDate(invoice.issueDate)?.plusDays(30)
        ?: return 0
    return ChronoUnit.DAYS.between(due, today).coerceAtLeast(0)
}
