package uk.co.jwgardenservices.crm.ui.dashboard

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import uk.co.jwgardenservices.crm.data.model.Client
import uk.co.jwgardenservices.crm.data.model.JobDraft
import uk.co.jwgardenservices.crm.domain.clientsDueAgain
import uk.co.jwgardenservices.crm.domain.describeGap
import uk.co.jwgardenservices.crm.ui.CrmUiState
import uk.co.jwgardenservices.crm.ui.CrmViewModel
import uk.co.jwgardenservices.crm.ui.common.JwCard
import uk.co.jwgardenservices.crm.ui.common.PillButton
import uk.co.jwgardenservices.crm.ui.common.PillStyle
import uk.co.jwgardenservices.crm.ui.schedule.JobEditorDialog
import java.time.LocalDate

/**
 * Home's "Due again" card: regular clients who have gone longer than usual
 * without a visit and have nothing booked. Hidden when there are none.
 */
@Composable
fun DueAgainCard(state: CrmUiState, viewModel: CrmViewModel) {
    val today = LocalDate.now()
    val due = remember(state.clients, state.visits, state.jobs) {
        clientsDueAgain(state.clients, state.visits, state.jobs, today)
    }
    if (due.isEmpty()) return
    val scope = rememberCoroutineScope()
    var booking by remember { mutableStateOf<Client?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    JwCard(modifier = Modifier.fillMaxWidth()) {
        Text("Due again", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "Regular clients with nothing booked",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
        due.take(5).forEach { item ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(item.client.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "Last visit ${weeksAgo(item.daysSince)}, usually ${describeGap(item.usualGapDays)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                PillButton(
                    label = "Book",
                    onClick = {
                        error = null
                        booking = item.client
                    },
                    enabled = !state.saving,
                    style = PillStyle.Forest,
                )
            }
            HorizontalDivider()
        }
    }

    booking?.let { client ->
        JobEditorDialog(
            existing = null,
            defaultDate = today.plusDays(1),
            clients = state.clients,
            saving = state.saving,
            errorMessage = error,
            onDismiss = { if (!state.saving) booking = null },
            onSave = { draft: JobDraft ->
                scope.launch {
                    error = null
                    try {
                        viewModel.saveJob(draft)
                        booking = null
                    } catch (failure: Exception) {
                        error = failure.message ?: "Could not book the job"
                    }
                }
            },
            onBookSeries = { draft ->
                scope.launch {
                    error = null
                    try {
                        viewModel.bookSeries(draft)
                        booking = null
                    } catch (failure: Exception) {
                        error = failure.message ?: "Could not book the run"
                    }
                }
            },
            onDelete = null,
            initialClientId = client.id,
        )
    }
}

private fun weeksAgo(days: Int): String = when {
    days < 14 -> "$days days ago"
    days < 60 -> "${days / 7} weeks ago"
    else -> "${days / 30} months ago"
}
