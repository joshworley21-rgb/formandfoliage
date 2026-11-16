package uk.co.jwgardenservices.crm.ui.voice

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
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
import uk.co.jwgardenservices.crm.voice.AssistantContext
import uk.co.jwgardenservices.crm.voice.AssistantMemory
import uk.co.jwgardenservices.crm.voice.AsrEngine
import uk.co.jwgardenservices.crm.voice.AssistantPreferences
import uk.co.jwgardenservices.crm.voice.KokoroHqDownloader
import uk.co.jwgardenservices.crm.voice.KokoroEngine
import uk.co.jwgardenservices.crm.voice.KokoroVoice
import uk.co.jwgardenservices.crm.voice.MicSource
import uk.co.jwgardenservices.crm.voice.MoonshineDownloader
import uk.co.jwgardenservices.crm.voice.NemotronDownloader
import uk.co.jwgardenservices.crm.voice.PhononDownloader
import uk.co.jwgardenservices.crm.voice.PhononVoice
import uk.co.jwgardenservices.crm.voice.PiperVoice
import uk.co.jwgardenservices.crm.voice.PiperVoiceDownloader
import uk.co.jwgardenservices.crm.voice.SpeechEngine

/**
 * The offline assistant's own settings: Kokoro voice profile and the contents
 * and the downloaded voice models that live in app storage.
 */
