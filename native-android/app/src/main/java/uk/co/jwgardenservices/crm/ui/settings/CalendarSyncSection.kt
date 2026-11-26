package uk.co.jwgardenservices.crm.ui.settings

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import uk.co.jwgardenservices.crm.ui.common.SectionCard
import uk.co.jwgardenservices.crm.voice.CalendarSync
import uk.co.jwgardenservices.crm.voice.CalendarTag
import uk.co.jwgardenservices.crm.voice.DeviceCalendar
import uk.co.jwgardenservices.crm.ui.common.SegmentedPillTabs
import uk.co.jwgardenservices.crm.ui.theme.JwTokens

/**
 * One setting per calendar, not per event: a Business calendar is read with
 * titles and names, a Personal one becomes anonymous busy blocks, and Ignore
 * stays out of the CRM entirely.
 */
@Composable
fun CalendarSyncSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var granted by remember { mutableStateOf(CalendarSync.hasPermission(context)) }
    var calendars by remember { mutableStateOf<List<DeviceCalendar>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    val tags by CalendarSync.tagsFlow(context)
        .collectAsStateWithLifecycle(initialValue = emptyMap())

    val permission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { allowed ->
        granted = allowed
    }

    LaunchedEffect(granted) {
        if (!granted) {
            calendars = emptyList()
            return@LaunchedEffect
        }
        loading = true
        calendars = CalendarSync.calendars(context)
        loading = false
    }

    SectionCard(
        title = "Google Calendar",
        subtitle = "Read-only context beside the round.",
        leadingIcon = Icons.Outlined.CalendarMonth,
    ) {
        Column(
            modifier = Modifier.padding(JwTokens.cardPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Calendar is read-only. Tag a whole calendar once: Business reads " +
                    "titles, Personal becomes anonymous busy blocks, and Mixed keeps a single " +
                    "calendar for both — prefix personal events with \"Personal:\" or " +
                    "\"Private:\" and leave business titles unprefixed. Google edits appear " +
                    "live, with no sync to wait for.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (!granted) {
                OutlinedButton(onClick = { permission.launch(Manifest.permission.READ_CALENDAR) }) {
                    Text("Allow calendar access")
                }
                return@Column
            }

            if (loading) {
                Text(
                    text = "Reading calendars…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (calendars.isEmpty()) {
                Text(
                    text = "No calendars found on this phone. Add the Google account in " +
                        "Android settings first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            calendars.forEach { calendar ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .background(Color(calendar.colour), CircleShape),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(calendar.name, style = MaterialTheme.typography.bodyLarge)
                        if (calendar.account.isNotBlank()) {
                            Text(
                                text = calendar.account,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                val selected = tags[calendar.id] ?: CalendarTag.Ignore
                SegmentedPillTabs(
                    options = CalendarTag.entries,
                    selected = selected,
                    onSelect = { tag ->
                        scope.launch { CalendarSync.setTag(context, calendar.id, tag) }
                    },
                    label = { it.label },
                )
            }

            OutlinedButton(
                onClick = {
                    scope.launch {
                        loading = true
                        calendars = CalendarSync.calendars(context)
                        loading = false
                    }
                },
            ) { Text("Refresh calendars") }
        }
    }
}
