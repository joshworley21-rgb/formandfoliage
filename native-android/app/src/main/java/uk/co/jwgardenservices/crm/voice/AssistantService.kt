package uk.co.jwgardenservices.crm.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import uk.co.jwgardenservices.crm.MainActivity
import uk.co.jwgardenservices.crm.R
import uk.co.jwgardenservices.crm.core.CrashLog

/**
 * Keeps the Assistant microphone alive as a foreground service.
 *
 * The speech loop, the rule/cloud classifier and the voice all live here,
 * so recogniser state survives config changes and the mic keeps working while
 * the screen is off. The UI only observes and never owns the audio.
 *
 * The turn pattern is fully hands-free:
 *   listen → endpoint → classify → Kokoro speaks → microphone reopens.
 * While Kokoro has the floor the AudioRecord loop keeps draining the device
 * buffer but the samples are dropped, so bm_fable's own voice can never be
 * recognised as the next command.
 */
class AssistantService : Service() {

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private lateinit var speech: SherpaSpeechService
    private lateinit var speaker: KokoroTts

    /** Created on first cloud-routed turn; holds no secret, only the endpoint. */
    private val cloud by lazy {
        OpenAiCloudRouter(onEvent = { CrashLog.note(this, CLOUD_TAG, it) })
    }

    /** Writes spoken commands into the same Room cache the screens use. */
    private val actions by lazy { VoiceActionRunner() }

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    /** True while a heard sentence is being classified and acted on. */
    private val _thinking = MutableStateFlow(false)
    val thinking: StateFlow<Boolean> = _thinking.asStateFlow()

    /** True while a call is connected (mic on, or just paused between tabs). */
    private val _callActive = MutableStateFlow(false)
    val callActive: StateFlow<Boolean> = _callActive.asStateFlow()

    /** True from the green button until the line is ready and the assistant answers. */
    private val _dialling = MutableStateFlow(false)
    val dialling: StateFlow<Boolean> = _dialling.asStateFlow()

    private val _turns = MutableStateFlow<List<AssistantTurn>>(emptyList())
    val turns: StateFlow<List<AssistantTurn>> = _turns.asStateFlow()

    private var foreground = false
    private var cloudConversation = true
    private var onlineVoice = false
    private var cloudVoice: CloudVoice? = null
    private val assistantContext = AssistantContext()

    private var voice = KokoroVoice.BmFable
    private var speed = AssistantPreferences.DEFAULT_SPEED

    /** Whether a fresh call answers with a short "Hello?". */
    private var pickupGreeting = true

    /** Set once the caller has said something in this call. */
    @Volatile
    private var heardInCall = false