@Composable
fun AssistantSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val voice by AssistantPreferences.voiceFlow(context)
        .collectAsStateWithLifecycle(initialValue = KokoroVoice.BmFable)

    val highQuality by AssistantPreferences.highQualityFlow(context)
        .collectAsStateWithLifecycle(initialValue = false)
    val savedSpeed by AssistantPreferences.speedFlow(context)
        .collectAsStateWithLifecycle(initialValue = AssistantPreferences.DEFAULT_SPEED)
    var draftSpeed by remember(savedSpeed) { mutableFloatStateOf(savedSpeed) }
    var previewingVoice by remember { mutableStateOf(false) }
    val hqState by KokoroHqDownloader.state.collectAsStateWithLifecycle()
    var hqInstalled by remember { mutableStateOf(KokoroHqDownloader.isInstalled(context)) }
    var savedBytes by remember { mutableLongStateOf(KokoroHqDownloader.savedBytes(context)) }
    val unmetered = remember { isUnmetered(context) }

    val facts by AssistantMemory.factsFlow(context)
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val cloudConversation by AssistantMemory.cloudConversationFlow(context)
        .collectAsStateWithLifecycle(initialValue = false)
    val onlineVoice by AssistantPreferences.onlineVoiceFlow(context)
        .collectAsStateWithLifecycle(initialValue = false)
    val pickupGreeting by AssistantPreferences.pickupFlow(context)
        .collectAsStateWithLifecycle(initialValue = true)
    val followUp by AssistantPreferences.followUpFlow(context)
        .collectAsStateWithLifecycle(initialValue = true)
    val micSource by AssistantPreferences.micSourceFlow(context)
        .collectAsStateWithLifecycle(initialValue = MicSource.Communication)
    val phononEnabled by AssistantPreferences.phononFlow(context)
        .collectAsStateWithLifecycle(initialValue = false)
    val phononState by PhononDownloader.state.collectAsStateWithLifecycle()
    var phononInstalled by remember { mutableStateOf(PhononDownloader.isInstalled(context)) }
    var phononSaved by remember { mutableLongStateOf(PhononDownloader.savedBytes(context)) }
    val phononVoice by AssistantPreferences.phononVoiceFlow(context)
        .collectAsStateWithLifecycle(initialValue = PhononVoice.Freya)
    var voiceDownloading by remember { mutableStateOf(false) }
    var voiceError by remember { mutableStateOf<String?>(null) }
    val moonshineState by MoonshineDownloader.state.collectAsStateWithLifecycle()
    var moonshineInstalled by remember { mutableStateOf(MoonshineDownloader.isInstalled(context)) }
    var moonshineSaved by remember { mutableLongStateOf(MoonshineDownloader.savedBytes(context)) }
    val nemotronState by NemotronDownloader.state.collectAsStateWithLifecycle()
    var nemotronInstalled by remember { mutableStateOf(NemotronDownloader.isInstalled(context)) }
    var nemotronSaved by remember { mutableLongStateOf(NemotronDownloader.savedBytes(context)) }
    val asrEngine by AssistantPreferences.asrEngineFlow(context)
        .collectAsStateWithLifecycle(initialValue = AsrEngine.Moonshine)

    LaunchedEffect(phononState) {
        when (val snapshot = phononState) {
            is PhononDownloader.State.Done -> {
                phononInstalled = true
                AssistantPreferences.setPhonon(context, true)
                KokoroEngine.get(context).reload()
                PhononDownloader.clearResult()
                phononSaved = 0
            }
            is PhononDownloader.State.Working -> Unit
            else -> phononSaved = PhononDownloader.savedBytes(context)
        }
    }
    LaunchedEffect(moonshineState) {
        when (val snapshot = moonshineState) {
            is MoonshineDownloader.State.Done -> {
                moonshineInstalled = true
                moonshineSaved = 0
                MoonshineDownloader.clearResult()
            }
            is MoonshineDownloader.State.Working -> Unit
            else -> moonshineSaved = MoonshineDownloader.savedBytes(context)
        }
    }
    LaunchedEffect(nemotronState) {
        when (val snapshot = nemotronState) {
            is NemotronDownloader.State.Done -> {
                nemotronInstalled = true
                nemotronSaved = 0
                NemotronDownloader.clearResult()
            }
            is NemotronDownloader.State.Working -> Unit
            else -> nemotronSaved = NemotronDownloader.savedBytes(context)
        }
    }
    LaunchedEffect(hqState) {
        when (hqState) {
            is KokoroHqDownloader.State.Done -> {
                hqInstalled = true
                AssistantPreferences.setHighQuality(context, true)
                KokoroHqDownloader.clearResult()
                savedBytes = 0
            }
            is KokoroHqDownloader.State.Working -> Unit
            else -> savedBytes = KokoroHqDownloader.savedBytes(context)
        }
    }

    var crashRefresh by remember { mutableIntStateOf(0) }
    val crashReport = remember(crashRefresh) { CrashLog.read(context) }

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
            description = "Voice and local voice models for the offline assistant.",
        )

        SectionCard(title = "Phonon 31m (fast)") {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Phonon 31m is Apache 2.0, about 62 MB, and streams audio while it " +
                        "speaks on the CPU. It replaces the other local voices while it is on; " +
                        "Kokoro stays as the fallback.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                val working = phononState as? PhononDownloader.State.Working
                val failure = phononState as? PhononDownloader.State.Failed
                if (working != null) {
                    LinearProgressIndicator(
                        progress = { working.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "Downloading… ${working.done / MEGABYTE} of " +
                            "${working.total / MEGABYTE} MB",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = { PhononDownloader.cancel() }) { Text("Cancel") }
                }

                if (phononInstalled) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Switch(
                            checked = phononEnabled,
                            onCheckedChange = { enabled ->
                                scope.launch {
                                    AssistantPreferences.setPhonon(context, enabled)
                                    KokoroEngine.get(context).reload()
                                }
                            },
                        )
                        Text(
                            text = "Use Phonon 31m",
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                    Text(
                        text = "Voice",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    OptionPicker(
                        options = PhononVoice.entries,
                        selected = phononVoice,
                        label = { it?.label ?: PhononVoice.Freya.label },
                        enabled = !voiceDownloading,
                        onSelect = { selected ->
                            scope.launch {
                                voiceDownloading = true
                                voiceError = null
                                val ok = PhononDownloader.installVoice(context, selected)
                                if (ok) {
                                    AssistantPreferences.setPhononVoice(context, selected)
                                    KokoroEngine.get(context).reload()
                                } else {
                                    voiceError = "Could not download that voice. Check your connection."
                                }
                                voiceDownloading = false
                            }
                        },
                    )
                    if (voiceDownloading) {
                        Text(
                            text = "Downloading voice…",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    voiceError?.let { message ->
                        Text(
                            text = message,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    TextButton(
                        enabled = phononEnabled && !voiceDownloading,
                        onClick = {
                            previewingVoice = true
                            scope.launch {
                                try {
                                    KokoroEngine.get(context).speak(
                                        PREVIEW_TEXT,
                                        voice.speakerId,
                                        draftSpeed,
                                    )
                                } catch (_: Exception) {
                                    // The reply path reports its own errors.
                                } finally {
                                    previewingVoice = false
                                }
                            }
                        },
                    ) {
                        Text(if (previewingVoice) "Playing sample…" else "Preview Phonon voice")
                    }
                    Text(
                        text = "Gradium's website demos use catalog voices; the checkpoint also " +
                            "ships its own default. The 40m models on the site are non-commercial, " +
                            "so the app uses 31m. Pick a voice and preview it here.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = {
                            scope.launch {
                                PhononDownloader.remove(context)
                                AssistantPreferences.setPhonon(context, false)
                                phononInstalled = false
                                phononSaved = 0
                                KokoroEngine.get(context).reload()
                            }
                        },
                    ) { Text("Remove Phonon model") }
                } else if (working == null) {
                    OutlinedButton(
                        onClick = {
                            if (!unmetered) return@OutlinedButton
                            PhononDownloader.start(context)
                        },
                    ) {
                        Text(
                            if (phononSaved > 0) {
                                "Resume Phonon download (${phononSaved / MEGABYTE} MB saved)"
                            } else {
                                "Download Phonon 31m (62 MB)"
                            },
                        )
                    }
                }

                if (!unmetered && working == null && !phononInstalled) {
                    Text(
                        text = "Connect to Wi-Fi to download the model.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                failure?.let { error ->
                    Text(
                        text = error.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        SectionCard(title = "Nemotron ASR (streaming)") {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "NVIDIA Nemotron 3.5 Speech Streaming 0.6B, English (en) only, " +
                        "NVIDIA Open Model License (commercial use permitted). About 662 MB. " +
                        "True streaming: live partial words like the Zipformer, from a " +
                        "2026-era model trained for noisy phone audio. While selected it " +
                        "holds about 650 MB in memory; switch back after comparing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                val working = nemotronState as? NemotronDownloader.State.Working
                val failure = nemotronState as? NemotronDownloader.State.Failed
                if (working != null) {
                    LinearProgressIndicator(
                        progress = { working.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "Downloading… ${working.done / MEGABYTE} of " +
                            "${working.total / MEGABYTE} MB",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = { NemotronDownloader.cancel() }) { Text("Cancel") }
                }

                if (nemotronInstalled) {
                    Text(
                        text = "Remove it to free the storage.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = {
                            scope.launch {
                                NemotronDownloader.remove(context)
                                if (asrEngine == AsrEngine.Nemotron) {
                                    AssistantPreferences.setAsrEngine(context, AsrEngine.Moonshine)
                                }
                                SpeechEngine.get(context).releaseNemotron()
                                nemotronInstalled = false
                                nemotronSaved = 0
                            }
                        },
                    ) { Text("Remove Nemotron model") }
                } else if (working == null) {
                    OutlinedButton(
                        onClick = { NemotronDownloader.start(context) },
                    ) {
                        Text(
                            if (nemotronSaved > 0) {
                                "Resume Nemotron download (${nemotronSaved / MEGABYTE} MB saved)"
                            } else {
                                "Download Nemotron (662 MB)"
                            },
                        )
                    }
                }

                if (working == null && !nemotronInstalled) {
                    Text(
                        text = "Downloads on Wi-Fi or mobile data. About 662 MB.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                failure?.let { error ->
                    Text(
                        text = error.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        SectionCard(title = "Moonshine ASR (experimental)") {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Moonshine v2 base English, MIT licence. About 135 MB. It is an " +
                        "offline model: there are no live partial words, just a transcript when " +
                        "you stop speaking. The Zipformer stays the default for its live words " +
                        "and automatic client-name hotwords; switch below to compare.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                val working = moonshineState as? MoonshineDownloader.State.Working
                val failure = moonshineState as? MoonshineDownloader.State.Failed
                if (working != null) {
                    LinearProgressIndicator(
                        progress = { working.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "Downloading… ${working.done / MEGABYTE} of " +
                            "${working.total / MEGABYTE} MB",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = { MoonshineDownloader.cancel() }) { Text("Cancel") }
                }

                if (moonshineInstalled) {
                    Text(
                        text = "Speech engine",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    OptionPicker(
                        options = AsrEngine.entries,
                        selected = asrEngine,
                        label = { it?.label ?: AsrEngine.Moonshine.label },
                        onSelect = { engine ->
                            scope.launch {
                                AssistantPreferences.setAsrEngine(context, engine)
                                val speechEngine = SpeechEngine.get(context)
                                if (engine != AsrEngine.Moonshine) speechEngine.releaseMoonshine()
                                if (engine != AsrEngine.Nemotron) speechEngine.releaseNemotron()
                            }
                        },
                    )
                    Text(
                        text = "Takes effect on the next call. Moonshine keeps about 135 MB " +
                            "in memory while selected; switch back to Zipformer after comparing.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = {
                            scope.launch {
                                MoonshineDownloader.remove(context)
                                AssistantPreferences.setAsrEngine(context, AsrEngine.Moonshine)
                                SpeechEngine.get(context).releaseMoonshine()
                                moonshineInstalled = false
                                moonshineSaved = 0
                            }
                        },
                    ) { Text("Remove Moonshine model") }
                } else if (working == null) {
                    OutlinedButton(
                        onClick = { MoonshineDownloader.start(context) },
                    ) {
                        Text(
                            if (moonshineSaved > 0) {
                                "Resume Moonshine download (${moonshineSaved / MEGABYTE} MB saved)"
                            } else {
                                "Download Moonshine v2 (135 MB)"
                            },
                        )
                    }
                }

                if (working == null && !moonshineInstalled) {
                    Text(
                        text = "Downloads on Wi-Fi or mobile data. About 135 MB.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                failure?.let { error ->
                    Text(
                        text = error.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        SectionCard(title = "Call") {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Switch(
                        checked = pickupGreeting,
                        onCheckedChange = { enabled ->
                            scope.launch { AssistantPreferences.setPickup(context, enabled) }
                        },
                    )
                    Text(
                        text = "Brief me on today's schedule when a call connects",
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                Text(
                    text = "A fresh call opens with the first job on today's schedule (or the " +
                        "one you are on now) before the microphone opens, so \"log four hours " +
                        "and mark it done\" already knows the client. Turning it off makes the " +
                        "assistant listen immediately.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Switch(
                        checked = followUp,
                        onCheckedChange = { enabled ->
                            scope.launch { AssistantPreferences.setFollowUp(context, enabled) }
                        },
                    )
                    Text(
                        text = "Ask \"anything else?\" after each answer",
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                Text(
                    text = "A finished answer ends with a short nudge so the call keeps its " +
                        "rhythm. Replies that already ask a question are left alone.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SectionCard(title = "Conversation & memory") {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Switch(
                        checked = cloudConversation,
                        onCheckedChange = { enabled ->
                            scope.launch { AssistantMemory.setCloudConversation(context, enabled) }
                        },
                    )
                    Text(
                        text = "Ask OpenAI for anything else (costs per reply)",
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                Text(
                    text = "Off by default: the diary, hours, jobs and money answers are free " +
                        "and instant. Turn this on and a sentence they cannot place is sent to " +
                        "the cloud with the business snapshot, in one request, billed to your " +
                        "OpenAI key. Off means everything stays on the phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider()
                Text(
                    text = "What it remembers",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                if (facts.isEmpty()) {
                    Text(
                        text = "Say “remember that …” to teach it something about the business. " +
                            "“What do you remember?” reads the list back, “forget …” removes one.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    facts.forEach { fact ->
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text(
                                text = fact.text,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = {
                                    scope.launch { AssistantMemory.forget(context, fact.text) }
                                },
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Delete,
                                    contentDescription = "Forget this",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }

        SectionCard(title = "Microphone") {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OptionPicker(
                    options = MicSource.entries,
                    selected = micSource,
                    label = { it?.label ?: "Barge-in (echo cancelling)" },
                    onSelect = { selected ->
                        scope.launch { AssistantPreferences.setMicSource(context, selected) }
                    },
                )
                Text(
                    text = "Barge-in needs VOICE_COMMUNICATION, which applies the phone's echo " +
                        "canceller. If words are still missed, choose Best recognition; the " +
                        "assistant then cannot hear you over its own voice. Applies on the next call.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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

private const val MEGABYTE = 1024L * 1024L

/** Spoken by "Preview this voice". */
private const val PREVIEW_TEXT = "Hello, this is how I sound when I read your diary."

private fun isUnmetered(context: Context): Boolean {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
    val caps = manager.getNetworkCapabilities(manager.activeNetwork ?: return false) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
}
