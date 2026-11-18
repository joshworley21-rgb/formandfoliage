package uk.co.jwgardenservices.crm.ui.voice

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.VolumeUp
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
import androidx.compose.ui.Alignment
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
import uk.co.jwgardenservices.crm.voice.MicTest
import uk.co.jwgardenservices.crm.voice.MoonshineDownloader
import uk.co.jwgardenservices.crm.voice.NemotronDownloader
import uk.co.jwgardenservices.crm.voice.PhononDownloader
import uk.co.jwgardenservices.crm.voice.PunctuationDownloader
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
    val piperState by PiperVoiceDownloader.state.collectAsStateWithLifecycle()
    val localVoice by AssistantPreferences.piperVoiceFlow(context)
        .collectAsStateWithLifecycle(initialValue = null)
    var localInstalled by remember {
        mutableStateOf(PiperVoice.entries.associateWith { PiperVoiceDownloader.isInstalled(context, it) })
    }
    var phononInstalled by remember { mutableStateOf(PhononDownloader.isInstalled(context)) }
    var phononSaved by remember { mutableLongStateOf(PhononDownloader.savedBytes(context)) }
    val phononVoice by AssistantPreferences.phononVoiceFlow(context)
        .collectAsStateWithLifecycle(initialValue = PhononVoice.Freya.name)
    var customVoices by remember { mutableStateOf(PhononDownloader.customVoices(context)) }
    var voiceDownloading by remember { mutableStateOf(false) }
    var voiceError by remember { mutableStateOf<String?>(null) }
    val moonshineState by MoonshineDownloader.state.collectAsStateWithLifecycle()
    var moonshineInstalled by remember { mutableStateOf(MoonshineDownloader.isInstalled(context)) }
    var moonshineSaved by remember { mutableLongStateOf(MoonshineDownloader.savedBytes(context)) }
    val nemotronState by NemotronDownloader.state.collectAsStateWithLifecycle()
    var nemotronInstalled by remember { mutableStateOf(NemotronDownloader.isInstalled(context)) }
    var nemotronSaved by remember { mutableLongStateOf(NemotronDownloader.savedBytes(context)) }
    val punctuationState by PunctuationDownloader.state.collectAsStateWithLifecycle()
    var punctuationInstalled by remember {
        mutableStateOf(PunctuationDownloader.isInstalled(context))
    }
    var punctuationSaved by remember {
        mutableLongStateOf(PunctuationDownloader.savedBytes(context))
    }
    val asrEngine by AssistantPreferences.asrEngineFlow(context)
        .collectAsStateWithLifecycle(initialValue = AsrEngine.Moonshine)

    LaunchedEffect(piperState) {
        when (val snapshot = piperState) {
            is PiperVoiceDownloader.State.Done -> {
                localInstalled = PiperVoice.entries.associateWith {
                    PiperVoiceDownloader.isInstalled(context, it)
                }
                AssistantPreferences.setPiperVoice(context, snapshot.voice)
                KokoroEngine.get(context).reload()
                PiperVoiceDownloader.clearResult()
            }
            is PiperVoiceDownloader.State.Failed -> Unit
            else -> Unit
        }
    }
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
    LaunchedEffect(punctuationState) {
        when (punctuationState) {
            is PunctuationDownloader.State.Done -> {
                punctuationInstalled = true
                punctuationSaved = 0
                PunctuationDownloader.clearResult()
            }
            is PunctuationDownloader.State.Working -> Unit
            else -> punctuationSaved = PunctuationDownloader.savedBytes(context)
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
                        selected = PhononVoice.entries.firstOrNull { it.name == phononVoice },
                        label = { it?.label ?: "Custom — $phononVoice" },
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

                    Text(
                        text = "Custom voices",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    customVoices.forEach { name ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Custom — $name",
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Text(
                                    text = "Imported voice file, works offline",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (phononVoice == name) {
                                Text(
                                    text = "In use",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            } else {
                                OutlinedButton(
                                    onClick = {
                                        scope.launch {
                                            AssistantPreferences.setPhononVoice(context, name)
                                            KokoroEngine.get(context).reload()
                                        }
                                    },
                                ) { Text("Use") }
                            }
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        PhononDownloader.removeCustomVoice(context, name)
                                        customVoices = PhononDownloader.customVoices(context)
                                        if (phononVoice == name) {
                                            AssistantPreferences.setPhononVoice(
                                                context,
                                                PhononVoice.Freya,
                                            )
                                            KokoroEngine.get(context).reload()
                                        }
                                    }
                                },
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Delete,
                                    contentDescription = "Remove custom voice $name",
                                )
                            }
                        }
                    }
                    val pickVoiceFile = rememberLauncherForActivityResult(
                        ActivityResultContracts.GetContent(),
                    ) { uri ->
                        if (uri == null) return@rememberLauncherForActivityResult
                        scope.launch {
                            voiceError = null
                            val imported = PhononDownloader.importVoice(context, uri)
                            if (imported != null) {
                                customVoices = PhononDownloader.customVoices(context)
                                AssistantPreferences.setPhononVoice(context, imported)
                                KokoroEngine.get(context).reload()
                            } else {
                                voiceError = "That file is not a Phonon voice."
                            }
                        }
                    }
                    OutlinedButton(
                        enabled = phononEnabled,
                        onClick = { pickVoiceFile.launch("*/*") },
                    ) { Text("Import custom voice (.safetensors)") }
                    Text(
                        text = "Gradium custom voices are not public downloads. Export yours " +
                            "from Gradium Studio, or with the download-voice.py script, then " +
                            "import the .safetensors file here. It is copied into the app and " +
                            "spoken on-device.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

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

        SectionCard(title = "Local voices (Piper & Supertonic)") {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Downloadable on-device voices. A downloaded voice replaces Phonon " +
                        "while it is selected; clearing the choice goes back to Phonon. Each " +
                        "is a one-off download over Wi-Fi.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                val working = piperState as? PiperVoiceDownloader.State.Working
                if (working != null) {
                    LinearProgressIndicator(
                        progress = { working.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "${working.voice.label}: ${working.phase.name.lowercase()} " +
                            "${working.done / MEGABYTE} of ${working.total / MEGABYTE} MB",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = { PiperVoiceDownloader.cancel() }) { Text("Cancel") }
                }

                // Going back to Phonon is always available.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Phonon (default)", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = "Streaming 31m voice, about 62 MB",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (localVoice == null) {
                        Text(
                            text = "In use",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    } else {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    AssistantPreferences.setPiperVoice(context, null)
                                    KokoroEngine.get(context).reload()
                                }
                            },
                        ) { Text("Use") }
                    }
                }

                PiperVoice.entries.forEach { voice ->
                    val installed = localInstalled[voice] == true
                    val selected = localVoice == voice
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(voice.label, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = "${voice.bytes / MEGABYTE} MB, on-device",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        val remove = {
                            scope.launch {
                                if (selected) {
                                    AssistantPreferences.setPiperVoice(context, null)
                                }
                                PiperVoiceDownloader.remove(context, voice)
                                localInstalled = PiperVoice.entries.associateWith {
                                    PiperVoiceDownloader.isInstalled(context, it)
                                }
                                KokoroEngine.get(context).reload()
                            }
                            Unit
                        }
                        val audition = {
                            scope.launch {
                                AssistantPreferences.setPiperVoice(context, voice)
                                KokoroEngine.get(context).reload()
                                runCatching {
                                    KokoroEngine.get(context).speak(PREVIEW_TEXT, 0, draftSpeed)
                                }
                            }
                            Unit
                        }
                        val use = {
                            scope.launch {
                                AssistantPreferences.setPiperVoice(context, voice)
                                KokoroEngine.get(context).reload()
                            }
                            Unit
                        }
                        when {
                            selected -> {
                                Text(
                                    text = "In use",
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                                IconButton(onClick = audition) {
                                    Icon(
                                        imageVector = Icons.Outlined.VolumeUp,
                                        contentDescription = "Preview ${voice.label}",
                                    )
                                }
                                TextButton(onClick = remove) { Text("Remove") }
                            }
                            installed -> {
                                OutlinedButton(onClick = use) { Text("Use") }
                                IconButton(onClick = audition) {
                                    Icon(
                                        imageVector = Icons.Outlined.VolumeUp,
                                        contentDescription = "Preview ${voice.label}",
                                    )
                                }
                                TextButton(onClick = remove) { Text("Remove") }
                            }
                            working == null -> OutlinedButton(
                                onClick = { PiperVoiceDownloader.start(context, voice) },
                            ) { Text("Download") }
                        }
                    }
                }

                (piperState as? PiperVoiceDownloader.State.Failed)?.let { failure ->
                    Text(
                        text = "${failure.voice.label}: ${failure.message}",
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
                        "NVIDIA Open Model License (commercial use permitted). About 662 MB, " +
                        "April 2026 560 ms export. True streaming: live partial words. " +
                        "While Nemotron streams the partials, the finished sentence is " +
                        "re-decoded with Moonshine when it is installed, so a quiet word " +
                        "missed by NeMo still comes through in the final transcript. If a " +
                        "call produces no words at all the saved engine also switches to " +
                        "Moonshine. While selected it holds about 650 MB in memory.",
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
                        "you stop speaking. In testing it transcribed quiet phone audio that " +
                        "Nemotron missed entirely, so it is the recommended engine day to day. " +
                        "Client names are corrected after recognition on either engine.",
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
                        text = "Takes effect on the next call. Recommended: Moonshine — it " +
                            "copes far better with this phone's quiet microphone. Nemotron is " +
                            "worth using only when you want live partial words.",
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

        SectionCard(title = "Punctuation") {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "A tiny English punctuation model (Apache 2.0) capitalises and " +
                        "punctuates finished sentences before they are saved, so dictated notes " +
                        "read like English and commands split cleanly. About 8 MB.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                val working = punctuationState as? PunctuationDownloader.State.Working
                val failure = punctuationState as? PunctuationDownloader.State.Failed
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
                    TextButton(onClick = { PunctuationDownloader.cancel() }) { Text("Cancel") }
                }

                if (punctuationInstalled) {
                    TextButton(
                        onClick = {
                            scope.launch {
                                PunctuationDownloader.remove(context)
                                punctuationInstalled = false
                                punctuationSaved = 0
                            }
                        },
                    ) { Text("Remove punctuation model") }
                } else if (working == null) {
                    OutlinedButton(
                        onClick = { PunctuationDownloader.start(context) },
                    ) {
                        Text(
                            if (punctuationSaved > 0) {
                                "Resume download (${punctuationSaved / MEGABYTE} MB saved)"
                            } else {
                                "Download punctuation model (8 MB)"
                            },
                        )
                    }
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

                // Hear exactly what the phone hears, through the same source
                // the assistant uses. One beep first: no beep means the output
                // is misrouted, beep but no voice means the mic is the problem.
                var micTesting by remember { mutableStateOf(false) }
                var micTestNote by remember { mutableStateOf<String?>(null) }
                OutlinedButton(
                    enabled = !micTesting,
                    onClick = {
                        scope.launch {
                            micTesting = true
                            micTestNote = "Recording 5 seconds — say something."
                            val outcome = runCatching {
                                MicTest.record(context, micSource.androidSource)
                            }
                            val samples = outcome.getOrNull()
                            if (samples == null || samples < 2_000) {
                                micTestNote = "No audio was captured. " +
                                    (outcome.exceptionOrNull()?.message ?: "Check the microphone " +
                                        "permission.")
                            } else {
                                micTestNote = "Playing it back — you should hear a beep, then you."
                                runCatching { MicTest.play(context) }
                                micTestNote = "Beep but no voice? The microphone is the problem. " +
                                    "Both? The phone's audio output is the problem."
                            }
                            micTesting = false
                        }
                    },
                ) {
                    Text(if (micTesting) "Testing…" else "Test microphone (5 s)")
                }
                micTestNote?.let { note ->
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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

private const val MEGABYTE = 1024L * 1024L

/** Spoken by "Preview this voice". */
private const val PREVIEW_TEXT = "Hello, this is how I sound when I read your diary."

private fun isUnmetered(context: Context): Boolean {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
    val caps = manager.getNetworkCapabilities(manager.activeNetwork ?: return false) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
}
