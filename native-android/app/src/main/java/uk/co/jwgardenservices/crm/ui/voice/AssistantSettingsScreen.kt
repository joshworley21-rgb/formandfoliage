package uk.co.jwgardenservices.crm.ui.voice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import uk.co.jwgardenservices.crm.ui.common.OptionPicker
import uk.co.jwgardenservices.crm.ui.common.PageHeader
import uk.co.jwgardenservices.crm.ui.common.SectionCard
import uk.co.jwgardenservices.crm.voice.AssistantPreferences
import uk.co.jwgardenservices.crm.voice.KokoroVoice
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The offline assistant's own settings: Kokoro voice profile and the contents
 * of the app's internal `files/models` folder, where the Qwen GGUF lives.
 */
@Composable
fun AssistantSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val voice by AssistantPreferences.voiceFlow(context)
        .collectAsStateWithLifecycle(initialValue = KokoroVoice.BmFable)

    var models by remember { mutableStateOf<List<File>>(emptyList()) }
    var refresh by remember { mutableIntStateOf(0) }

    LaunchedEffect(refresh) {
        val dir = File(context.filesDir, "models")
        models = dir.listFiles()?.sortedBy { it.name.lowercase() } ?: emptyList()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TextButton(onClick = onBack) { Text("Back") }

        PageHeader(
            title = "Assistant settings",
            description = "Voice and local models for the offline assistant.",
        )

        SectionCard(title = "Kokoro voice") {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OptionPicker(
                    options = KokoroVoice.entries,
                    selected = voice,
                    label = { it?.label ?: "bm_fable — British male" },
                    onSelect = { selected ->
                        scope.launch { AssistantPreferences.setVoice(context, selected) }
                    },
                )
                Text(
                    text = "Used for the filler and every spoken confirmation from the next reply.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SectionCard(
            title = "Local models",
            actionLabel = "Refresh",
            onAction = { refresh += 1 },
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Internal storage: ${File(context.filesDir, "models").absolutePath}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (models.isEmpty()) {
                    Text(
                        text = "No files yet. Push a quantised Qwen GGUF with adb run-as; see the README for the exact commands.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp),
                        contentPadding = PaddingValues(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        items(models) { file ->
                            ModelRow(file)
                            HorizontalDivider()
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ModelRow(file: File) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = file.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "${formatSize(file.length())} · ${formatDate(file.lastModified())}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1_073_741_824 -> "%.1f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1_024 -> "%.0f KB".format(bytes / 1_024.0)
    else -> "$bytes B"
}

private fun formatDate(epochMillis: Long): String = DATE.format(
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()),
)

private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")
