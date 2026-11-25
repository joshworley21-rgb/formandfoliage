package uk.co.jwgardenservices.crm.ui.quotes

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.HowToReg
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.RequestQuote
import androidx.compose.material.icons.outlined.Send
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.LocalDate
import kotlin.math.abs
import kotlinx.coroutines.launch
import uk.co.jwgardenservices.crm.data.model.Client
import uk.co.jwgardenservices.crm.data.model.Quote
import uk.co.jwgardenservices.crm.data.model.QuoteDraft
import uk.co.jwgardenservices.crm.data.model.QuoteItem
import uk.co.jwgardenservices.crm.data.model.QuoteItemDraft
import uk.co.jwgardenservices.crm.data.model.Settings
import uk.co.jwgardenservices.crm.domain.QuoteLine
import uk.co.jwgardenservices.crm.domain.displayQuoteStatus
import uk.co.jwgardenservices.crm.domain.formatDayMonthYear
import uk.co.jwgardenservices.crm.domain.lineAmount
import uk.co.jwgardenservices.crm.domain.money
import uk.co.jwgardenservices.crm.domain.moneyCompact
import uk.co.jwgardenservices.crm.domain.parseDate
import uk.co.jwgardenservices.crm.domain.quoteLink
import uk.co.jwgardenservices.crm.domain.quoteTotals
import uk.co.jwgardenservices.crm.ui.CrmViewModel
import uk.co.jwgardenservices.crm.ui.common.DateField
import uk.co.jwgardenservices.crm.ui.common.EmptyState
import uk.co.jwgardenservices.crm.ui.common.ErrorState
import uk.co.jwgardenservices.crm.ui.common.LoadingState
import uk.co.jwgardenservices.crm.ui.common.OptionPicker
import uk.co.jwgardenservices.crm.ui.common.PageHeader
import uk.co.jwgardenservices.crm.ui.common.ShareText
import uk.co.jwgardenservices.crm.ui.common.StatusChip
import uk.co.jwgardenservices.crm.ui.theme.DarkWarning
import uk.co.jwgardenservices.crm.ui.theme.LightWarning

private const val SITE_URL_MISSING =
    "Add your website address in Settings first — the customer needs a link to accept the quote."

private data class QuoteEditorState(
    val quote: Quote?,
    val items: List<QuoteItem>,
    val prefill: QuoteDraft? = null,
)

private data class QuoteItemForm(
    val description: String,
    val quantity: String,
    val unitPrice: String,
)

