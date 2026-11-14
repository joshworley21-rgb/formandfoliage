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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import uk.co.jwgardenservices.crm.MainActivity
import uk.co.jwgardenservices.crm.R
import uk.co.jwgardenservices.crm.core.CrashLog

/**
 * Keeps the Assistant microphone alive as a foreground service.
 *
 * The speech loop, the Qwen intent model and the Kokoro voice all live here,
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
    private lateinit var intents: QwenIntentEngine

    /** Created on first cloud-routed turn; holds no secret, only the endpoint. */
    private val cloud by lazy { OpenAiCloudRouter() }

    /** Writes spoken commands into the same Room cache the screens use. */
    private val actions by lazy { VoiceActionRunner() }

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    /** True while a call is connected (mic on, or just paused between tabs). */
    private val _callActive = MutableStateFlow(false)
    val callActive: StateFlow<Boolean> = _callActive.asStateFlow()

    private val _turns = MutableStateFlow<List<AssistantTurn>>(emptyList())
    val turns: StateFlow<List<AssistantTurn>> = _turns.asStateFlow()

    private var foreground = false
    private var cloudConversation = true
    private var onlineVoice = false
    private var cloudVoice: CloudVoice? = null
    private val assistantContext = AssistantContext()

    private var voice = KokoroVoice.BmFable
    private var speed = AssistantPreferences.DEFAULT_SPEED
    private var greeted = false
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
        speaker = KokoroEngine.get(this)
        intents = QwenEngine.get(this)
        CrashLog.note(this, TAG, "service created")
        scope.launch { AssistantPreferences.voiceFlow(this@AssistantService).collect { voice = it } }
        scope.launch { AssistantPreferences.speedFlow(this@AssistantService).collect { speed = it } }
        // Swapping between int8 and the downloaded fp32 model should take
        // effect on the next reply, without restarting the Assistant.
        scope.launch {
            AssistantPreferences.highQualityFlow(this@AssistantService).collect {
                speaker.reload()
            }
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
        scope.launch { handleTranscripts() }
    }

    /**
     * A started service is a call. Anything that began with startForegroundService
     * gets the notification and the microphone; a service that exists only to
     * serve the bound screen (after the user pressed End) does neither.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        CrashLog.note(this, TAG, "call starting")
        endedByUser = false
        _callActive.value = true
        startInForeground()
        scope.launch { ensureListening() }
        // Load the intent model while the owner is still talking, so the first
        // command does not wait for it.
        scope.launch(kotlinx.coroutines.Dispatchers.Default) { intents.warmUp() }
        scope.launch(kotlinx.coroutines.Dispatchers.Default) { speaker.warmUp() }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /** Start listening if this is the first bind or the loop stopped. */
    fun ensureListening() {
        CrashLog.note(this, TAG, "ensureListening")
        scope.launch {
            if (!speech.isListening() && speech.state.value.phase != SherpaSpeechService.Phase.Loading) {
                speech.startListening()
            }
            if (speech.isListening()) greet()
        }
    }

    /** One spoken welcome per call, so a fresh tab is not silent. */
    private suspend fun greet() {
        if (greeted) return
        greeted = true
        CrashLog.note(this, TAG, "greeting with Kokoro")
        val wasListening = speech.isListening()
        if (wasListening) speech.pauseForSpeech()
        _speaking.value = true
        try {
            say("How can I help?")
        } catch (error: Exception) {
            CrashLog.note(this, TAG, "greeting failed: ${error.message}")
            Log.w(TAG, "Greeting playback failed", error)
        } finally {
            _speaking.value = false
            if (wasListening) speech.resumeAfterSpeech()
        }
    }

    /** Speak a reply and add it to the rolling transcript. */
    private suspend fun say(text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        addTurn(AssistantTurn(fromUser = false, text = clean))
        // Hold audio focus only for the utterance: the call itself can stay
        // connected across tabs without pausing the van's music all day.
        val focused = requestAudioFocus()
        try {
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
        endedByUser = true
        _callActive.value = false
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
            CrashLog.note(this, TAG, "transcript: ${text.take(120)}")
            addTurn(AssistantTurn(fromUser = true, text = text))
            // Interlock starts before the filler: from here until Kokoro's
            // last sample the microphone frames are discarded, so neither the
            // filler nor the confirmation can feed back into the recognizer.
            val wasListening = speech.isListening()
            if (wasListening) speech.pauseForSpeech()
            _speaking.value = true
            try {
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
                _speaking.value = false
                if (wasListening) speech.resumeAfterSpeech()
            }
        }
    }

    /** Classify one finished sentence and either act locally or ask the cloud. */
    private suspend fun answerTurn(text: String) {
        val context = businessContext()
        // Fast path: the common commands are unambiguous enough that waiting
        // for a 0.5B model to agree only adds a second of silence.
        val quick = IntentRules.classify(text)
        val intent = if (IntentRules.isConfident(quick.intent) && !quick.requiresCloud) {
            CrashLog.note(this, TAG, "intent=rules:${quick.intent.wire}")
            quick
        } else {
            intents.classify(text, context)
        }
        val conversational = intent.intent == QwenIntentEngine.Kind.Other
        val wantsCloud = intent.requiresCloud || (conversational && cloudConversation)
        CrashLog.note(this, TAG, "intent=${intent.intent.wire} cloud=$wantsCloud")
        if (wantsCloud) {
            if (intent.requiresCloud) say(CHECKING_ONLINE)
            val answer = runCatching {
                cloud.route(text, intent.clientOrTopic, context, _turns.value.takeLast(6))
            }
                .onFailure { error -> Log.w(TAG, "Cloud route failed", error) }
                .getOrNull()
            say(answer ?: "I couldn't reach the online assistant. Try again when you have signal.")
        } else {
            val executed = runCatching { actions.run(text, intent) }
                .onFailure { CrashLog.note(this, TAG, "action failed: ${it.message}") }
                .getOrNull()
            CrashLog.note(this, TAG, "action reply: ${executed ?: "none"}")
            say(executed ?: confirmationFor(intent))
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
        abandonAudioFocus()
        scope.cancel()
        speaker.stopPlayback()
        cloudVoice?.close()
        cloudVoice = null
        // Kokoro and the intent model are process-scoped too; leave them loaded.
        // The recognizer is process-scoped on purpose: close the microphone,
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

    private fun startInForeground() {
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
            buildNotification(LISTENING_TEXT),
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
        const val LISTENING_TEXT = "Assistant is listening for your next command"
        const val MAX_TURNS = 40

        /**
         * Set when the user hangs up, so returning to the tab shows "Start
         * call" instead of silently reopening the microphone. Reset by the
         * start action or by a fresh process.
         */
        @Volatile
        var endedByUser: Boolean = false
    }
}

/** Short spoken confirmation for a classified command. */
private fun confirmationFor(intent: QwenIntentEngine.Intent): String {
    val topic = intent.clientOrTopic.trim()
    return when (intent.intent) {
        QwenIntentEngine.Kind.LogHours ->
            if (topic.isEmpty()) "Hours logged." else "Logged your hours for $topic."
        QwenIntentEngine.Kind.CheckSchedule -> "Checking your schedule."
        QwenIntentEngine.Kind.NextJob -> "Checking your next job."
        QwenIntentEngine.Kind.MarkJobDone ->
            if (topic.isEmpty()) "Marking that job done." else "Marking the job for $topic done."
        QwenIntentEngine.Kind.WhoOwesMe -> "Checking who owes you."
        QwenIntentEngine.Kind.WeeklyEarnings -> "Checking this week's earnings."
        QwenIntentEngine.Kind.Quote ->
            if (topic.isEmpty()) "Preparing your quote." else "Preparing a quote for $topic."
        QwenIntentEngine.Kind.Other -> "I can log hours, check your diary or tell you who owes you."
    }
}