    /** Ringback while the recogniser and voice warm up. */
    private val ringback = VoiceRingback()
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        // A call or another assistant taking over must win: stop whichever
        // voice has the floor.
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            speaker.stopPlayback()
            cloudVoice?.stop()
        }
    }

    val speechState: StateFlow<SherpaSpeechService.UiState>
        get() = speech.state

    inner class LocalBinder : Binder() {
        fun service(): AssistantService = this@AssistantService
    }

    override fun onCreate() {
        super.onCreate()
        speech = SpeechEngine.get(this)
        speech.onBargeIn = {
            CrashLog.note(this, TAG, "barge-in; stopping playback")
            speaker.stopPlayback()
            cloudVoice?.stop()
        }
        speaker = KokoroEngine.get(this)
        CrashLog.note(this, TAG, "service created")
        scope.launch { AssistantPreferences.voiceFlow(this@AssistantService).collect { voice = it; speaker.clearFiller() } }
        scope.launch { AssistantPreferences.speedFlow(this@AssistantService).collect { speed = it; speaker.clearFiller() } }
        // Selecting a downloaded Piper voice rebuilds the engine on the next reply.
        scope.launch {
            AssistantPreferences.piperVoiceFlow(this@AssistantService).drop(1).collect {
                speaker.reload()
            }
        }
        scope.launch {
            AssistantPreferences.phononFlow(this@AssistantService).drop(1).collect {
                speaker.reload()
            }
        }
        // Swapping between int8 and the downloaded fp32 model should take
        // effect on the next reply, without restarting the Assistant.
        scope.launch {
            // drop(1): the initial emission is the current value, not a
            // change. Reloading on it unloaded a warm fp32 engine at every
            // call start, which cost several seconds before the first word.
            AssistantPreferences.highQualityFlow(this@AssistantService)
                .drop(1)
                .collect { speaker.reload() }
        }
        scope.launch {
            val saved = AssistantMemory.turns(this@AssistantService)
            if (saved.isNotEmpty() && _turns.value.isEmpty()) _turns.value = saved
        }
        scope.launch {
            AssistantMemory.cloudConversationFlow(this@AssistantService).collect { cloudConversation = it }
        }
        scope.launch {
            AssistantPreferences.onlineVoiceFlow(this@AssistantService).collect { onlineVoice = it }
        }
        scope.launch {
            AssistantPreferences.pickupFlow(this@AssistantService).collect { pickupGreeting = it }
        }
        scope.launch { handleTranscripts() }
    }

    /**
     * A started service is a call. Anything that began with startForegroundService
     * gets the notification and the microphone; a service that exists only to
     * serve the bound screen (after the user pressed End) does neither.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Returning to the tab starts the service again but is not a new call:
        // only a call that was not already connected gets the pickup greeting.
        val fresh = !_callActive.value
        CrashLog.note(this, TAG, if (fresh) "call starting" else "call resuming")
        if (fresh) {
            heardInCall = false
            // Ring out while the model and voice load; the caller hears a
            // dialling line instead of a loading spinner.
            _dialling.value = true
            ringback.start()
        }
        _callActive.value = true
        startInForeground(if (fresh) CALLING_TEXT else LISTENING_TEXT)
        scope.launch { AssistantPreferences.markUsed(this@AssistantService) }
        // Rejoining an existing call just reopens the microphone.
        if (!fresh) scope.launch { ensureListening() }
        scope.launch(Dispatchers.Default) {
            val callVoice = runCatching { AssistantPreferences.voiceFlow(this@AssistantService).first() }
                .getOrDefault(voice)
            val callSpeed = runCatching { AssistantPreferences.speedFlow(this@AssistantService).first() }
                .getOrDefault(speed)
            voice = callVoice
            speed = callSpeed
            // Load the recogniser and the voice together. On a fresh call the
            // microphone stays closed while the ringback plays, so it cannot
            // hear the tones as a command.
            coroutineScope {
                launch { runCatching { speech.prepare() } }
                speaker.warmUp()
                runCatching { speaker.prepareFiller(callVoice.speakerId, callSpeed) }
            }
            if (fresh) connectCall(callVoice.speakerId, callSpeed)
        }
        return START_NOT_STICKY
    }

    /**
     * Finish dialling: stop the ringback, open the line and answer with the
     * cached "Hello?". Waiting for [SherpaSpeechService.Phase.Listening]
     * keeps the greeting out of the recogniser.
     */
    private suspend fun connectCall(speakerId: Int, speed: Float) {
        if (!_callActive.value) {
            ringback.stop()
            _dialling.value = false
            return
        }
        // Both models are warm by now. Ring off, then open the microphone.
        ringback.stop()
        speech.startListening()
        if (!_callActive.value) {
            speech.stopListening()
            _dialling.value = false
            return
        }
        val connected = withTimeoutOrNull(8_000) {
            speech.state.first {
                it.phase == SherpaSpeechService.Phase.Listening ||
                    it.phase == SherpaSpeechService.Phase.Error
            }
            speech.isListening()
        } ?: false
        setNotification(LISTENING_TEXT)
        _dialling.value = false
        if (!connected || !_callActive.value) {
            CrashLog.note(this, TAG, "pickup skipped: line not connected")
            return
        }
        if (heardInCall || _thinking.value || _speaking.value) {
            CrashLog.note(this, TAG, "pickup skipped: caller already speaking")
            return
        }
        if (speech.state.value.partial.isNotBlank()) {
            CrashLog.note(this, TAG, "pickup skipped: partial speech")
            return
        }
        val wantsGreeting = runCatching {
            AssistantPreferences.pickupFlow(this@AssistantService).first()
        }.getOrDefault(pickupGreeting)
        if (!wantsGreeting) {
            CrashLog.note(this, TAG, "pickup skipped: greeting is off")
            return
        }
        val greeting = PICKUP_TEXT
        CrashLog.note(this, TAG, "pickup greeting: $greeting")
        val started = System.currentTimeMillis()
        speech.pauseForSpeech()
        _speaking.value = true
        // Show the greeting in the conversation immediately, even if the
        // speaker is muted or the clip fails.
        _turns.value = (_turns.value + AssistantTurn(fromUser = false, text = greeting))
            .takeLast(MAX_TURNS)
        val focused = requestAudioFocus()
        try {
            // Let the ringback AudioTrack release and the route settle before
            // the first syllable, otherwise the greeting can start clipped.
            delay(120)
            val spoken = speaker.speakPickup(speakerId, speed)
            CrashLog.note(this, TAG, "pickup spoken=$spoken in ${System.currentTimeMillis() - started}ms")
        } finally {
            if (focused) abandonAudioFocus()
            _speaking.value = false
            speech.resumeAfterSpeech()
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /** Start listening if this is the first bind or the loop stopped. */
    fun ensureListening() {
        CrashLog.note(this, TAG, "ensureListening")
        scope.launch {
            // startListening waits for an in-flight preload rather than
            // skipping the call, so a warm-up at app launch never leaves the
            // microphone closed. The voice and its cached clips are prepared
            // by onStartCommand, where the saved voice is read first.
            speech.startListening()
        }
    }

    /** Speak a reply and add it to the rolling transcript. */
    private suspend fun say(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        _thinking.value = false
        addTurn(AssistantTurn(fromUser = false, text = clean))
        // Hold audio focus only for the utterance: the call itself can stay
        // connected across tabs without pausing the van's music all day.
        val focused = requestAudioFocus()
        try {
            CrashLog.note(this, TAG, "reply voice=${if (onlineVoice) "online" else "device"}")
            // The natural online voice is optional; any failure falls back to
            // Kokoro rather than leaving the assistant silent.
            val spokenOnline = onlineVoice &&
                runCatching { onlineSpeaker().speak(clean) }.getOrDefault(false)
            if (!spokenOnline) speaker.speakStreaming(clean, voice.speakerId, speed)
        } finally {
            if (focused) abandonAudioFocus()
        }
    }

    private fun onlineSpeaker(): CloudVoice =
        cloudVoice ?: CloudVoice(this).also { cloudVoice = it }

    private fun addTurn(turn: AssistantTurn) {
        _turns.value = (_turns.value + turn).takeLast(MAX_TURNS)
        scope.launch { AssistantMemory.appendTurn(this@AssistantService, turn) }
    }

    /** Close the microphone but keep the service (and the loaded model) alive. */
    fun pauseListening() {
        if (!speech.isListening()) return
        CrashLog.note(this, TAG, "pausing the microphone")
        speech.stopListening()
        setNotification("Assistant paused")
    }

    /**
     * Hang up: close the microphone, drop the notification and stop being a
     * started service. The screen may still be bound, so the model stays warm
     * until the user leaves the tab.
     */
    fun endCall() {
        CrashLog.note(this, TAG, "call ended")
        _callActive.value = false
        _dialling.value = false
        ringback.stop()
        speech.stopListening()
        abandonAudioFocus()
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        foreground = false
        stopSelf()
    }

    /** Wipe the conversation, not just the last sentence. */
    fun clearTranscript() {
        speech.clear()
        _turns.value = emptyList()
        scope.launch { AssistantMemory.clearTurns(this@AssistantService) }
    }

    private suspend fun handleTranscripts() {
        speech.transcripts.collect { text ->
            if (!heardInCall) {
                heardInCall = true
                ringback.stop()
                _dialling.value = false
            }
            CrashLog.note(this, TAG, "transcript: ${text.take(120)}")
            addTurn(AssistantTurn(fromUser = true, text = text))
            _thinking.value = true
            // Interlock starts before the filler: from here until Kokoro's
            // last sample the microphone frames are discarded, so neither the
            // filler nor the confirmation can feed back into the recognizer.
            val wasListening = speech.isListening()
            if (wasListening) speech.pauseForSpeech()
            _speaking.value = true
            try {
                // A yes/no finishes an "are you sure?" from the last turn
                // before "undo that" reverses the last logged visit or client
                // note. Both run before memory and classification so a stray
                // word cannot lose the pending action.
                val confirmed = runCatching { actions.answerConfirmation(text) }
                    .onFailure { CrashLog.note(this, TAG, "confirmation failed: ${it.message}") }
                    .getOrNull()
                if (confirmed != null) {
                    say(confirmed)
                    return@collect
                }
                if (undoRequested(text)) {
                    say(actions.undoLast())
                    return@collect
                }
                val remembered = runCatching { memoryCommand(text) }
                    .onFailure { CrashLog.note(this, TAG, "memory command failed: ${it.message}") }
                    .getOrNull()
                if (remembered != null) {
                    say(remembered)
                    return@collect
                }
                // A previous turn may have asked "How many hours?" — finish
                // that before treating this sentence as a new command.
                val followUp = runCatching { actions.continuePending(text) }
                    .onFailure { CrashLog.note(this, TAG, "pending action failed: ${it.message}") }
                    .getOrNull()
                if (followUp != null) {
                    CrashLog.note(this, TAG, "pending reply: ${followUp.take(120)}")
                    say(followUp)
                } else {
                    answerTurn(text)
                }
            } catch (error: Exception) {
                Log.w(TAG, "Kokoro playback failed", error)
            } finally {
                _thinking.value = false
                _speaking.value = false
                if (wasListening) speech.resumeAfterSpeech()
            }
        }
    }

    /** Classify one finished sentence and either act locally or ask the cloud. */
    private suspend fun answerTurn(text: String) {
        val started = System.currentTimeMillis()

        // "log four hours for Smiths, then mark Smiths done" is two actions.
        // Run the recognised parts in order and speak one combined reply. This
        // is free and local by design; nothing multi-step goes to the cloud.
        val parts = splitMultiCommand(text)
        if (parts.size > 1) {
            val replies = mutableListOf<String>()
            for (part in parts) {
                val intent = IntentRules.classify(part)
                if (intent.intent == SpokenIntent.Other || intent.requiresCloud) continue
                val reply = runCatching { actions.run(part, intent) }.getOrNull() ?: continue
                replies += reply
                if (reply.endsWith("?")) break
            }
            if (replies.isNotEmpty()) {
                CrashLog.note(this, TAG, "multi-step: ${replies.size} in ${System.currentTimeMillis() - started}ms")
                say(replies.joinToString(" "))
                return
            }
        }

        // 1. Rules: the common commands are instant and free. A date word
        //    ("next week", "tomorrow") means the today-only actions are not
        //    enough, so those are allowed to go to the cloud. The commands
        //    that do not depend on the date stay local regardless.
        val lower = text.lowercase()
        val temporal = TEMPORAL_WORDS.any { lower.contains(it) }
        val quick = IntentRules.classify(text)
        val dateIndependent = quick.intent in setOf(
            SpokenIntent.CheckSchedule,
            SpokenIntent.AddNote,
            SpokenIntent.ClientList,
            SpokenIntent.Unbilled,
            SpokenIntent.InvoiceStatus,
            SpokenIntent.NextJobForClient,
            SpokenIntent.TodayEarnings,
        )
        val localTemporal = dateIndependent && temporal
        if ((!temporal || localTemporal) && IntentRules.isConfident(quick.intent) && !quick.requiresCloud) {
            CrashLog.note(
                this,
                TAG,
                "intent=rules:${quick.intent.wire} in ${System.currentTimeMillis() - started}ms",
            )
            runIntent(text, quick)
            return
        }

        // 2. Exactly one optional cloud call. The business snapshot is built
        //    only now, and only when a cloud call is actually allowed: a local
        //    answer never pays for the Room/DataStore reads.
        if (cloudConversation) {
            val context = businessContext()
            // The function classifies and, for conversational turns, produces
            // the answer in the same response — so a sentence never costs two
            // requests and never waits for the removed local model.
            val reply = runCatching {
                coroutineScope {
                    val pending = async { cloud.ask(text, context, _turns.value.takeLast(6)) }
                    // Let a quick turn answer on its own; if it is slower than
                    // a beat, cover the wait with a cached "Let me check."
                    val quick = withTimeoutOrNull(300) { pending.await() }
                    if (quick != null) {
                        quick
                    } else {
                        if (!onlineVoice) {
                            runCatching { speaker.speakFiller(voice.speakerId, speed) }
                        }
                        pending.await()
                    }
                }
            }
                .onFailure { CrashLog.note(this, TAG, "cloud failed: ${it.message}") }
                .getOrNull()

            if (reply != null) {
                if (reply.answer.isNotBlank()) {
                    CrashLog.note(
                        this,
                        TAG,
                        "cloud answered in ${System.currentTimeMillis() - started}ms",
                    )
                    say(reply.answer)
                    return
                }
                val intent = reply.intent
                if (intent != null) {
                    CrashLog.note(
                        this,
                        TAG,
                        "intent=cloud:${intent.intent.wire} in ${System.currentTimeMillis() - started}ms",
                    )
                    if (intent.requiresCloud) {
                        say("I couldn't get an answer from the online assistant.")
                    } else {
                        runIntent(text, intent)
                    }
                    return
                }
            }
            CrashLog.note(this, TAG, "cloud unavailable; using the rules")
        } else {
            CrashLog.note(this, TAG, "online answers are off; using the rules")
        }

        // 3. Free fallback: the rules, then a short help line.
        when {
            quick.requiresCloud -> say(
                if (cloudConversation) {
                    "I couldn't reach the online assistant. Try again when you have signal."
                } else {
                    "Online answers are switched off in settings."
                },
            )

            quick.intent == SpokenIntent.Other -> {
                val chatter = smallTalk(text)
                say(
                    when {
                        chatter != null -> chatter
                        cloudConversation ->
                            "I couldn't reach the online assistant. Try again when you have signal."
                        else -> HELP_TEXT
                    },
                )
            }

            else -> runIntent(text, quick)
        }
    }

    /** Carry out a local intent and speak its result. */
    private suspend fun runIntent(text: String, intent: IntentResult) {
        val executed = runCatching { actions.run(text, intent) }
            .onFailure { CrashLog.note(this, TAG, "action failed: ${it.message}") }
            .getOrNull()
        CrashLog.note(this, TAG, "action reply: ${executed ?: "none"}")
        say(executed ?: confirmationFor(intent))
    }

    /**
     * Split a sentence into independent commands. "and then", "then" and ";"
     * are explicit. A plain "and" only splits when both sides look like a
     * command, so "Louisa and George" stays one client name.
     */
    private fun splitMultiCommand(text: String): List<String> {
        val strong = text.split(Regex("\\b(?:and then|then)\\b|;", RegexOption.IGNORE_CASE))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (strong.size > 1) return strong

        val and = text.split(Regex("\\s+and\\s+", RegexOption.IGNORE_CASE))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        if (and.size in 2..3 && and.all { looksLikeCommand(it) }) return and
        return listOf(text)
    }

    private fun looksLikeCommand(part: String): Boolean {
        if (part.split(Regex("\\s+")).size < 2) return false
        val intent = IntentRules.classify(part)
        return intent.intent != SpokenIntent.Other && !intent.requiresCloud
    }

    /** "Undo", "undo that", "scratch that": reverse the last change. */
    private fun undoRequested(text: String): Boolean =
        Regex("^(undo|undo that|undo the last one|scratch that|revert that)[.!]?$")
            .matches(text.lowercase().trim())

    /** A few free replies so greetings are not answered with a help list. */
    private fun smallTalk(text: String): String? {
        val lower = text.lowercase().trim()
        return when {
            Regex("^(hi|hello|hey|good morning|good afternoon|good evening)\b")
                .containsMatchIn(lower) -> "Hello. What can I do?"
            lower.contains("thank") -> "Any time."
            lower.contains("how are you") -> "All good, thanks. What do you need?"
            lower.contains("what can you do") || lower == "help" -> HELP_TEXT
            else -> null
        }
    }

    /** The business snapshot handed to the local and cloud models. */
    private suspend fun businessContext(): String {
        val facts = runCatching { AssistantMemory.facts(this).map { it.text } }.getOrDefault(emptyList())
        return assistantContext.snapshot(facts, _turns.value.takeLast(8))
    }

    /**
     * "Remember …", "forget …", "what do you remember?". Handled before the
     * intent classifier so it works even with no GGUF installed.
     */
    private suspend fun memoryCommand(text: String): String? {
        val lower = text.lowercase().trim()
        if (lower.contains("what do you remember") ||
            lower.contains("what have you remembered") ||
            lower == "what do you know"
        ) {
            val facts = AssistantMemory.facts(this)
            if (facts.isEmpty()) return "I haven't been told anything to remember yet."
            return "I remember: " + facts.joinToString("; ") { it.text } + "."
        }
        if (lower.startsWith("forget everything") ||
            lower == "forget it all" ||
            lower == "forget all of it"
        ) {
            AssistantMemory.forgetEverything(this)
            return "Done. My memory is empty."
        }
        Regex(
            "^\\s*(?:please\\s+)?remember(?:\\s+that)?\\s+(.+)$",
            RegexOption.IGNORE_CASE,
        ).find(text)?.let { match ->
            val fact = match.groupValues[1].trim()
            if (fact.isBlank()) return null
            val added = AssistantMemory.remember(this, fact)
            return if (added) "I'll remember that." else "I already had that one."
        }
        Regex(
            "^\\s*(?:please\\s+)?forget(?:\\s+that)?\\s+(.+)$",
            RegexOption.IGNORE_CASE,
        ).find(text)?.let { match ->
            val needle = match.groupValues[1].trim()
            if (needle.isBlank()) return null
            val removed = AssistantMemory.forget(this, needle)
            return when {
                removed == 0 -> "I don't have anything about that."
                removed == 1 -> "Forgotten."
                else -> "Forgotten $removed things."
            }
        }
        return null
    }

    override fun onDestroy() {
        CrashLog.note(this, TAG, "service destroyed")
        _callActive.value = false
        _dialling.value = false
        ringback.stop()
        abandonAudioFocus()
        scope.cancel()
        speaker.stopPlayback()
        cloudVoice?.close()
        cloudVoice = null
        // Kokoro is process-scoped too; leave it loaded. The recogniser is
        // process-scoped on purpose: close the microphone,
        // but leave the 190 MB model loaded for the next call.
        speech.stopListening()
        super.onDestroy()
    }

    /**
     * Ask for transient audio focus for one utterance. Transient focus is what
     * makes a ringing phone or the van's media pause us, rather than the
     * assistant talking over the call. Returns true when the request should be
     * released afterwards.
     */
    private fun requestAudioFocus(): Boolean {
        if (focusRequest != null) return false
        val manager = getSystemService(AudioManager::class.java) ?: return false
        audioManager = manager
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener(focusListener)
                .build()
            focusRequest = request
            val result = manager.requestAudioFocus(request)
            // Leave no half-request behind: a refused focus must be retried
            // next time rather than remembered as held.
            if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) focusRequest = null
            result
        } else {
            @Suppress("DEPRECATION")
            manager.requestAudioFocus(
                focusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
            )
        }
        return granted == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonAudioFocus() {
        val manager = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { manager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            manager.abandonAudioFocus(focusListener)
        }
        focusRequest = null
        audioManager = null
    }

    private fun startInForeground(text: String = LISTENING_TEXT) {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Assistant microphone",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Keeps the offline assistant listening" }
            manager.createNotificationChannel(channel)
        }

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(text),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                0
            },
        )
        foreground = true
    }

    /** Update the ongoing notification without taking the service down. */
    private fun setNotification(text: String) {
        if (!foreground) return
        runCatching {
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification(text))
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("J W Garden Services")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    companion object {
        const val TAG = "AssistantService"
        const val CHANNEL_ID = "assistant_mic"
        const val NOTIFICATION_ID = 41
        const val CHECKING_ONLINE = "Checking online rates."
        const val CALLING_TEXT = "Assistant is dialling…"
        const val LISTENING_TEXT = "Assistant is listening for your next command"
        const val CLOUD_TAG = "CloudRouter"
        const val HELP_TEXT = "I can log hours, check your diary, mark a job done, add a client " +
            "note, read out invoices and takings, or say undo to reverse the last change."
        val TEMPORAL_WORDS = listOf("week", "tomorrow", "month", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
        const val MAX_TURNS = 40

    }
}

/** Short spoken confirmation for a classified command. */
private fun confirmationFor(intent: IntentResult): String {
    val topic = intent.clientOrTopic.trim()
    return when (intent.intent) {
        SpokenIntent.LogHours ->
            if (topic.isEmpty()) "Hours logged." else "Logged your hours for $topic."
        SpokenIntent.CheckSchedule -> "Checking your schedule."
        SpokenIntent.NextJob -> "Checking your next job."
        SpokenIntent.NextJobAddress -> "Checking where your next job is."
        SpokenIntent.MonthEarnings -> "Checking this month's earnings."
        SpokenIntent.HoursThisWeek -> "Checking your hours."
        SpokenIntent.ClientInfo -> "Looking up that client."
        SpokenIntent.LastVisit -> "Checking that client's last visit."
        SpokenIntent.BestClient -> "Checking your best client."
        SpokenIntent.AddNote ->
            if (topic.isEmpty()) "Adding that note." else "Adding a note for $topic."
        SpokenIntent.Unbilled -> "Checking what is waiting to be invoiced."
        SpokenIntent.ClientList -> "Reading out your clients."
        SpokenIntent.InvoiceStatus -> "Checking that invoice."
        SpokenIntent.NextJobForClient ->
            if (topic.isEmpty()) "Checking that booking." else "Checking when $topic is next in."
        SpokenIntent.TodayEarnings -> "Checking today's takings."
        SpokenIntent.MarkJobDone ->
            if (topic.isEmpty()) "Marking that job done." else "Marking the job for $topic done."
        SpokenIntent.WhoOwesMe -> "Checking who owes you."
        SpokenIntent.WeeklyEarnings -> "Checking this week's earnings."
        SpokenIntent.Quote ->
            if (topic.isEmpty()) "Preparing your quote." else "Preparing a quote for $topic."
        SpokenIntent.Other ->
            "I can log hours, check your diary, add a note, or tell you who owes you."
    }
}
