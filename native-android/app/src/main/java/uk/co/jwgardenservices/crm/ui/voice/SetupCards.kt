package uk.co.jwgardenservices.crm.ui.voice

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.FactCheck
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import uk.co.jwgardenservices.crm.ui.common.JwCard
import uk.co.jwgardenservices.crm.ui.common.SectionCard
import uk.co.jwgardenservices.crm.ui.common.ShareText
import uk.co.jwgardenservices.crm.ui.theme.JwTheme
import uk.co.jwgardenservices.crm.ui.theme.JwTokens
import uk.co.jwgardenservices.crm.voice.AssistantService
import uk.co.jwgardenservices.crm.voice.KokoroEngine
import uk.co.jwgardenservices.crm.voice.LlmModel
import uk.co.jwgardenservices.crm.voice.LocalLlm
import uk.co.jwgardenservices.crm.voice.NemotronDownloader
import uk.co.jwgardenservices.crm.voice.PhononDownloader
import uk.co.jwgardenservices.crm.voice.RecommendedSetup
import uk.co.jwgardenservices.crm.voice.SpeechEngine
import uk.co.jwgardenservices.crm.voice.WhisperCppDownloader

private fun onWifi(context: Context): Boolean {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
    val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
}

private fun freeMemoryMb(context: Context): Long {
    val manager = context.getSystemService(ActivityManager::class.java) ?: return -1
    val info = ActivityManager.MemoryInfo().also { manager.getMemoryInfo(it) }
    return info.availMem / (1024L * 1024L)
}

/** Progress of one item's download, 0..1, or null when it is not downloading. */
@Composable
private fun progressOf(item: RecommendedSetup.Item): Float? = when (item) {
    RecommendedSetup.Item.Voice ->
        (PhononDownloader.state.collectAsStateWithLifecycle().value as? PhononDownloader.State.Working)?.progress
    RecommendedSetup.Item.Listening ->
        (NemotronDownloader.state.collectAsStateWithLifecycle().value as? NemotronDownloader.State.Working)?.progress
    RecommendedSetup.Item.SecondLook ->
        (WhisperCppDownloader.state.collectAsStateWithLifecycle().value as? WhisperCppDownloader.State.Working)?.progress
    RecommendedSetup.Item.FastModel ->
        (LocalLlm.stateFor(LlmModel.Small).collectAsStateWithLifecycle().value as? LocalLlm.State.Working)?.progress
    RecommendedSetup.Item.CleverModel ->
        (LocalLlm.stateFor(LlmModel.Large).collectAsStateWithLifecycle().value as? LocalLlm.State.Working)?.progress
}

/**
 * One tap to get the recommended set of models on Wi-Fi, with the matching
 * settings. Hidden once everything is installed.
 */
