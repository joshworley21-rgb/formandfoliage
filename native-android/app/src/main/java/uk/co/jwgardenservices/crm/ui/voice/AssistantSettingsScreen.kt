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
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Notes
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
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
import uk.co.jwgardenservices.crm.ui.common.ScreenHeader
import uk.co.jwgardenservices.crm.ui.common.SectionCard
import uk.co.jwgardenservices.crm.ui.common.SegmentedPills
import uk.co.jwgardenservices.crm.ui.common.SettingSwitchRow
import uk.co.jwgardenservices.crm.ui.common.ShareText
import uk.co.jwgardenservices.crm.ui.theme.JwTokens
import uk.co.jwgardenservices.crm.voice.AssistantContext
import uk.co.jwgardenservices.crm.voice.AssistantMemory
import uk.co.jwgardenservices.crm.voice.AsrEngine
import uk.co.jwgardenservices.crm.voice.AssistantPreferences
import uk.co.jwgardenservices.crm.voice.KokoroCustomVoices
import uk.co.jwgardenservices.crm.voice.KokoroHqDownloader
import uk.co.jwgardenservices.crm.voice.KokoroEngine
import uk.co.jwgardenservices.crm.voice.KokoroVoice
import uk.co.jwgardenservices.crm.voice.LocalLlm
import uk.co.jwgardenservices.crm.voice.MicSource
import uk.co.jwgardenservices.crm.voice.MicTest
import uk.co.jwgardenservices.crm.voice.MoonshineDownloader
import uk.co.jwgardenservices.crm.voice.NemotronDownloader
import uk.co.jwgardenservices.crm.voice.PhononDownloader
import uk.co.jwgardenservices.crm.voice.PunctuationDownloader
import uk.co.jwgardenservices.crm.voice.PhononVoice
import uk.co.jwgardenservices.crm.voice.PiperVoice
import uk.co.jwgardenservices.crm.voice.PiperVoiceDownloader
import uk.co.jwgardenservices.crm.voice.PersonaHumour
import uk.co.jwgardenservices.crm.voice.PersonaLength
import uk.co.jwgardenservices.crm.voice.PersonaTone
import uk.co.jwgardenservices.crm.voice.PronunciationDictionary
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
        .collectAsStateWithLifecycle(initialValue = KokoroVoice.DEFAULT)

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
    val localThinking by AssistantPreferences.localThinkingFlow(context)
        .collectAsStateWithLifecycle(initialValue = true)
    val drivingMode by AssistantPreferences.drivingModeFlow(context)
        .collectAsStateWithLifecycle(initialValue = false)
    val autoDrivingMode by AssistantPreferences.autoDrivingModeFlow(context)
        .collectAsStateWithLifecycle(initialValue = true)
    val personaTone by AssistantPreferences.personaToneFlow(context)
        .collectAsStateWithLifecycle(initialValue = PersonaTone.Warm)
    val personaHumour by AssistantPreferences.personaHumourFlow(context)
        .collectAsStateWithLifecycle(initialValue = PersonaHumour.Normal)
    val personaLength by AssistantPreferences.personaLengthFlow(context)
        .collectAsStateWithLifecycle(initialValue = PersonaLength.Normal)
    val selectedCustomVoice by AssistantPreferences.customKokoroVoiceFlow(context)
        .collectAsStateWithLifecycle(initialValue = "")
    var kokoroCustomVoices by remember { mutableStateOf(KokoroCustomVoices.list(context)) }
    var customVoiceError by remember { mutableStateOf<String?>(null) }
    val savedOwnerName by AssistantPreferences.ownerNameFlow(context)
        .collectAsStateWithLifecycle(initialValue = "")
    var ownerNameDraft by remember { mutableStateOf(savedOwnerName) }
    LaunchedEffect(savedOwnerName) { ownerNameDraft = savedOwnerName }
    val pronunciations by AssistantPreferences.pronunciationsFlow(context)
        .collectAsStateWithLifecycle(initialValue = emptyMap())
    LaunchedEffect(pronunciations) { PronunciationDictionary.userEntries = pronunciations }
    val localLlmState by LocalLlm.state.collectAsStateWithLifecycle()
    var localLlmInstalled by remember { mutableStateOf(LocalLlm.isInstalled(context)) }
    var localLlmSaved by remember { mutableLongStateOf(LocalLlm.savedBytes(context)) }
    val pickupGreeting by AssistantPreferences.pickupFlow(context)
        .collectAsStateWithLifecycle(initialValue = true)
    val followUp by AssistantPreferences.followUpFlow(context)
        .collectAsStateWithLifecycle(initialValue = true)
    val micSource by AssistantPreferences.micSourceFlow(context)
        .collectAsStateWithLifecycle(initialValue = MicSource.Recognition)
    val phononEnabled by AssistantPreferences.phononFlow(context)
        .collectAsStateWithLifecycle(initialValue = false)
    val phononState by PhononDownloader.state.collectAsStateWithLifecycle()
    val piperState by PiperVoiceDownloader.state.collectAsStateWithLifecycle()
    val localVoice by AssistantPreferences.piperVoiceFlow(context)
        .collectAsStateWithLifecycle(initialValue = null)
    var localInstalled by remember {
        mutableStateOf(PiperVoice.entries.associateWith { PiperVoiceDownloader.isInstalled(context, it) })
    }
    val localBundled = remember {
        PiperVoice.entries.associateWith { PiperVoiceDownloader.isBundled(context, it) }
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

    LaunchedEffect(localLlmState) {
        when (val snapshot = localLlmState) {
            is LocalLlm.State.Done -> {
                localLlmInstalled = true
                localLlmSaved = 0
                LocalLlm.clearResult()
            }
            is LocalLlm.State.Working -> localLlmSaved = snapshot.done
            is LocalLlm.State.Failed -> localLlmSaved = LocalLlm.savedBytes(context)
            else -> localLlmSaved = LocalLlm.savedBytes(context)
        }
    }
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

    var showAdvanced by remember { mutableStateOf(false) }
    var crashRefresh by remember { mutableIntStateOf(0) }
    val crashReport = remember(crashRefresh) { CrashLog.read(context) }

    Column(modifier = Modifier.fillMaxSize()) {
        ScreenHeader(
            title = "Secretary settings",
            subtitle = "Voice, personality and local models.",
            onBack = onBack,
            backLabel = "Settings",
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(JwTokens.screenPadding),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
        TextButton(onClick = { showAdvanced = !showAdvanced }) {
            Text(if (showAdvanced) "Hide advanced model settings" else "Advanced model settings")
        }

        SectionCard(
            title = "Phonon 31m (manual alternative)",
            subtitle = "Whole-call voice, off by default.",
            leadingIcon = Icons.Outlined.RecordVoiceOver,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Phonon 31m is Apache 2.0, about 62 MB, and streams audio while it " +
                        "speaks on the CPU. It is off by default. Turning it on switches the " +
                        "whole call to Phonon; fillers, backchannels and replies never mix engines.",
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
                    SettingSwitchRow(
                        title = "Use Phonon for the whole call",
                        description = "One engine throughout. Kokoro is the default when this is off.",
                        checked = phononEnabled,
                        onCheckedChange = { enabled ->
                            scope.launch {
                                AssistantPreferences.setPhonon(context, enabled)
                                KokoroEngine.get(context).reload()
                            }
                        },
                    )
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

        SectionCard(
            title = "Kokoro 82M (primary)",
            subtitle = "Conversational voice.",
            leadingIcon = Icons.Outlined.VolumeUp,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Kokoro int8 runs on four CPU threads and speaks the whole call, " +
                        "including fillers and backchannels, when Phonon is off. Choose one of " +
                        "the British voices or the cached cab blend.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OptionPicker(
                    options = KokoroVoice.entries,
                    selected = voice,
                    label = { it?.label ?: KokoroVoice.DEFAULT.label },
                    onSelect = { selected ->
                        scope.launch {
                            AssistantPreferences.setVoice(context, selected)
                            KokoroEngine.get(context).reload()
                        }
                    },
                )
                val pickCustomVoice = rememberLauncherForActivityResult(
                    ActivityResultContracts.GetContent(),
                ) { uri ->
                    if (uri == null) return@rememberLauncherForActivityResult
                    val name = KokoroCustomVoices.import(context, uri)
                    if (name == null) {
                        customVoiceError = "That file is not a 510 × 256 float32 Kokoro voice."
                    } else {
                        customVoiceError = null
                        kokoroCustomVoices = KokoroCustomVoices.list(context)
                        scope.launch {
                            AssistantPreferences.setCustomKokoroVoice(context, name)
                            KokoroEngine.get(context).reload()
                        }
                    }
                }
                Text("Custom voice", style = MaterialTheme.typography.labelMedium)
                OptionPicker(
                    options = listOf("") + kokoroCustomVoices.map { it.name },
                    selected = selectedCustomVoice,
                    label = { name ->
                        if (name.isNullOrBlank()) "Built-in voices" else name
                    },
                    onSelect = { name ->
                        scope.launch {
                            AssistantPreferences.setCustomKokoroVoice(context, name)
                            KokoroEngine.get(context).reload()
                        }
                    },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(onClick = { pickCustomVoice.launch("*/*") }) {
                        Text("Import .bin")
                    }
                    if (selectedCustomVoice.isNotBlank()) {
                        OutlinedButton(
                            onClick = {
                                val name = selectedCustomVoice
                                scope.launch {
                                    KokoroCustomVoices.delete(context, name)
                                    AssistantPreferences.setCustomKokoroVoice(context, null)
                                    kokoroCustomVoices = KokoroCustomVoices.list(context)
                                    KokoroEngine.get(context).reload()
                                }
                            },
                        ) { Text("Remove custom") }
                    }
                }
                Text(
                    text = "A custom 510 × 256 float32 .bin overrides the built-in voice above. " +
                        "Files can also be placed in app storage under kokoro-custom.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                customVoiceError?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                TextButton(
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
                    Text(if (previewingVoice) "Playing sample…" else "Preview Kokoro voice")
                }
            }
        }

        if (showAdvanced) {
        SectionCard(
            title = "Nemotron ASR (streaming)",
            subtitle = "Optional streaming recogniser.",
            leadingIcon = Icons.Outlined.GraphicEq,
        ) {
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

        SectionCard(
            title = "Moonshine ASR (experimental)",
            subtitle = "Default offline recogniser.",
            leadingIcon = Icons.Outlined.Mic,
        ) {
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
                    SegmentedPills(
                        options = AsrEngine.entries,
                        selected = asrEngine,
                        onSelect = { engine ->
                            scope.launch {
                                AssistantPreferences.setAsrEngine(context, engine)
                                val speechEngine = SpeechEngine.get(context)
                                if (engine != AsrEngine.Moonshine) speechEngine.releaseMoonshine()
                                if (engine != AsrEngine.Nemotron) speechEngine.releaseNemotron()
                            }
                        },
                        label = { it.label },
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

        SectionCard(
            title = "Punctuation",
            subtitle = "How spoken sentences are written.",
            leadingIcon = Icons.Outlined.Notes,
        ) {
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

        }

        SectionCard(
            title = "Call",
            subtitle = "Driving and call behaviour.",
            leadingIcon = Icons.Outlined.Call,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Calls open with the diary, then a friendly check-in, and leave the " +
                        "line open after each answer.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SettingSwitchRow(
                    title = "Driving mode",
                    description = "Shorter turns, slower speech, louder output and no follow-up nudge.",
                    checked = drivingMode,
                    onCheckedChange = { enabled ->
                        scope.launch { AssistantPreferences.setDrivingMode(context, enabled) }
                    },
                )
                SettingSwitchRow(
                    title = "Turn on automatically in the car",
                    description = "Uses the Bluetooth car connection as the trigger.",
                    checked = autoDrivingMode,
                    onCheckedChange = { enabled ->
                        scope.launch {
                            AssistantPreferences.setAutoDrivingMode(context, enabled)
                        }
                    },
                )
            }
        }

        SectionCard(
            title = "Conversation & memory",
            subtitle = "What Secretary remembers.",
            leadingIcon = Icons.Outlined.Chat,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SettingSwitchRow(
                    title = "Ask OpenAI for anything else",
                    description = "Billed per reply to your OpenAI key.",
                    checked = cloudConversation,
                    onCheckedChange = { enabled ->
                        scope.launch { AssistantMemory.setCloudConversation(context, enabled) }
                    },
                )
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

        SectionCard(
            title = "Local thinking (free)",
            subtitle = "Optional on-device answers.",
            leadingIcon = Icons.Outlined.Memory,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Qwen3 0.6B runs on this phone with no key and no cost. " +
                        "It loads only when a question needs it, stays warm for five minutes " +
                        "of conversation, then closes so the speech models get the memory " +
                        "back. 331 MB, downloaded once.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SettingSwitchRow(
                    title = "Use the local model for general questions",
                    description = "Answer general questions without sending them to the cloud.",
                    checked = localThinking,
                    onCheckedChange = { enabled ->
                        scope.launch { AssistantPreferences.setLocalThinking(context, enabled) }
                    },
                )
                val working = localLlmState as? LocalLlm.State.Working
                if (working != null) {
                    LinearProgressIndicator(
                        progress = { working.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = "Downloading ${working.done / MEGABYTE} of " +
                            "${working.total / MEGABYTE} MB",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = { LocalLlm.cancel() }) { Text("Cancel") }
                } else if (localLlmInstalled) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Installed — loads on demand",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = {
                                LocalLlm.remove(context)
                                localLlmInstalled = false
                                localLlmSaved = 0
                            },
                        ) { Text("Remove") }
                    }
                } else {
                    OutlinedButton(
                        onClick = { LocalLlm.start(context) },
                    ) {
                        Text(
                            if (localLlmSaved > 0) {
                                "Resume download (${localLlmSaved / MEGABYTE} MB saved)"
                            } else {
                                "Download local model (331 MB)"
                            },
                        )
                    }
                }
                (localLlmState as? LocalLlm.State.Failed)?.let { failure ->
                    Text(
                        text = failure.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        SectionCard(
            title = "Pronunciations",
            subtitle = "Shared by recognition and speech.",
            leadingIcon = Icons.Outlined.RecordVoiceOver,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Teach both sides a word. The recogniser fixes near misses back to the " +
                        "real spelling, and the voice uses the sounds-like form: Bierton → Beerton, " +
                        "Buckinghamshire → Buckinghamshur, Stihl → Steel. Built-in UK place, client " +
                        "and trade terms are included; your entries win.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                var word by remember { mutableStateOf("") }
                var soundsLike by remember { mutableStateOf("") }
                OutlinedTextField(
                    value = word,
                    onValueChange = { word = it },
                    label = { Text("Word") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = soundsLike,
                    onValueChange = { soundsLike = it },
                    label = { Text("Sounds like") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    enabled = word.isNotBlank() && soundsLike.isNotBlank(),
                    onClick = {
                        scope.launch {
                            AssistantPreferences.addPronunciation(context, word, soundsLike)
                            word = ""
                            soundsLike = ""
                        }
                    },
                ) { Text("Add pronunciation") }
                pronunciations.forEach { (key, value) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "$key → $value",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = {
                                scope.launch {
                                    AssistantPreferences.removePronunciation(context, key)
                                }
                            },
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Delete,
                                contentDescription = "Remove $key",
                            )
                        }
                    }
                }
            }
        }

        SectionCard(
            title = "Personality",
            subtitle = "Tone, humour and length.",
            leadingIcon = Icons.Outlined.Chat,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "How it sounds once the words are chosen: warm or brisk, straight " +
                        "or dry, short or normal. The voice itself is set above.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = ownerNameDraft,
                    onValueChange = { value ->
                        ownerNameDraft = value
                        scope.launch { AssistantPreferences.setOwnerName(context, value) }
                    },
                    label = { Text("Your first name") },
                    placeholder = { Text("Josh") },
                    supportingText = {
                        Text("Used in the pickup line and the occasional aside. Blank uses your sign-in name.")
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Tone", style = MaterialTheme.typography.labelMedium)
                SegmentedPills(
                    options = PersonaTone.entries,
                    selected = personaTone,
                    onSelect = { tone ->
                        scope.launch { AssistantPreferences.setPersonaTone(context, tone) }
                    },
                    label = { it.label },
                )
                Text("Humour", style = MaterialTheme.typography.labelMedium)
                SegmentedPills(
                    options = PersonaHumour.entries,
                    selected = personaHumour,
                    onSelect = { humour ->
                        scope.launch { AssistantPreferences.setPersonaHumour(context, humour) }
                    },
                    label = { it.label },
                )
                Text("Length", style = MaterialTheme.typography.labelMedium)
                SegmentedPills(
                    options = PersonaLength.entries,
                    selected = personaLength,
                    onSelect = { length ->
                        scope.launch { AssistantPreferences.setPersonaLength(context, length) }
                    },
                    label = { it.label },
                )
            }
        }

        SectionCard(
            title = "Microphone",
            subtitle = "Audio source for recognition.",
            leadingIcon = Icons.Outlined.Mic,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SegmentedPills(
                    options = MicSource.entries,
                    selected = micSource,
                    onSelect = { selected ->
                        scope.launch { AssistantPreferences.setMicSource(context, selected) }
                    },
                    label = { it.label },
                )
                Text(
                    text = "Best recognition is the reliable default. Barge-in uses " +
                        "VOICE_COMMUNICATION, which applies echo cancellation but is silent " +
                        "on this Samsung — choose it only if you need to talk over the voice. " +
                        "On a Bluetooth car kit the car's hands-free mic is used; if it " +
                        "struggles, try Raw microphone with the phone nearer. Applies on the " +
                        "next call.",
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

        if (showAdvanced) {
        SectionCard(
            title = "Diagnostics",
            subtitle = "Crash and model status.",
            leadingIcon = Icons.Outlined.BugReport,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = if (crashReport.isNullOrBlank()) {
                        "No crash recorded. If Secretary disappears unexpectedly, reopen the app and share this report; it includes native crashes and low-memory kills."
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
                                "Secretary diagnostics",
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

        }
        Spacer(Modifier.height(8.dp))
        }
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
