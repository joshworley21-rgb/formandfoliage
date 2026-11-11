package uk.co.jwgardenservices.crm.ui.enquiries

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import uk.co.jwgardenservices.crm.data.model.Enquiry
import uk.co.jwgardenservices.crm.domain.formatDayMonthYear
import uk.co.jwgardenservices.crm.domain.parseTimestamp
import uk.co.jwgardenservices.crm.ui.CrmViewModel
import uk.co.jwgardenservices.crm.ui.common.EmptyState
import uk.co.jwgardenservices.crm.ui.common.ErrorState
import uk.co.jwgardenservices.crm.ui.common.LoadingState
import uk.co.jwgardenservices.crm.ui.common.PageHeader
import uk.co.jwgardenservices.crm.ui.common.StatusChip

private val STATUS_LABELS = mapOf(
    "new" to "New",
    "contacted" to "Contacted",
    "converted" to "Converted",
    "closed" to "Closed",
)

private val MOVE_TO_STATUSES = listOf(
    "new" to "New",
    "contacted" to "Contacted",
    "closed" to "Closed",
)

@Composable
fun EnquiriesScreen(viewModel: CrmViewModel, onStartQuote: (Enquiry) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var deleting by remember { mutableStateOf<Enquiry?>(null) }
    var busyId by remember { mutableStateOf<String?>(null) }

    fun toast(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }

    fun convert(enquiry: Enquiry) {
        scope.launch {
            busyId = enquiry.id
            try {
                viewModel.convertEnquiry(enquiry)
                toast("${enquiry.name} added to clients")
            } catch (error: Exception) {
                toast(error.message ?: "Could not convert the enquiry")
            } finally {
                busyId = null
            }
        }
    }

    fun replyByEmail(enquiry: Enquiry) {
        val address = enquiry.email?.takeIf { it.isNotBlank() } ?: return
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:$address")
            putExtra(Intent.EXTRA_SUBJECT, "Re: your enquiry")
            putExtra(Intent.EXTRA_TEXT, "Hi ${enquiry.name},\n\n")
        }
        try {
            context.startActivity(Intent.createChooser(intent, "Reply to enquiry"))
        } catch (error: ActivityNotFoundException) {
            toast("No email app found")
        }
    }

    fun call(enquiry: Enquiry) {
        val phone = enquiry.phone?.takeIf { it.isNotBlank() } ?: return
        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))
        try {
            context.startActivity(intent)
        } catch (error: ActivityNotFoundException) {
            toast("No phone app found")
        }
    }

    fun setStatus(enquiry: Enquiry, status: String) {
        scope.launch {
            try {
                viewModel.updateEnquiryStatus(enquiry.id, status)
            } catch (error: Exception) {
                toast(error.message ?: "Could not update the enquiry")
            }
        }
    }

    fun delete(enquiry: Enquiry) {
        scope.launch {
            try {
                viewModel.deleteEnquiry(enquiry.id)
                deleting = null
                toast("Enquiry deleted")
            } catch (error: Exception) {
                toast(error.message ?: "Could not delete the enquiry")
            }
        }
    }

    if (state.loading) {
        Column(Modifier.padding(20.dp)) {
            PageHeader("Enquiries", "Website enquiries, ready to become clients.")
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
                title = "Enquiries",
                description = "Website enquiries, ready to become clients.",
            )
        }

        if (state.error != null) {
            item { ErrorState(state.error!!, viewModel::refresh) }
        }

        if (state.enquiries.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Outlined.Inbox,
                    title = "No enquiries yet",
                    body = "Point your website's contact button at the enquiry link in Settings and submissions will appear here.",
                )
            }
            return@LazyColumn
        }

        items(state.enquiries, key = { it.id }) { enquiry ->
            EnquiryCard(
                enquiry = enquiry,
                busy = busyId == enquiry.id,
                onConvert = { convert(enquiry) },
                onQuote = { onStartQuote(enquiry) },
                onEmail = { replyByEmail(enquiry) },
                onCall = { call(enquiry) },
                onStatus = { status -> setStatus(enquiry, status) },
                onDelete = { deleting = enquiry },
            )
        }
    }

    deleting?.let { enquiry ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete the enquiry from ${enquiry.name}?") },
            text = { Text("It will be removed from the inbox. Any client created from it stays.") },
            confirmButton = {
                TextButton(onClick = { delete(enquiry) }) {
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
private fun EnquiryCard(
    enquiry: Enquiry,
    busy: Boolean,
    onConvert: () -> Unit,
    onQuote: () -> Unit,
    onEmail: () -> Unit,
    onCall: () -> Unit,
    onStatus: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val status = enquiry.status
    val (chipColor, chipContainer) = when (status) {
        "converted" -> MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        "new" -> MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        else -> MaterialTheme.colorScheme.onSurfaceVariant to MaterialTheme.colorScheme.secondary
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = enquiry.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = buildString {
                            append(enquiry.source.replaceFirstChar { it.uppercase() })
                            parseTimestamp(enquiry.createdAt)?.let { append(" · ${it.formatDayMonthYear()}") }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusChip(
                    label = STATUS_LABELS[status] ?: status,
                    color = chipColor,
                    container = chipContainer,
                )
                Spacer(Modifier.width(4.dp))
                Box {
                    IconButton(onClick = { menuOpen = true }, enabled = !busy) {
                        Icon(Icons.Outlined.MoreVert, contentDescription = "Enquiry actions")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        MOVE_TO_STATUSES.filter { it.first != status }.forEach { (value, label) ->
                            DropdownMenuItem(
                                text = { Text("Mark $label") },
                                onClick = {
                                    menuOpen = false
                                    onStatus(value)
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                            onClick = {
                                menuOpen = false
                                onDelete()
                            },
                        )
                    }
                }
            }

            val contact = listOfNotNull(
                enquiry.email?.takeIf { it.isNotBlank() },
                enquiry.phone?.takeIf { it.isNotBlank() },
                enquiry.address?.takeIf { it.isNotBlank() },
            )
            if (contact.isNotEmpty()) {
                Text(
                    text = contact.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            if (!enquiry.message.isNullOrBlank()) {
                Text(
                    text = enquiry.message.orEmpty(),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            if (busy) {
                Text(
                    text = "Working…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 10.dp))

            val hasEmail = !enquiry.email.isNullOrBlank()
            val hasPhone = !enquiry.phone.isNullOrBlank()
            if (hasEmail || hasPhone) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (hasEmail) {
                        OutlinedButton(
                            onClick = onEmail,
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Email")
                        }
                    }
                    if (hasPhone) {
                        OutlinedButton(
                            onClick = onCall,
                            enabled = !busy,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Call")
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = onQuote,
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Quote")
                }
                if (status == "converted") {
                    Text(
                        text = "Converted to a client",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Button(
                        onClick = onConvert,
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Convert to client")
                    }
                }
            }
        }
    }
}