@Composable
fun QuotesScreen(viewModel: CrmViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var editor by remember { mutableStateOf<QuoteEditorState?>(null) }
    var editorError by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<Quote?>(null) }
    var sendingQuote by remember { mutableStateOf<Quote?>(null) }
    var sendError by remember { mutableStateOf<String?>(null) }
    var busyId by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf("all") }

    val withStatus = remember(state.quotes) {
        state.quotes.map { it to displayQuoteStatus(it.status, it.validUntil) }
    }
    val counts = remember(withStatus) {
        val draft = withStatus.count { it.second == "draft" }
        val sent = withStatus.count { it.second == "sent" }
        val approved = withStatus.count { it.second == "accepted" && it.first.clientId == null }
        val converted = withStatus.count { it.second == "accepted" && it.first.clientId != null }
        val declined = withStatus.count { it.second == "declined" }
        mapOf(
            "all" to withStatus.size,
            "draft" to draft,
            "sent" to sent,
            "approved" to approved,
            "converted" to converted,
            "declined" to declined,
        )
    }
    val shown = remember(withStatus, filter) {
        when (filter) {
            "approved" -> withStatus.filter { it.second == "accepted" && it.first.clientId == null }
            "converted" -> withStatus.filter { it.second == "accepted" && it.first.clientId != null }
            "all" -> withStatus
            else -> withStatus.filter { it.second == filter }
        }
    }
    val shownTotal = shown.sumOf { it.first.total }
    val awaiting = shown.count { it.second == "sent" }

    val origin = state.settings?.publicSiteUrl?.trim()?.trimEnd('/')
    val warning = if (isSystemInDarkTheme()) DarkWarning else LightWarning
    val pendingQuote by viewModel.pendingQuoteDraft.collectAsStateWithLifecycle()

    LaunchedEffect(pendingQuote) {
        pendingQuote?.let { draft ->
            editorError = null
            editor = QuoteEditorState(quote = null, items = emptyList(), prefill = draft)
            viewModel.consumePendingQuote()
        }
    }

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    fun openEdit(quote: Quote) {
        scope.launch {
            busyId = quote.id
            try {
                val items = viewModel.quoteItems(quote.id)
                editorError = null
                editor = QuoteEditorState(quote, items)
            } catch (error: Exception) {
                toast(error.message ?: "Could not open the quote")
            } finally {
                busyId = null
            }
        }
    }

    fun openSend(quote: Quote) {
        if (origin.isNullOrBlank()) {
            toast(SITE_URL_MISSING)
            return
        }
        sendError = null
        sendingQuote = quote
    }

    fun shareLink(quote: Quote) {
        if (origin.isNullOrBlank()) {
            toast(SITE_URL_MISSING)
            return
        }
        val link = quoteLink(quote.publicKey, origin)
        ShareText.share(
            context = context,
            text = "Quote ${quote.number} from Form & Foliage: $link",
            title = "Share quote link",
        )
    }

    fun setStatus(quote: Quote, status: String) {
        scope.launch {
            try {
                viewModel.setQuoteStatus(quote.id, status)
                toast("${quote.number} marked $status")
            } catch (error: Exception) {
                toast(error.message ?: "Could not update the quote")
            }
        }
    }

    fun duplicate(quote: Quote) {
        scope.launch {
            busyId = quote.id
            try {
                viewModel.duplicateQuote(quote.id)
                toast("${quote.number} copied as a new draft quote")
            } catch (error: Exception) {
                toast(error.message ?: "Could not duplicate the quote")
            } finally {
                busyId = null
            }
        }
    }

    fun delete(quote: Quote) {
        scope.launch {
            try {
                viewModel.deleteQuote(quote.id)
                deleting = null
                toast("${quote.number} deleted")
            } catch (error: Exception) {
                toast(error.message ?: "Could not delete the quote")
            }
        }
    }

    if (state.loading) {
        Column(Modifier.padding(20.dp)) {
            PageHeader("Quotes", "Price up work and let the customer accept it online.")
            LoadingState()
        }
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                QuotesHeader(
                    counts = counts,
                    filter = filter,
                    onFilter = { filter = it },
                )
            }

            if (state.error != null) {
                item {
                    Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                        ErrorState(state.error!!, viewModel::refresh)
                    }
                }
            }

            if (state.quotes.isEmpty()) {
                item {
                    EmptyState(
                        icon = Icons.Outlined.RequestQuote,
                        title = "No quotes yet",
                        body = "Price up the first job and send the customer a link they can accept with one tap.",
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            } else {
                item {
                    FilterPills(
                        counts = counts,
                        filter = filter,
                        onFilter = { filter = it },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                item {
                    SummaryStrip(
                        count = shown.size,
                        total = shownTotal,
                        awaiting = awaiting,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                item {
                    Text(
                        text = "Swipe left to delete · Swipe right to send",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                    )
                }
                items(shown, key = { it.first.id }) { (quote, status) ->
                    QuoteCard(
                        quote = quote,
                        display = status,
                        busy = busyId == quote.id,
                        warning = warning,
                        onEdit = { openEdit(quote) },
                        onSend = { openSend(quote) },
                        onLink = { shareLink(quote) },
                        onStatus = { next -> setStatus(quote, next) },
                        onDuplicate = { duplicate(quote) },
                        onDelete = { deleting = quote },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
            }
        }

        Surface(
            onClick = {
                editorError = null
                editor = QuoteEditorState(quote = null, items = emptyList())
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
                Icon(Icons.Outlined.Add, contentDescription = "New quote")
            }
        }
    }

    editor?.let { quoteEditor ->
        QuoteEditorDialog(
            existing = quoteEditor.quote,
            existingItems = quoteEditor.items,
            prefill = quoteEditor.prefill,
            clients = state.clients,
            settings = state.settings,
            saving = state.saving,
            errorMessage = editorError,
            onDismiss = {
                if (!state.saving) {
                    editor = null
                    editorError = null
                }
            },
            onSave = { draft ->
                scope.launch {
                    editorError = null
                    try {
                        val existingId = quoteEditor.quote?.id
                        if (existingId == null) {
                            viewModel.createQuote(draft)
                            toast("Quote created")
                            editor = null
                        } else {
                            viewModel.updateQuote(existingId, draft)
                            editor = null
                            toast("Quote saved")
                        }
                    } catch (error: Exception) {
                        editorError = error.message ?: "Could not save the quote"
                    }
                }
            },
        )
    }

    sendingQuote?.let { quote ->
        SendQuoteDialog(
            quote = quote,
            saving = state.saving,
            errorMessage = sendError,
            onDismiss = {
                if (!state.saving) {
                    sendingQuote = null
                    sendError = null
                }
            },
            onSend = { message ->
                val site = origin
                if (site.isNullOrBlank()) {
                    toast(SITE_URL_MISSING)
                } else {
                    scope.launch {
                        sendError = null
                        try {
                            val result = viewModel.sendQuote(quote.id, site, message)
                            if (result.sent) {
                                toast("Quote emailed to ${result.to ?: "the customer"}")
                            } else {
                                toast(result.reason ?: "Email is not set up yet")
                            }
                            sendingQuote = null
                        } catch (error: Exception) {
                            sendError = error.message ?: "Could not send the quote"
                        }
                    }
                }
            },
        )
    }

    deleting?.let { quote ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete ${quote.number}?") },
            text = { Text("This removes the quote and its lines. A link already sent will stop working.") },
            confirmButton = {
                TextButton(onClick = { delete(quote) }) {
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
private fun QuotesHeader(
    counts: Map<String, Int>,
    filter: String,
    onFilter: (String) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 16.dp)) {
            Text(
                text = "Quotes",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Proposals & price agreements",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StatusTile(
                    label = "Drafts",
                    count = counts["draft"] ?: 0,
                    icon = Icons.Outlined.Description,
                    tint = MaterialTheme.colorScheme.tertiary,
                    active = filter == "draft",
                    onClick = { onFilter(if (filter == "draft") "all" else "draft") },
                    modifier = Modifier.weight(1f),
                )
                StatusTile(
                    label = "Sent",
                    count = counts["sent"] ?: 0,
                    icon = Icons.Outlined.Send,
                    tint = Color(0xFF9FB6D8),
                    active = filter == "sent",
                    onClick = { onFilter(if (filter == "sent") "all" else "sent") },
                    modifier = Modifier.weight(1f),
                )
                StatusTile(
                    label = "Approved",
                    count = counts["approved"] ?: 0,
                    icon = Icons.Outlined.CheckCircle,
                    tint = Color(0xFF8FCF9E),
                    active = filter == "approved",
                    onClick = { onFilter(if (filter == "approved") "all" else "approved") },
                    modifier = Modifier.weight(1f),
                )
                StatusTile(
                    label = "Converted",
                    count = counts["converted"] ?: 0,
                    icon = Icons.Outlined.HowToReg,
                    tint = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.65f),
                    active = filter == "converted",
                    onClick = { onFilter(if (filter == "converted") "all" else "converted") },
                    modifier = Modifier.weight(1f),
                )
                StatusTile(
                    label = "Declined",
                    count = counts["declined"] ?: 0,
                    icon = Icons.Outlined.Cancel,
                    tint = Color(0xFFFF9A84),
                    active = filter == "declined",
                    onClick = { onFilter(if (filter == "declined") "all" else "declined") },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun StatusTile(
    label: String,
    count: Int,
    icon: ImageVector,
    tint: Color,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = if (active) 0.18f else 0.08f),
        contentColor = MaterialTheme.colorScheme.onPrimary,
        border = if (active) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.45f))
        } else {
            null
        },
    ) {
        Column(
            modifier = Modifier.padding(vertical = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.5.sp, letterSpacing = 0.sp),
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.65f),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun FilterPills(
    counts: Map<String, Int>,
    filter: String,
    onFilter: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val filters = listOf(
        "all" to "All",
        "draft" to "Draft",
        "sent" to "Sent",
        "approved" to "Approved",
        "declined" to "Declined",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        filters.forEach { (key, label) ->
            val active = filter == key
            Surface(
                onClick = { onFilter(key) },
                shape = RoundedCornerShape(50),
                color = if (active) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.secondary,
                contentColor = if (active) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                border = if (active) {
                    BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                } else {
                    null
                },
            ) {
                Text(
                    text = "$label (${counts[key] ?: 0})",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun SummaryStrip(
    count: Int,
    total: Double,
    awaiting: Int,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = buildString {
                    append("$count ${if (count == 1) "quote" else "quotes"} · ")
                    append("${moneyCompact(total)} total")
                    if (awaiting > 0) append(" · $awaiting awaiting response")
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.secondary,
            ) {
                Text(
                    text = "Newest ↓",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun QuoteCard(
    quote: Quote,
    display: String,
    busy: Boolean,
    warning: Color,
    onEdit: () -> Unit,
    onSend: () -> Unit,
    onLink: () -> Unit,
    onStatus: (String) -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val converted = display == "accepted" && quote.clientId != null
    val statusLabel = when (display) {
        "accepted" -> if (converted) "Converted" else "Approved"
        "declined" -> "Declined"
        "expired" -> "Expired"
        "sent" -> "Sent"
        else -> "Draft"
    }
    val chipColor = when (display) {
        "draft" -> MaterialTheme.colorScheme.tertiary
        "sent" -> Color(0xFF4C6A92)
        "accepted" -> MaterialTheme.colorScheme.primary
        "declined" -> MaterialTheme.colorScheme.error
        else -> warning
    }
    val chipContainer = chipColor.copy(alpha = 0.12f)
    var menuOpen by remember { mutableStateOf(false) }
    var drag by remember(quote.id) { mutableStateOf(0f) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .pointerInput(quote.id) {
                detectHorizontalDragGestures(
                    onDragStart = { drag = 0f },
                    onDragEnd = {
                        if (abs(drag) > 64f) {
                            if (drag > 0f) onSend() else onDelete()
                        }
                        drag = 0f
                    },
                    onHorizontalDrag = { _, amount -> drag += amount },
                )
            },
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(modifier = Modifier.height(IntrinsicSize.Min)) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(chipColor),
            )
            Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = quote.contactName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = quote.number,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            text = money(quote.total),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(4.dp))
                        StatusChip(
                            label = statusLabel,
                            color = chipColor,
                            container = chipContainer,
                        )
                    }
                }
                Spacer(Modifier.height(7.dp))
                Text(
                    text = quote.notes?.takeIf { it.isNotBlank() }
                        ?: quote.contactAddress?.takeIf { it.isNotBlank() }
                        ?: "No job description yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = quote.validUntil?.let { date ->
                            "Valid until ${parseDate(date)?.formatDayMonthYear() ?: date}"
                        } ?: "No expiry date",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Box {
                        IconButton(
                            onClick = { menuOpen = true },
                            enabled = !busy,
                            modifier = Modifier.size(32.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.MoreVert,
                                contentDescription = "Quote actions",
                                modifier = Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text("Edit quote") },
                                leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onEdit()
                                },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(if (display == "draft") "Send quote" else "Send again")
                                },
                                leadingIcon = { Icon(Icons.Outlined.Send, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onSend()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Share link") },
                                leadingIcon = { Icon(Icons.Outlined.Link, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onLink()
                                },
                            )
                            if (display == "sent") {
                                DropdownMenuItem(
                                    text = { Text("Mark approved") },
                                    leadingIcon = {
                                        Icon(Icons.Outlined.CheckCircle, contentDescription = null)
                                    },
                                    onClick = {
                                        menuOpen = false
                                        onStatus("accepted")
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Mark declined") },
                                    leadingIcon = {
                                        Icon(Icons.Outlined.Cancel, contentDescription = null)
                                    },
                                    onClick = {
                                        menuOpen = false
                                        onStatus("declined")
                                    },
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Duplicate") },
                                onClick = {
                                    menuOpen = false
                                    onDuplicate()
                                },
                            )
                            DropdownMenuItem(
                                text = {
                                    Text("Delete", color = MaterialTheme.colorScheme.error)
                                },
                                onClick = {
                                    menuOpen = false
                                    onDelete()
                                },
                            )
                        }
                    }
                }
                if (busy) {
                    Text(
                        text = "Working…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun quoteStatusColors(status: String, warning: Color): Pair<Color, Color> = when (status) {
    "accepted" -> MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
    "declined" -> MaterialTheme.colorScheme.error to MaterialTheme.colorScheme.error.copy(alpha = 0.10f)
    "expired" -> warning to warning.copy(alpha = 0.12f)
    else -> MaterialTheme.colorScheme.onSurfaceVariant to MaterialTheme.colorScheme.secondary
}

@Composable
private fun QuoteEditorDialog(
    existing: Quote?,
    existingItems: List<QuoteItem>,
    prefill: QuoteDraft? = null,
    clients: List<Client>,
    settings: Settings?,
    saving: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onSave: (QuoteDraft) -> Unit,
) {
    val formKey = existing?.id ?: prefill?.enquiryId ?: "new"
    var clientId by remember(formKey) {
        mutableStateOf(existing?.clientId ?: prefill?.clientId.orEmpty())
    }
    var contactName by remember(formKey) {
        mutableStateOf(existing?.contactName ?: prefill?.contactName.orEmpty())
    }
    var contactEmail by remember(formKey) {
        mutableStateOf(existing?.contactEmail ?: prefill?.contactEmail.orEmpty())
    }
    var contactPhone by remember(formKey) {
        mutableStateOf(existing?.contactPhone ?: prefill?.contactPhone.orEmpty())
    }
    var contactAddress by remember(formKey) {
        mutableStateOf(existing?.contactAddress ?: prefill?.contactAddress.orEmpty())
    }
    var validUntil by remember(formKey) {
        mutableStateOf(
            existing?.validUntil
                ?: prefill?.validUntil
                ?: LocalDate.now().plusDays((settings?.quoteValidityDays ?: 30).toLong()).toString(),
        )
    }
    var notes by remember(formKey) {
        mutableStateOf(existing?.notes ?: prefill?.notes.orEmpty())
    }
    var terms by remember(formKey) {
        mutableStateOf(
            existing?.terms ?: prefill?.terms ?: settings?.quoteTerms.orEmpty(),
        )
    }
    var items by remember(formKey) {
        val prefilled = prefill?.items.orEmpty().map {
            QuoteItemForm(
                description = it.description,
                quantity = quantityText(it.quantity),
                unitPrice = priceText(it.unitPrice),
            )
        }
        mutableStateOf(
            when {
                existingItems.isNotEmpty() -> existingItems.map {
                    QuoteItemForm(
                        description = it.description,
                        quantity = quantityText(it.quantity),
                        unitPrice = priceText(it.unitPrice),
                    )
                }
                prefilled.isNotEmpty() -> prefilled
                else -> listOf(QuoteItemForm("", "1", "0"))
            },
        )
    }

    fun updateItem(index: Int, transform: (QuoteItemForm) -> QuoteItemForm) {
        items = items.toMutableList().also { list -> list[index] = transform(list[index]) }
    }

    val lines = items.map {
        QuoteLine(
            quantity = it.quantity.toDoubleOrNull() ?: 0.0,
            unitPrice = it.unitPrice.toDoubleOrNull() ?: 0.0,
        )
    }
    val totals = quoteTotals(lines, settings?.vatRegistered, settings?.vatRate)
    val drafts = items
        .map {
            QuoteItemDraft(
                description = it.description.trim(),
                quantity = it.quantity.toDoubleOrNull() ?: 0.0,
                unitPrice = it.unitPrice.toDoubleOrNull() ?: 0.0,
            )
        }
        .filter { it.description.isNotBlank() || lineAmount(QuoteLine(it.quantity, it.unitPrice)) != 0.0 }
    val validUntilValid = validUntil.isBlank() || parseDate(validUntil) != null
    val canSave = contactName.isNotBlank() && drafts.isNotEmpty() && validUntilValid && !saving

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
                .heightIn(max = 800.dp),
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
                    text = if (existing == null) "New quote" else "Edit quote",
                    style = MaterialTheme.typography.headlineSmall,
                )

                OptionPicker(
                    options = clients,
                    selected = clients.find { it.id == clientId },
                    label = { it?.name ?: "No client (free text)" },
                    enabled = !saving,
                    onSelect = { client ->
                        clientId = client.id
                        contactName = client.name
                        contactEmail = client.email.orEmpty()
                        contactPhone = client.phone.orEmpty()
                        contactAddress = client.address.orEmpty()
                    },
                )
                if (clientId.isNotBlank()) {
                    TextButton(onClick = { clientId = "" }, enabled = !saving) {
                        Text("Clear client (keep contact details)")
                    }
                }

                OutlinedTextField(
                    value = contactName,
                    onValueChange = { contactName = it },
                    label = { Text("Contact name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = contactEmail,
                    onValueChange = { contactEmail = it },
                    label = { Text("Email") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = contactPhone,
                    onValueChange = { contactPhone = it },
                    label = { Text("Phone") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = contactAddress,
                    onValueChange = { contactAddress = it },
                    label = { Text("Address") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )

                HorizontalDivider()
                Text("Work", style = MaterialTheme.typography.titleSmall)
                items.forEachIndexed { index, item ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = item.description,
                            onValueChange = { value ->
                                updateItem(index) { it.copy(description = value) }
                            },
                            label = { Text("Description") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = item.quantity,
                                onValueChange = { value ->
                                    updateItem(index) { it.copy(quantity = value) }
                                },
                                label = { Text("Qty") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.weight(1f),
                            )
                            OutlinedTextField(
                                value = item.unitPrice,
                                onValueChange = { value ->
                                    updateItem(index) { it.copy(unitPrice = value) }
                                },
                                label = { Text("Unit price (£)") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                onClick = {
                                    items = items.toMutableList().also { list -> list.removeAt(index) }
                                },
                                enabled = items.size > 1 && !saving,
                            ) {
                                Text("Remove", color = MaterialTheme.colorScheme.error)
                            }
                        }
                        Text(
                            text = "Line: ${money(lineAmount(QuoteLine(item.quantity.toDoubleOrNull() ?: 0.0, item.unitPrice.toDoubleOrNull() ?: 0.0)))}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                TextButton(
                    onClick = { items = items + QuoteItemForm("", "1", "0") },
                    enabled = !saving,
                ) {
                    Text("Add line")
                }

                DateField(
                    value = validUntil,
                    onValueChange = { validUntil = it },
                    label = "Valid until",
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = terms,
                    onValueChange = { terms = it },
                    label = { Text("Terms") },
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
                            text = "Subtotal: ${money(totals.subtotal)}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (settings?.vatRegistered == true) {
                            Text(
                                text = "VAT: ${money(totals.vatAmount)}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Text(
                            text = "Total: ${money(totals.total)}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
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
                        enabled = canSave,
                        onClick = {
                            onSave(
                                QuoteDraft(
                                    id = existing?.id,
                                    clientId = clientId.ifBlank { null },
                                    enquiryId = existing?.enquiryId ?: prefill?.enquiryId,
                                    contactName = contactName.trim(),
                                    contactEmail = contactEmail.trim().ifBlank { null },
                                    contactPhone = contactPhone.trim().ifBlank { null },
                                    contactAddress = contactAddress.trim().ifBlank { null },
                                    validUntil = validUntil.trim().ifBlank { null },
                                    notes = notes.trim().ifBlank { null },
                                    terms = terms.trim().ifBlank { null },
                                    items = drafts,
                                ),
                            )
                        },
                    ) {
                        Text(if (existing == null) "Create quote" else "Save changes")
                    }
                }
            }
        }
    }
}

@Composable
private fun SendQuoteDialog(
    quote: Quote,
    saving: Boolean,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onSend: (message: String?) -> Unit,
) {
    var message by remember(quote.id) { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
                .heightIn(max = 640.dp),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Email ${quote.number}", style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = "Leave the message blank to use the standard wording. Anything here appears above the quote table.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = message,
                    onValueChange = { message = it },
                    label = { Text("Message to the customer (optional)") },
                    placeholder = { Text("Thanks for getting in touch. Here is the quote for the work we discussed…") },
                    minLines = 4,
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
                        enabled = !saving,
                        onClick = { onSend(message.trim().ifBlank { null }) },
                    ) {
                        Text(if (saving) "Sending…" else "Send quote")
                    }
                }
            }
        }
    }
}

private fun quantityText(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()

private fun priceText(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()