@Composable
fun RecommendedSetupCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Re-check what is installed every couple of seconds while the card is up.
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2_000)
            tick++
        }
    }
    val missing = remember(tick) { RecommendedSetup.missing(context) }
    if (missing.isEmpty()) return
    val wifi = remember(tick) { onWifi(context) }
    val totalMb = missing.sumOf { it.megabytes }
    SectionCard(
        title = "Recommended setup",
        subtitle = "What works best on this phone.",
        leadingIcon = Icons.Outlined.AutoAwesome,
    ) {
        Column(
            modifier = Modifier.padding(JwTokens.cardPadding),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Live listening, a light second look, a fast model for calls and a clever one " +
                    "for typed chat. The heavier options stay switched off, so calls don't run out of memory.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            RecommendedSetup.Item.entries.forEach { item ->
                val done = item !in missing
                val progress = progressOf(item)
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(item.label, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = "${item.role} · ${item.megabytes} MB",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = when {
                                done -> "✓ Ready"
                                progress != null -> "${(progress * 100).toInt()}%"
                                else -> "Needed"
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = if (done) JwTheme.colors.positive else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!done && progress != null) {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            Button(
                enabled = wifi,
                onClick = {
                    scope.launch { RecommendedSetup.apply(context) }
                    RecommendedSetup.startMissing(context)
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Set up ($totalMb MB to download)") }
            if (!wifi) {
                Text(
                    text = "Connect to Wi-Fi first: this is a large download.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** A nudge on the Secretary page until the recommended setup is in place. */
@Composable
fun SetupBanner(onOpen: () -> Unit) {
    val context = LocalContext.current
    val missing = remember { RecommendedSetup.missing(context) }
    if (missing.isEmpty()) return
    JwCard(modifier = Modifier.fillMaxWidth(), onClick = onOpen) {
        Text("Finish setting up Secretary", style = MaterialTheme.typography.titleSmall)
        Text(
            text = "${missing.size} ${if (missing.size == 1) "download" else "downloads"} to go. Tap to set up on Wi-Fi.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Settings → Testing: try every installed model once and report what works,
 * how long it took and how much memory was free. The report can be shared.
 */
@Composable
fun PhoneCheckCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var step by remember { mutableStateOf("") }
    var report by remember { mutableStateOf<List<String>>(emptyList()) }

    fun run() {
        running = true
        report = emptyList()
        scope.launch {
            val lines = mutableListOf<String>()
            try {
                lines += "Phone check — ${java.time.LocalDateTime.now().withNano(0)}"
                lines += "Free memory at start: ${freeMemoryMb(context)} MB"
                if (AssistantService.callActiveNow) {
                    lines += "A call is open, so the models were not tested. End the call and run again."
                    return@launch
                }
                step = "Testing listening…"
                lines += runCatching { SpeechEngine.get(context).selfTest() }
                    .getOrElse { listOf("Listening: test crashed (${it.message})") }

                step = "Testing the voice…"
                val voiceStarted = SystemClock.elapsedRealtime()
                val spoke = runCatching { KokoroEngine.get(context).speak("Testing the voice.") }.isSuccess
                val voiceSeconds = (SystemClock.elapsedRealtime() - voiceStarted) / 1000.0
                lines += if (spoke) {
                    "Voice: works, said a test line in ${"%.1f".format(voiceSeconds)} s (including loading)"
                } else {
                    "Voice: FAILED"
                }

                for (model in listOf(LlmModel.Small, LlmModel.Large)) {
                    if (!LocalLlm.isInstalled(context, model)) {
                        lines += "${model.label}: not downloaded"
                        continue
                    }
                    step = "Testing ${model.label}…"
                    val started = SystemClock.elapsedRealtime()
                    val answer = runCatching {
                        LocalLlm.generate(
                            context,
                            "Reply with the single word OK.",
                            "Are you working?",
                            maxTokens = 8,
                            temperature = 0.0,
                            smart = model == LlmModel.Large,
                        )
                    }.getOrNull()
                    val seconds = (SystemClock.elapsedRealtime() - started) / 1000.0
                    val used = LocalLlm.lastUsed
                    lines += when {
                        answer.isNullOrBlank() -> "${model.label}: FAILED to answer (memory or files)"
                        used != null && used != model ->
                            "${model.label}: could not load, ${used.label} answered instead (${"%.1f".format(seconds)} s)"
                        else -> "${model.label}: works, answered in ${"%.1f".format(seconds)} s (including loading)"
                    }
                }
                lines += "Free memory at end: ${freeMemoryMb(context)} MB"
            } finally {
                report = lines
                running = false
                step = ""
            }
        }
    }

    SectionCard(
        title = "Check my phone",
        subtitle = "Try every model once and report.",
        leadingIcon = Icons.Outlined.FactCheck,
    ) {
        Column(
            modifier = Modifier.padding(JwTokens.cardPadding),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Loads each installed model, times it and checks memory. It takes up to a minute " +
                    "and says a short test line out loud. Share the report if something looks wrong.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (running) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(step, style = MaterialTheme.typography.bodySmall)
            } else {
                Button(onClick = { run() }, modifier = Modifier.fillMaxWidth()) { Text("Run the check") }
            }
            report.forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (line.contains("FAILED")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
            }
            if (report.isNotEmpty() && !running) {
                OutlinedButton(
                    onClick = { ShareText.share(context, report.joinToString("\n"), "Phone check") },
                ) { Text("Share report") }
            }
        }
    }
}
