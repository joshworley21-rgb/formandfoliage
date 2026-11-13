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
import uk.co.jwgardenservices.crm.core.AppConfig
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

    /** Created on first cloud-routed turn; holds the build's API key only. */
    private val cloud by lazy { OpenAiCloudRouter(AppConfig.openAiApiKey) }

    /** Writes spoken commands into the same Room cache the screens use. */
    private val actions by lazy { VoiceActionRunner() }

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    private var voice = KokoroVoice.BmFable
    private var greeted = false
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        // A call or another assistant taking over must win: stop Kokoro.
        if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            speaker.stopPlayback()
        }
    }

    val speechState: StateFlow<SherpaSpeechService.UiState>
        get() = speech.state

    inner class LocalBinder : Binder() {
        fun service(): AssistantService = this@AssistantService
    }

    override fun onCreate() {
        super.onCreate()
        speech = SherpaSpeechService(this)
        speaker = KokoroTts(this)
        intents = QwenIntentEngine(this)
        CrashLog.note(this, TAG, "service created")
        startInForeground()
        requestAudioFocus()
        scope.launch { AssistantPreferences.voiceFlow(this@AssistantService).collect { voice = it } }
        scope.launch { handleTranscripts() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

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

    /** One spoken welcome per service session, so a fresh tab is not silent. */
    private suspend fun greet() {
        if (greeted) return
        greeted = true
        CrashLog.note(this, TAG, "greeting with Kokoro")
        val wasListening = speech.isListening()
        if (wasListening) speech.pauseForSpeech()
        _speaking.value = true
        try {
            speaker.speak("How can I help?", voice.speakerId)
        } catch (error: Exception) {
            CrashLog.note(this, TAG, "greeting failed: ${error.message}")
            Log.w(TAG, "Greeting playback failed", error)
        } finally {
            _speaking.value = false
            if (wasListening) speech.resumeAfterSpeech()
        }
    }

    fun clearTranscript() = speech.clear()

    private suspend fun handleTranscripts() {
        speech.transcripts.collect { text ->
            CrashLog.note(this, TAG, "transcript: ${text.take(120)}")
            // Interlock starts before the filler: from here until Kokoro's
            // last sample the microphone frames are discarded, so neither the
            // filler nor the confirmation can feed back into the recognizer.
            val wasListening = speech.isListening()
            if (wasListening) speech.pauseForSpeech()
            _speaking.value = true
            try {
                // Instant verbal filler removes the dead air while the local
                // Qwen model thinks.
                speaker.speak(fillerFor(text), voice.speakerId)
                val intent = intents.classify(text)
                CrashLog.note(this, TAG, "intent=${intent.intent.wire} cloud=${intent.requiresCloud}")
                if (intent.requiresCloud) {
                    speaker.speak(CHECKING_ONLINE, voice.speakerId)
                    val answer = runCatching { cloud.route(text, intent.clientOrTopic) }
                        .onFailure { error -> Log.w(TAG, "Cloud route failed", error) }
                        .getOrNull()
                    speaker.speak(
                        answer ?: if (AppConfig.openAiApiKey.isBlank()) {
                            "Online rates are not set up on this phone."
                        } else {
                            "I couldn't reach the online rates. Try again when you have signal."
                        },
                        voice.speakerId,
                    )
                } else {
                    val executed = runCatching { actions.run(text, intent) }
                        .onFailure { CrashLog.note(this, TAG, "action failed: ${it.message}") }
                        .getOrNull()
                    CrashLog.note(this, TAG, "action reply: ${executed ?: "none"}")
                    speaker.speak(executed ?: confirmationFor(intent), voice.speakerId)
                }
            } catch (error: Exception) {
                Log.w(TAG, "Kokoro playback failed", error)
            } finally {
                _speaking.value = false
                if (wasListening) speech.resumeAfterSpeech()
            }
        }
    }

    /** A short phrase said the moment the transcript commits. */
    private fun fillerFor(transcript: String): String {
        val text = transcript.lowercase()
        val schedule = listOf("schedule", "diary", "today", "next job", "calendar")
        return if (schedule.any { text.contains(it) }) "Checking schedule." else "Got it."
    }

    override fun onDestroy() {
        CrashLog.note(this, TAG, "service destroyed")
        abandonAudioFocus()
        scope.cancel()
        speaker.close()
        intents.close()
        speech.close()
        super.onDestroy()
    }

    /**
     * Ask for transient audio focus when the assistant takes over. Transient
     * focus is what makes a ringing phone or the van's media pause us, rather
     * than the assistant talking over the call.
     */
    private fun requestAudioFocus() {
        val manager = getSystemService(AudioManager::class.java) ?: return
        audioManager = manager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
            manager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            manager.requestAudioFocus(
                focusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT,
            )
        }
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

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("J W Garden Services")
            .setContentText("Assistant is listening for your next command")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setContentIntent(open)
            .build()

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                0
            },
        )
    }

    private companion object {
        const val TAG = "AssistantService"
        const val CHANNEL_ID = "assistant_mic"
        const val NOTIFICATION_ID = 41
        const val CHECKING_ONLINE = "Checking online rates."
    }
}

/** Short spoken confirmation for a classified command. */
private fun confirmationFor(intent: QwenIntentEngine.Intent): String {
    val topic = intent.clientOrTopic.trim()
    return when (intent.intent) {
        QwenIntentEngine.Kind.LogHours ->
            if (topic.isEmpty()) "Hours logged." else "Logged your hours for $topic."
        QwenIntentEngine.Kind.CheckSchedule -> "Checking your schedule."
        QwenIntentEngine.Kind.Quote ->
            if (topic.isEmpty()) "Preparing your quote." else "Preparing a quote for $topic."
        QwenIntentEngine.Kind.Other -> "I can log hours, check your schedule or prepare a quote."
    }
}
