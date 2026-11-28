package uk.co.jwgardenservices.crm.ui.voice

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Notes
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import uk.co.jwgardenservices.crm.core.CrashLog
import uk.co.jwgardenservices.crm.ui.common.OptionPicker
import uk.co.jwgardenservices.crm.ui.common.SectionCard
import uk.co.jwgardenservices.crm.ui.common.SettingSwitchRow
import uk.co.jwgardenservices.crm.ui.common.ShareText
import uk.co.jwgardenservices.crm.ui.theme.JwTokens
import uk.co.jwgardenservices.crm.voice.AssistantMemory
import uk.co.jwgardenservices.crm.voice.AsrEngine
import uk.co.jwgardenservices.crm.voice.AssistantPreferences
import uk.co.jwgardenservices.crm.voice.CloudVoice
import uk.co.jwgardenservices.crm.voice.KokoroCustomVoices
import uk.co.jwgardenservices.crm.voice.KokoroHqDownloader
import uk.co.jwgardenservices.crm.voice.KokoroEngine
import uk.co.jwgardenservices.crm.voice.KokoroVoice
import uk.co.jwgardenservices.crm.voice.CallAudio
import uk.co.jwgardenservices.crm.voice.FinalPass
import uk.co.jwgardenservices.crm.voice.LlmModel
import uk.co.jwgardenservices.crm.voice.LocalLlm
import uk.co.jwgardenservices.crm.voice.MicSource
import uk.co.jwgardenservices.crm.voice.MicTest
import uk.co.jwgardenservices.crm.voice.ParakeetDownloader
import uk.co.jwgardenservices.crm.voice.NemotronDownloader
import uk.co.jwgardenservices.crm.voice.OpenAiVoice
import uk.co.jwgardenservices.crm.voice.PhononDownloader
import uk.co.jwgardenservices.crm.voice.PhononModel
import uk.co.jwgardenservices.crm.voice.PunctuationDownloader
import uk.co.jwgardenservices.crm.voice.PhononVoice
import uk.co.jwgardenservices.crm.voice.PiperVoice
import uk.co.jwgardenservices.crm.voice.PiperVoiceDownloader
import uk.co.jwgardenservices.crm.voice.PersonaHumour
import uk.co.jwgardenservices.crm.voice.PersonaLength
import uk.co.jwgardenservices.crm.voice.PersonaTone
import uk.co.jwgardenservices.crm.voice.PronunciationDictionary
import uk.co.jwgardenservices.crm.voice.SpeechDenoiser
import uk.co.jwgardenservices.crm.voice.WhisperCppDownloader
import uk.co.jwgardenservices.crm.voice.WhisperDownloader
import uk.co.jwgardenservices.crm.voice.SpeechEngine
import uk.co.jwgardenservices.crm.ui.common.CompactScreenHeader
import uk.co.jwgardenservices.crm.ui.common.SegmentedPillTabs

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
    var previewingOnline by remember { mutableStateOf(false) }
    var previewingOnlineVoice by remember { mutableStateOf<OpenAiVoice?>(null) }
    var onlinePreviewError by remember { mutableStateOf<String?>(null) }
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
    val openAiVoice by AssistantPreferences.openAiVoiceFlow(context)
        .collectAsStateWithLifecycle(initialValue = OpenAiVoice.DEFAULT)
    val localThinking by AssistantPreferences.localThinkingFlow(context)
        .collectAsStateWithLifecycle(initialValue = true)
    val drivingMode by AssistantPreferences.drivingModeFlow(context)
        .collectAsStateWithLifecycle(initialValue = false)
    val denoise by AssistantPreferences.denoiseFlow(context)
        .collectAsStateWithLifecycle(initialValue = false)
    val denoiseState by SpeechDenoiser.state.collectAsStateWithLifecycle()
    var denoiseInstalled by remember { mutableStateOf(SpeechDenoiser.isInstalled(context)) }
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
    val steadyVoice by AssistantPreferences.steadyVoiceFlow(context)
        .collectAsStateWithLifecycle(initialValue = true)
    val confirmChanges by AssistantPreferences.confirmChangesFlow(context)
        .collectAsStateWithLifecycle(initialValue = true)
    val pickupGreeting by AssistantPreferences.pickupFlow(context)
        .collectAsStateWithLifecycle(initialValue = true)
    val followUp by AssistantPreferences.followUpFlow(context)
        .collectAsStateWithLifecycle(initialValue = true)
    val micSource by AssistantPreferences.micSourceFlow(context)
        .collectAsStateWithLifecycle(initialValue = MicSource.Recognition)
    val preferPhoneMic by AssistantPreferences.preferPhoneMicFlow(context)
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
        .collectAsStateWithLifecycle(initialValue = AsrEngine.Nemotron)

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
    LaunchedEffect(denoiseState) {
        if (denoiseState is SpeechDenoiser.State.Done) {
            denoiseInstalled = true
            AssistantPreferences.setDenoise(context, true)
            SpeechDenoiser.clearResult()
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

    Column(modifier = Modifier.fillMaxSize()) {
        CompactScreenHeader(
            title = "Secretary settings",
            subtitle = "Voice, listening and the assistant's brain.",
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

        SettingsGroupHeader("Voice", "How the assistant sounds.")
        SectionCard(
            title = "Phonon 31m (call voice)",
            subtitle = "The only in-call TTS engine.",
            leadingIcon = Icons.Outlined.RecordVoiceOver,
        ) {
            Column(
                modifier = Modifier.padding(JwTokens.cardPadding),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Phonon 31m is Apache 2.0, about 62 MB, and streams audio while it " +
                        "speaks on the CPU. It is the only in-call voice: fillers, backchannels, " +
                        "pickups and replies all use the selected Phonon voice.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SettingSwitchRow(
                    title = "Steady voice",
                    description = "Keeps it sounding like one speaker from sentence to sentence. " +
                        "Switch off if it sounds flat.",
                    checked = steadyVoice,
                    onCheckedChange = { enabled ->
                        scope.launch { AssistantPreferences.setSteadyVoice(context, enabled) }
                    },
                )

                val working = (phononState as? PhononDownloader.State.Working)
                    ?.takeIf { PhononDownloader.activeModel == PhononModel.Phonon31 }
                val failure = (phononState as? PhononDownloader.State.Failed)
                    ?.takeIf { PhononDownloader.activeModel == PhononModel.Phonon31 }
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
                                // Every installed checkpoint keeps its own copy of the voice.
                                val ok = PhononModel.entries
                                    .filter { PhononDownloader.isInstalled(context, it) }
                                    .map { PhononDownloader.installVoice(context, selected, it) }
                                    .all { it }
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
                        enabled = true,
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
                        enabled = !voiceDownloading,
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
                            "ships its own default. Pick a voice and preview it here.",
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


        if (phononInstalled) Phonon40Card()


        SectionCard(
            title = "Pronunciations",
            subtitle = "Shared by recognition and speech.",
            leadingIcon = Icons.Outlined.RecordVoiceOver,
        ) {
            Column(
                modifier = Modifier.padding(JwTokens.cardPadding),
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
            title = "Online voice (OpenAI)",
            subtitle = "Cloud voice with offline fallback.",
            leadingIcon = Icons.Outlined.Cloud,
        ) {
            Column(
                modifier = Modifier.padding(JwTokens.cardPadding),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SettingSwitchRow(
                    title = "Use OpenAI online",
                    description = "Answers and replies try OpenAI first. Network, key or " +
                        "service failures fall back to the selected offline voice.",
                    checked = onlineVoice,
                    onCheckedChange = { enabled ->
                        scope.launch {
                            AssistantPreferences.setOnlineVoice(context, enabled)
                            AssistantMemory.setCloudConversation(context, enabled)
                        }
                    },
                )
                Text("OpenAI voice", style = MaterialTheme.typography.labelMedium)
                Text(
                    text = "OpenAI recommends Marin or Cedar for the best quality.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OptionPicker(
                    options = OpenAiVoice.entries,
                    selected = openAiVoice,
                    label = { it?.label ?: OpenAiVoice.DEFAULT.label },
                    enabled = onlineVoice,
                    onSelect = { selected ->
                        scope.launch { AssistantPreferences.setOpenAiVoice(context, selected) }
                    },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        enabled = !previewingOnline,
                        onClick = {
                            onlinePreviewError = null
                            scope.launch {
                                previewingOnline = true
                                previewingOnlineVoice = openAiVoice
                                val voice = CloudVoice(context)
                                try {
                                    val played = voice.speak(PREVIEW_TEXT, openAiVoice.id)
                                    if (!played) {
                                        onlinePreviewError =
                                            "Could not play that OpenAI voice. Check the online switch, " +
                                                "connection and OPENAI_API_KEY."
                                    }
                                } catch (error: Exception) {
                                    onlinePreviewError =
                                        error.message ?: "Could not play that OpenAI voice."
                                } finally {
                                    voice.close()
                                    previewingOnline = false
                                    previewingOnlineVoice = null
                                }
                            }
                        },
                    ) {
                        Text(if (previewingOnline) "Playing…" else "Preview selected")
                    }
                    OutlinedButton(
                        enabled = !previewingOnline,
                        onClick = {
                            onlinePreviewError = null
                            scope.launch {
                                previewingOnline = true
                                try {
                                    for (voiceOption in OpenAiVoice.entries) {
                                        previewingOnlineVoice = voiceOption
                                        val voice = CloudVoice(context)
                                        val played = try {
                                            voice.speak(PREVIEW_TEXT, voiceOption.id)
                                        } finally {
                                            voice.close()
                                        }
                                        if (!played) {
                                            onlinePreviewError =
                                                "Stopped at ${voiceOption.label}. Check the online switch, " +
                                                    "connection and OPENAI_API_KEY."
                                            break
                                        }
                                    }
                                } catch (error: Exception) {
                                    onlinePreviewError =
                                        error.message ?: "Could not play the OpenAI voices."
                                } finally {
                                    previewingOnline = false
                                    previewingOnlineVoice = null
                                }
                            }
                        },
                    ) {
                        Text(if (previewingOnline) "Playing…" else "Preview all voices")
                    }
                }
                if (previewingOnline && previewingOnlineVoice != null) {
                    Text(
                        text = "Playing ${previewingOnlineVoice!!.label}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                onlinePreviewError?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Text(
                    text = "The OpenAI key stays in Supabase; only the reply text is sent. " +
                        "Offline fillers remain on-device so the line never waits for the network.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }


        SettingsGroupHeader("Listening", "How the assistant hears you.")
        SectionCard(
            title = "Speech engine",
            subtitle = "What listens on a call.",
            leadingIcon = Icons.Outlined.Mic,
        ) {
            Column(
                modifier = Modifier.padding(JwTokens.cardPadding),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SegmentedPillTabs(
                    options = AsrEngine.entries,
                    selected = asrEngine,
                    onSelect = { engine ->
                        scope.launch {
                            AssistantPreferences.setAsrEngine(context, engine)
                            if (engine != AsrEngine.Nemotron) SpeechEngine.get(context).releaseNemotron()
                        }
                    },
                    label = { it.label },
                )
                Text(
                    text = "Takes effect on the next call. Nemotron streams live words. Offline " +
                        "waits until you stop and then uses the Parakeet or Whisper you choose " +
                        "below. If Nemotron fails, the call carries on with that choice.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }


        SectionCard(
            title = "Nemotron ASR (streaming)",
            subtitle = "Optional streaming recogniser.",
            leadingIcon = Icons.Outlined.GraphicEq,
        ) {
            Column(
                modifier = Modifier.padding(JwTokens.cardPadding),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "NVIDIA Nemotron 3.5 Speech Streaming 0.6B, English (en) only, " +
                        "NVIDIA Open Model License (commercial use permitted). About 662 MB, " +
                        "April 2026 560 ms export. True streaming: live partial words. " +
                        "While Nemotron streams the partials, the finished sentence is " +
                        "re-decoded by the offline recogniser you pick below, so a quiet word " +
                        "missed by NeMo still comes through in the final transcript. If a " +
                        "call produces no words at all the saved engine also switches to " +
                        "it. While selected it holds about 650 MB in memory.",
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
                                    AssistantPreferences.setAsrEngine(context, AsrEngine.Offline)
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


        SettingsNote("Second look: after you stop talking, one of these hears the whole sentence again. Use at most one.")

        ParakeetCard()

        WhisperPassCard()

        WhisperCppCard()

        SectionCard(
            title = "Microphone",
            subtitle = "Audio source for recognition.",
            leadingIcon = Icons.Outlined.Mic,
        ) {
            Column(
                modifier = Modifier.padding(JwTokens.cardPadding),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SegmentedPillTabs(
                    options = MicSource.entries,
                    selected = micSource,
                    onSelect = { selected ->
                        scope.launch { AssistantPreferences.setMicSource(context, selected) }
                    },
                    label = { it.label },
                )
                SettingSwitchRow(
                    title = "Prefer phone mic over Bluetooth mic",
                    description = "Keeps replies on HD Bluetooth media audio (A2DP) and " +
                        "records from the phone's built-in 16 kHz mic instead of opening a " +
                        "narrowband SCO link. Turn off to use the headset's hands-free mic.",
                    checked = preferPhoneMic,
                    onCheckedChange = { enabled ->
                        scope.launch { AssistantPreferences.setPreferPhoneMic(context, enabled) }
                    },
                )
                Text(
                    text = "Best recognition is the reliable default. Barge-in uses " +
                        "VOICE_COMMUNICATION, which applies echo cancellation but is silent " +
                        "on this Samsung — choose it only if you need to talk over the voice. " +
                        "With the phone-mic preference on, the mic source above is bypassed " +
                        "for the built-in mic on a Bluetooth call. Applies on the next call.",
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


        SectionCard(
            title = "Noise suppression",
            subtitle = "Engine and road noise.",
            leadingIcon = Icons.Outlined.Mic,
        ) {
            Column(
                modifier = Modifier.padding(JwTokens.cardPadding),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SettingSwitchRow(
                    title = "Clean up the microphone",
                    description = "A tiny on-device model (0.5 MB) removes engine, road and wind " +
                        "noise before the words are recognised. Try it in the van and switch " +
                        "it off if names get worse.",
                    checked = denoise && denoiseInstalled,
                    onCheckedChange = { enabled ->
                        when {
                            !enabled -> scope.launch { AssistantPreferences.setDenoise(context, false) }
                            denoiseInstalled -> scope.launch { AssistantPreferences.setDenoise(context, true) }
                            else -> SpeechDenoiser.start(context)
                        }
                    },
                )
                when (val state = denoiseState) {
                    is SpeechDenoiser.State.Working -> Text(
                        text = "Downloading the noise model…",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    is SpeechDenoiser.State.Failed -> Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    else -> Unit
                }
            }
        }


        SectionCard(
            title = "Punctuation",
            subtitle = "How spoken sentences are written.",
            leadingIcon = Icons.Outlined.Notes,
        ) {
            Column(
                modifier = Modifier.padding(JwTokens.cardPadding),
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

        SettingsGroupHeader("Assistant brain", "How it thinks and what it remembers.")
        LocalModelsCard()

        SectionCard(
            title = "Conversation & memory",
            subtitle = "What Secretary remembers.",
            leadingIcon = Icons.Outlined.Chat,
        ) {
            Column(
                modifier = Modifier.padding(JwTokens.cardPadding),
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
            title = "Personality",
            subtitle = "Tone, humour and length.",
            leadingIcon = Icons.Outlined.Chat,
        ) {
            Column(
                modifier = Modifier.padding(JwTokens.cardPadding),
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
                SegmentedPillTabs(
                    options = PersonaTone.entries,
                    selected = personaTone,
                    onSelect = { tone ->
                        scope.launch { AssistantPreferences.setPersonaTone(context, tone) }
                    },
                    label = { it.label },
                )
                Text("Humour", style = MaterialTheme.typography.labelMedium)
                SegmentedPillTabs(
                    options = PersonaHumour.entries,
                    selected = personaHumour,
                    onSelect = { humour ->
                        scope.launch { AssistantPreferences.setPersonaHumour(context, humour) }
                    },
                    label = { it.label },
                )
                Text("Length", style = MaterialTheme.typography.labelMedium)
                SegmentedPillTabs(
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
            title = "Call",
            subtitle = "Driving and call behaviour.",
            leadingIcon = Icons.Outlined.Call,
        ) {
            Column(
                modifier = Modifier.padding(JwTokens.cardPadding),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Calls open with the diary, then a friendly check-in, and leave the " +
                        "line open after each answer.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SettingSwitchRow(
                    title = "Confirm before saving changes",
                    description = "Reads back exactly what it will do, with the date and the money, " +
                        "and only saves when you say yes. Switch off to save straight away " +
                        "(you can still say \"undo that\").",
                    checked = confirmChanges,
                    onCheckedChange = { enabled ->
                        scope.launch { AssistantPreferences.setConfirmChanges(context, enabled) }
                    },
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


        SettingsGroupHeader("Testing and tuning", "Diagnostics, and audio for tuning.")
        AudioCaptureCard()


        SectionCard(
            title = "Diagnostics",
            subtitle = "Crash and model status.",
            leadingIcon = Icons.Outlined.BugReport,
        ) {
            Column(
                modifier = Modifier.padding(JwTokens.cardPadding),
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


        Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun Phonon40Card() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val model = PhononModel.Phonon40
    val chosen by AssistantPreferences.phononModelFlow(context)
        .collectAsStateWithLifecycle(initialValue = PhononModel.Phonon31)
    val state by PhononDownloader.state.collectAsStateWithLifecycle()
    var installed by remember { mutableStateOf(PhononDownloader.isInstalled(context, model)) }
    var saved by remember { mutableLongStateOf(PhononDownloader.savedBytes(context, model)) }
    var switching by remember { mutableStateOf(false) }
    val savedToken by AssistantPreferences.hfTokenFlow(context).collectAsStateWithLifecycle(initialValue = "")
    var tokenDraft by remember { mutableStateOf("") }
    LaunchedEffect(savedToken) { if (tokenDraft.isEmpty()) tokenDraft = savedToken }
    LaunchedEffect(state) {
        when (state) {
            is PhononDownloader.State.Done -> {
                installed = PhononDownloader.isInstalled(context, model)
                saved = 0
                PhononDownloader.clearResult()
            }
            is PhononDownloader.State.Working -> Unit
            else -> saved = PhononDownloader.savedBytes(context, model)
        }
    }
    SectionCard(
        title = "Phonon 40m voice (optional)",
        subtitle = "Larger model. Non-commercial licence.",
        leadingIcon = Icons.Outlined.GraphicEq,
    ) {
        Column(
            modifier = Modifier.padding(JwTokens.cardPadding),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Licence: ${model.licence} The 40m checkpoint is released under CC BY-NC 4.0, " +
                    "which does not allow commercial use. This app runs a business, so decide " +
                    "whether that applies to you before downloading it. Nothing is downloaded " +
                    "unless you tap the button. About ${model.totalBytes / MEGABYTE} MB; the " +
                    "standard 31m stays installed as the fallback.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SettingSwitchRow(
                title = "Use Phonon 40m",
                description = "Switch off to go back to 31m. Takes effect on the next reply.",
                checked = chosen == model && installed,
                enabled = installed && !switching,
                onCheckedChange = { on ->
                    scope.launch {
                        switching = true
                        if (on) {
                            // Bring the chosen and any imported voices across, so the
                            // voice does not change when the model does.
                            val voice = AssistantPreferences.phononVoiceFlow(context).first()
                            PhononVoiceLookup.find(voice)?.let {
                                PhononDownloader.installVoice(context, it, model)
                            }
                            PhononDownloader.copyVoices(context, PhononModel.Phonon31, model)
                            AssistantPreferences.setPhononModel(context, model)
                        } else {
                            AssistantPreferences.setPhononModel(context, PhononModel.Phonon31)
                        }
                        KokoroEngine.get(context).reload()
                        switching = false
                    }
                },
            )
            val working = (state as? PhononDownloader.State.Working)
                ?.takeIf { PhononDownloader.activeModel == model }
            if (!installed && working == null) {
                Text(
                    text = "Hugging Face keeps this model behind a free login. Three steps: " +
                        "1) open the model page and accept the licence; 2) create a Read token at " +
                        "huggingface.co/settings/tokens; 3) paste it here. The token stays on this phone " +
                        "and is only sent to Hugging Face.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(
                    onClick = {
                        context.startActivity(
                            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(model.pageUrl))
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    },
                ) { Text("Open the model page") }
                OutlinedTextField(
                    value = tokenDraft,
                    onValueChange = { tokenDraft = it.trim() },
                    label = { Text("Hugging Face read token") },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (working != null) {
                LinearProgressIndicator(progress = { working.progress }, modifier = Modifier.fillMaxWidth())
                Text(
                    text = "Downloading ${working.done / MEGABYTE} of ${working.total / MEGABYTE} MB",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { PhononDownloader.cancel() }) { Text("Cancel") }
            } else if (installed) {
                TextButton(
                    onClick = {
                        scope.launch {
                            AssistantPreferences.setPhononModel(context, PhononModel.Phonon31)
                            PhononDownloader.remove(context, model)
                            installed = false
                            saved = 0
                            KokoroEngine.get(context).reload()
                        }
                    },
                ) { Text("Remove Phonon 40m") }
            } else {
                OutlinedButton(
                    enabled = tokenDraft.isNotBlank(),
                    onClick = {
                        scope.launch { AssistantPreferences.setHfToken(context, tokenDraft) }
                        PhononDownloader.start(context, model, tokenDraft)
                    },
                ) {
                    Text(
                        if (saved > 0) {
                            "Resume download (${saved / MEGABYTE} MB saved)"
                        } else {
                            "Download Phonon 40m (${model.totalBytes / MEGABYTE} MB)"
                        },
                    )
                }
            }
            (state as? PhononDownloader.State.Failed)
                ?.takeIf { PhononDownloader.activeModel == model }
                ?.let { failure ->
                Text(
                    text = failure.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

private object PhononVoiceLookup {
    fun find(name: String): uk.co.jwgardenservices.crm.voice.PhononVoice? =
        uk.co.jwgardenservices.crm.voice.PhononVoice.entries.firstOrNull { it.name == name }
}

@Composable
private fun ParakeetCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pass by AssistantPreferences.finalPassFlow(context)
        .collectAsStateWithLifecycle(initialValue = FinalPass.Off)
    val state by ParakeetDownloader.state.collectAsStateWithLifecycle()
    var installed by remember { mutableStateOf(ParakeetDownloader.isInstalled(context)) }
    var saved by remember { mutableLongStateOf(ParakeetDownloader.savedBytes(context)) }
    LaunchedEffect(state) {
        when (state) {
            is ParakeetDownloader.State.Done -> {
                installed = true
                saved = 0
                ParakeetDownloader.clearResult()
            }
            is ParakeetDownloader.State.Working -> Unit
            else -> saved = ParakeetDownloader.savedBytes(context)
        }
    }
    SectionCard(
        title = "Second look: Parakeet (experimental)",
        subtitle = "NVIDIA's accurate English recogniser.",
        leadingIcon = Icons.Outlined.GraphicEq,
    ) {
        Column(
            modifier = Modifier.padding(JwTokens.cardPadding),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Every sentence is heard twice: live, and again as a whole once you stop. " +
                    "NVIDIA Parakeet TDT 0.6B v2 has some of the lowest word error rates of any " +
                    "open English model and writes its own punctuation. It is the heaviest " +
                    "option: about 660 MB on disk and over a gigabyte of spare memory to run, " +
                    "next to Nemotron and the local models. If memory is short it quietly " +
                    "skips the second look. Each sentence's time is in the assistant log.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SettingSwitchRow(
                title = "Use Parakeet for the second look",
                description = "Switch off to turn the second look off.",
                checked = pass == FinalPass.Parakeet && installed,
                enabled = installed,
                onCheckedChange = { on ->
                    scope.launch {
                        AssistantPreferences.setFinalPass(context, if (on) FinalPass.Parakeet else FinalPass.Off)
                    }
                },
            )
            val working = state as? ParakeetDownloader.State.Working
            if (working != null) {
                LinearProgressIndicator(progress = { working.progress }, modifier = Modifier.fillMaxWidth())
                Text(
                    text = "Downloading ${working.done / MEGABYTE} of ${working.total / MEGABYTE} MB",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { ParakeetDownloader.cancel() }) { Text("Cancel") }
            } else if (installed) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Installed", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = {
                            scope.launch {
                                AssistantPreferences.setFinalPass(context, FinalPass.Off)
                                ParakeetDownloader.remove(context)
                                installed = false
                                saved = 0
                            }
                        },
                    ) { Text("Remove") }
                }
            } else {
                OutlinedButton(onClick = { ParakeetDownloader.start(context) }) {
                    Text(
                        if (saved > 0) {
                            "Resume download (${saved / MEGABYTE} MB saved)"
                        } else {
                            "Download Parakeet (${ParakeetDownloader.TOTAL_BYTES / MEGABYTE} MB)"
                        },
                    )
                }
            }
            (state as? ParakeetDownloader.State.Failed)?.let { failure ->
                Text(
                    text = failure.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun WhisperPassCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pass by AssistantPreferences.finalPassFlow(context)
        .collectAsStateWithLifecycle(initialValue = FinalPass.Off)
    val state by WhisperDownloader.state.collectAsStateWithLifecycle()
    var installed by remember { mutableStateOf(WhisperDownloader.isInstalled(context)) }
    var saved by remember { mutableLongStateOf(WhisperDownloader.savedBytes(context)) }
    LaunchedEffect(state) {
        when (state) {
            is WhisperDownloader.State.Done -> {
                installed = true
                saved = 0
                WhisperDownloader.clearResult()
            }
            is WhisperDownloader.State.Working -> Unit
            else -> saved = WhisperDownloader.savedBytes(context)
        }
    }
    SectionCard(
        title = "Second look: Whisper (experimental)",
        subtitle = "Built for noisy speech.",
        leadingIcon = Icons.Outlined.GraphicEq,
    ) {
        Column(
            modifier = Modifier.padding(JwTokens.cardPadding),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Every sentence is heard twice: live, and again as a whole once you stop. " +
                    "Whisper was trained on a lot of " +
                    "noisy speech, so it may follow you better in the van, but it is slower " +
                    "(a second or more a sentence) and needs about 375 MB and spare memory. If " +
                    "memory is short it quietly skips the second look. Each sentence's time is in the " +
                    "assistant log.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SettingSwitchRow(
                title = "Use Whisper for the second look",
                description = "Switch off to turn the second look off.",
                checked = pass == FinalPass.Whisper && installed,
                enabled = installed,
                onCheckedChange = { on ->
                    scope.launch {
                        AssistantPreferences.setFinalPass(context, if (on) FinalPass.Whisper else FinalPass.Off)
                    }
                },
            )
            val working = state as? WhisperDownloader.State.Working
            if (working != null) {
                LinearProgressIndicator(progress = { working.progress }, modifier = Modifier.fillMaxWidth())
                Text(
                    text = "Downloading ${working.done / MEGABYTE} of ${working.total / MEGABYTE} MB",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { WhisperDownloader.cancel() }) { Text("Cancel") }
            } else if (installed) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Installed", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = {
                            scope.launch {
                                AssistantPreferences.setFinalPass(context, FinalPass.Off)
                                WhisperDownloader.remove(context)
                                installed = false
                                saved = 0
                            }
                        },
                    ) { Text("Remove") }
                }
            } else {
                OutlinedButton(onClick = { WhisperDownloader.start(context) }) {
                    Text(
                        if (saved > 0) {
                            "Resume download (${saved / MEGABYTE} MB saved)"
                        } else {
                            "Download Whisper small.en (${WhisperDownloader.TOTAL_BYTES / MEGABYTE} MB)"
                        },
                    )
                }
            }
            (state as? WhisperDownloader.State.Failed)?.let { failure ->
                Text(
                    text = failure.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun WhisperCppCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pass by AssistantPreferences.finalPassFlow(context)
        .collectAsStateWithLifecycle(initialValue = FinalPass.Off)
    val state by WhisperCppDownloader.state.collectAsStateWithLifecycle()
    var installed by remember { mutableStateOf(WhisperCppDownloader.isInstalled(context)) }
    var saved by remember { mutableLongStateOf(WhisperCppDownloader.savedBytes(context)) }
    LaunchedEffect(state) {
        when (state) {
            is WhisperCppDownloader.State.Done -> {
                installed = true
                saved = 0
                WhisperCppDownloader.clearResult()
            }
            is WhisperCppDownloader.State.Working -> Unit
            else -> saved = WhisperCppDownloader.savedBytes(context)
        }
    }
    SectionCard(
        title = "Second look: whisper.cpp (experimental)",
        subtitle = "Whisper with a shorter window, plus voice detection.",
        leadingIcon = Icons.Outlined.GraphicEq,
    ) {
        Column(
            modifier = Modifier.padding(JwTokens.cardPadding),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "The same Whisper small.en model, run by whisper.cpp instead. It only looks at " +
                    "as much audio as the sentence needs rather than a fixed 30 seconds, and a " +
                    "voice detector skips silence so a pause is not written up as words. It needs " +
                    "about 190 MB and spare memory; if memory is short it quietly skips the second look. " +
                    "Each sentence's time is in the assistant log, so you can compare.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SettingSwitchRow(
                title = "Use whisper.cpp for the second look",
                description = "Switch off to turn the second look off.",
                checked = pass == FinalPass.WhisperCpp && installed,
                enabled = installed,
                onCheckedChange = { on ->
                    scope.launch {
                        AssistantPreferences.setFinalPass(context, if (on) FinalPass.WhisperCpp else FinalPass.Off)
                    }
                },
            )
            val working = state as? WhisperCppDownloader.State.Working
            if (working != null) {
                LinearProgressIndicator(progress = { working.progress }, modifier = Modifier.fillMaxWidth())
                Text(
                    text = "Downloading ${working.done / MEGABYTE} of ${working.total / MEGABYTE} MB",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { WhisperCppDownloader.cancel() }) { Text("Cancel") }
            } else if (installed) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Installed", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(
                        onClick = {
                            scope.launch {
                                AssistantPreferences.setFinalPass(context, FinalPass.Off)
                                WhisperCppDownloader.remove(context)
                                installed = false
                                saved = 0
                            }
                        },
                    ) { Text("Remove") }
                }
            } else {
                OutlinedButton(onClick = { WhisperCppDownloader.start(context) }) {
                    Text(
                        if (saved > 0) {
                            "Resume download (${saved / MEGABYTE} MB saved)"
                        } else {
                            "Download whisper.cpp small.en (${WhisperCppDownloader.TOTAL_BYTES / MEGABYTE} MB)"
                        },
                    )
                }
            }
            (state as? WhisperCppDownloader.State.Failed)?.let { failure ->
                Text(
                    text = failure.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun AudioCaptureCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val save by AssistantPreferences.saveAudioFlow(context).collectAsStateWithLifecycle(initialValue = false)
    var count by remember { mutableIntStateOf(CallAudio.count(context)) }
    LaunchedEffect(save) { count = CallAudio.count(context) }
    SectionCard(
        title = "Save call audio (for tuning)",
        subtitle = "Stays on this phone.",
        leadingIcon = Icons.Outlined.Mic,
    ) {
        Column(
            modifier = Modifier.padding(JwTokens.cardPadding),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Keeps a copy of each sentence the assistant hears, with what each recogniser " +
                    "made of it, so the recognisers can be compared on your own voice in the van. " +
                    "It stays on this phone (the newest 60 sentences) and nothing is sent anywhere " +
                    "unless you tap Share. Switch it off when you are done.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SettingSwitchRow(
                title = "Save call audio",
                description = "Takes effect from the next call.",
                checked = save,
                onCheckedChange = { on -> scope.launch { AssistantPreferences.setSaveAudio(context, on) } },
            )
            Text(
                text = "Saved: $count ${if (count == 1) "sentence" else "sentences"}",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    enabled = count > 0,
                    onClick = {
                        scope.launch {
                            val zip = withContext(kotlinx.coroutines.Dispatchers.IO) { CallAudio.zipForShare(context) }
                            if (zip != null) {
                                val uri = androidx.core.content.FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    zip,
                                )
                                val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                    type = "application/zip"
                                    putExtra(android.content.Intent.EXTRA_STREAM, uri)
                                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(
                                    android.content.Intent.createChooser(send, "Share call audio")
                                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        }
                    },
                ) { Text("Share saved audio") }
                TextButton(
                    enabled = count > 0,
                    onClick = {
                        CallAudio.clear(context)
                        count = 0
                    },
                ) { Text("Delete") }
            }
        }
    }
}

@Composable
private fun SettingsGroupHeader(title: String, subtitle: String) {
    Column(modifier = Modifier.padding(top = 8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingsNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The two on-device models, each with one fixed job, and the switch for using them at all. */
@Composable
private fun LocalModelsCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val localThinking by AssistantPreferences.localThinkingFlow(context)
        .collectAsStateWithLifecycle(initialValue = true)
    SectionCard(
        title = "On-device assistant",
        subtitle = "Answers with no internet.",
        leadingIcon = Icons.Outlined.Memory,
    ) {
        Column(
            modifier = Modifier.padding(JwTokens.cardPadding),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SettingSwitchRow(
                title = "Use the on-device assistant",
                description = "Understands loose phrasing and answers questions without sending them anywhere.",
                checked = localThinking,
                onCheckedChange = { enabled ->
                    scope.launch { AssistantPreferences.setLocalThinking(context, enabled) }
                },
            )
            LocalModelRow(
                model = LlmModel.Small,
                blurb = "Fast. Understands what you say and answers on a phone call. " +
                    "Loads when needed and closes after a few idle minutes.",
            )
            LocalModelRow(
                model = LlmModel.Large,
                blurb = "Clever. Used in typed chat, where it reads a summary of your diary, " +
                    "money and clients to answer. Slower, so it is never used on a call. " +
                    "Needs about 2.4 GB of free memory; without it the fast model answers.",
            )
        }
    }
}

@Composable
private fun LocalModelRow(model: LlmModel, blurb: String) {
    val context = LocalContext.current
    val state by LocalLlm.stateFor(model).collectAsStateWithLifecycle()
    var installed by remember { mutableStateOf(LocalLlm.isInstalled(context, model)) }
    var saved by remember { mutableLongStateOf(LocalLlm.savedBytes(context, model)) }
    val megabytes = model.bytes / MEGABYTE
    LaunchedEffect(state) {
        when (val snapshot = state) {
            is LocalLlm.State.Done -> {
                installed = true
                saved = 0
                LocalLlm.clearResult(model)
            }
            is LocalLlm.State.Working -> saved = snapshot.done
            else -> saved = LocalLlm.savedBytes(context, model)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(model.label, style = MaterialTheme.typography.titleSmall)
        Text(
            text = blurb,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val working = state as? LocalLlm.State.Working
        if (working != null) {
            LinearProgressIndicator(
                progress = { working.progress },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "Downloading ${working.done / MEGABYTE} of ${working.total / MEGABYTE} MB",
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = { LocalLlm.cancel(model) }) { Text("Cancel") }
        } else if (installed) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Installed",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        LocalLlm.remove(context, model)
                        installed = false
                        saved = 0
                    },
                ) { Text("Remove") }
            }
        } else {
            OutlinedButton(onClick = { LocalLlm.start(context, model) }) {
                Text(
                    if (saved > 0) {
                        "Resume download ($megabytes MB, ${saved / MEGABYTE} MB saved)"
                    } else {
                        "Download ($megabytes MB)"
                    },
                )
            }
        }
        (state as? LocalLlm.State.Failed)?.let { failure ->
            Text(
                text = failure.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
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
