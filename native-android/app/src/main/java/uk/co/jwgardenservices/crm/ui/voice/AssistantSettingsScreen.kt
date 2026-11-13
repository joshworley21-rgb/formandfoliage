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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
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
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import uk.co.jwgardenservices.crm.core.CrashLog
import uk.co.jwgardenservices.crm.ui.common.OptionPicker
import uk.co.jwgardenservices.crm.ui.common.PageHeader
import uk.co.jwgardenservices.crm.ui.common.SectionCard
import uk.co.jwgardenservices.crm.ui.common.ShareText
import uk.co.jwgardenservices.crm.voice.AssistantPreferences
import uk.co.jwgardenservices.crm.voice.KokoroHqDownloader
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

    val highQuality by AssistantPreferences.highQualityFlow(context)
        .collectAsStateWithLifecycle(initialValue = false)
    var hqInstalled by remember { mutableStateOf(KokoroHqDownloader.isInstalled(context)) }
    var downloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableIntStateOf(0) }
    var downloadError by remember { mutableStateOf<String?>(null) }

    var models by remember { mutableStateOf<List<File>>(emptyList()) }
    var refresh by remember { mutableIntStateOf(0) }
    var crashRefresh by remember { mutableIntStateOf(0) }
    val crashReport = remember(crashRefresh) { CrashLog.read(context) }

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

        SectionCard(title = "Voice quality") {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = if (hqInstalled) {
                        "The full fp32 Kokoro model is installed. It sounds more natural than the bundled int8 voice."
                    } else {
                        "The bundled voice is the int8 model (109 MB). The full fp32 model sounds more natural; it is a one-time 350 MB download and stays on the phone."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (downloading) {
                    LinearProgressIndicator(
                        progress = { downloadProgress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("Downloading… $downloadProgress%", style = MaterialTheme.typography.bodySmall)
                }
                if (hqInstalled) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Switch(
                            checked = highQuality,
                            onCheckedChange = { enabled ->
                                scope.launch { AssistantPreferences.setHighQuality(context, enabled) }
                            },
                        )
                        Text(
                            text = "Use high-quality voice",
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    TextButton(
                        onClick = {
                            KokoroHqDownloader.modelFile(context).delete()
                            hqInstalled = false
                            scope.launch { AssistantPreferences.setHighQuality(context, false) }
                        },
                    ) { Text("Remove downloaded model") }
                } else {
                    OutlinedButton(
                        enabled = !downloading,
                        onClick = {
                            if (!isUnmetered(context)) {
                                downloadError = "Connect to Wi-Fi to download the 350 MB voice."
                                return@OutlinedButton
                            }
                            downloading = true
                            downloadError = null
                            downloadProgress = 0
                            scope.launch {
                                runCatching {
                                    KokoroHqDownloader.download(context) { percent ->
                                        downloadProgress = percent
                                    }
                                }.onSuccess {
                                    hqInstalled = true
                                    AssistantPreferences.setHighQuality(context, true)
                                }.onFailure { error ->
                                    downloadError = error.message ?: "Download failed."
                                }
                                downloading = false
                            }
                        },
                    ) { Text("Download high-quality voice (350 MB)") }
                }
                downloadError?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    text = "Voice changes apply the next time the Assistant starts.",
                    style = MaterialTheme.typography.labelSmall,
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

        SectionCard(title = "Diagnostics") {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = if (crashReport.isNullOrBlank()) {
                        "No crash recorded. If the assistant disappears unexpectedly, reopen the app and share this report; it includes native crashes and low-memory kills."
                    } else {
                        "A crash report is available. Share it to diagnose the last failure."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            ShareText.share(
                                context,
                                CrashLog.diagnostics(context),
                                "Assistant diagnostics",
                            )
                        },
                    ) { Text("Share report") }
                    TextButton(
                        onClick = {
                            CrashLog.clear(context)
                            crashRefresh += 1
                        },
                    ) { Text("Clear") }
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

private fun isUnmetered(context: Context): Boolean {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
    val caps = manager.getNetworkCapabilities(manager.activeNetwork ?: return false) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
}

private fun formatDate(epochMillis: Long): String = DATE.format(
    Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()),
)

private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")
