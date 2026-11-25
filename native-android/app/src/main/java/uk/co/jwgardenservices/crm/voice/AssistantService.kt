package uk.co.jwgardenservices.crm.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.Manifest
import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioFocusRequest
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.support.v4.media.session.MediaSessionCompat
import android.util.Base64
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import uk.co.jwgardenservices.crm.MainActivity
import uk.co.jwgardenservices.crm.R
import uk.co.jwgardenservices.crm.core.CrashLog
import uk.co.jwgardenservices.crm.data.repository.ServiceLocator
import uk.co.jwgardenservices.crm.data.repository.invoiceItems
import uk.co.jwgardenservices.crm.data.repository.sendInvoice
import uk.co.jwgardenservices.crm.data.model.Client
import uk.co.jwgardenservices.crm.domain.money
import uk.co.jwgardenservices.crm.ui.common.Messaging
import uk.co.jwgardenservices.crm.ui.invoices.InvoicePdf
import uk.co.jwgardenservices.crm.ui.invoices.PdfSharing

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

    private val freeWeb by lazy { FreeWebAnswers(this) }

    /** Writes spoken commands into the same Room cache the screens use. */
    private val actions by lazy {
        VoiceActionRunner(
            sendInvoice = { invoiceId, message -> sendInvoiceByVoice(invoiceId, message) },
            shareWhatsApp = { invoiceId, message ->
                shareInvoiceWhatsAppByVoice(invoiceId, message)
            },
        )
    }

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    /** True while a heard sentence is being classified and acted on. */
    private val _thinking = MutableStateFlow(false)
    val thinking: StateFlow<Boolean> = _thinking.asStateFlow()

    /** Delayed "let me check" clip, cancelled as soon as an answer is ready. */
    private var thinkingMask: Job? = null

    /** "Ah, my mistake —" prefix for a self-correction, consumed once. */
    private var correctionLead: String? = null

    /** The last real command, replayed by "same again" / "the usual". */
    private var lastCommand: String? = null

    /** True while a call is connected (mic on, or just paused between tabs). */
    private val _callActive = MutableStateFlow(false)
    val callActive: StateFlow<Boolean> = _callActive.asStateFlow()

    /** Milliseconds timestamp when the current call connected; 0 while dialling. */
    private val _connectedAt = MutableStateFlow(0L)
    val connectedAt: StateFlow<Long> = _connectedAt.asStateFlow()

    /** Microphone muted for this call; the assistant keeps listening to nothing. */
    private val _muted = MutableStateFlow(false)
    val muted: StateFlow<Boolean> = _muted.asStateFlow()

    /** Loudspeaker route, best effort per device. */
    private val _speakerOn = MutableStateFlow(false)
    private var audioRouteJob: Job? = null
    val speakerOn: StateFlow<Boolean> = _speakerOn.asStateFlow()

    /** True from the green button until the line is ready and the assistant answers. */
    private val _dialling = MutableStateFlow(false)
    val dialling: StateFlow<Boolean> = _dialling.asStateFlow()

    private val _turns = MutableStateFlow<List<AssistantTurn>>(emptyList())
    val turns: StateFlow<List<AssistantTurn>> = _turns.asStateFlow()

    private var foreground = false
    private var cloudConversation = true
    private var localThinking = true
    private var drivingMode = false
    private var manualDrivingMode = false
    private var autoDrivingMode = true
    private var bluetoothDriving = false
    private var onlineVoice = false
    private var personaTone = PersonaTone.Warm
    private var personaHumour = PersonaHumour.Normal
    private var personaLength = PersonaLength.Normal

    /** Waiting for the text of a "message X on WhatsApp" turn. */
    private var pendingWhatsApp: Client? = null

    /** True while the battery is under 15% and unplugged: cloud features off. */
    @Volatile
    private var lowBattery = false

    /** Dead-air watcher: nudges once or twice, never nags. */
    private var silenceJob: kotlinx.coroutines.Job? = null
    @Volatile
    private var lastHeardAt = 0L
    @Volatile
    private var silencePrompts = 0

    /** Has the microphone shown any real audio since listening began? */
    @Volatile
    private var micHeardAnything = false
    @Volatile
    private var hearHintGiven = false
    private var cloudVoice: CloudVoice? = null
    // Lazy: the base context is only attached after construction, and the
    // profile asset is read once per process on first use.
    private val assistantContext by lazy {
        AssistantContext(businessProfile = BusinessBrain.load(this))
    }
    private var voice = KokoroVoice.BmFable
    private var speed = AssistantPreferences.DEFAULT_SPEED

    /** Whether a fresh call answers with a short "Hello?". */
    private var pickupGreeting = true

    /** Whether a finished answer is followed by "Anything else?". */
    private var askFollowUp = true

    /** Selected recogniser, mirrored so the quick-resume check is synchronous. */
    private var selectedEngine = AsrEngine.Moonshine

    /** Set once the caller has said something in this call. */
    @Volatile
    private var heardInCall = false

    /** Ringback while the recogniser and voice warm up. */
    private val ringback by lazy { RingbackPlayer(this) }
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null

    /** Re-routes the call when earphones or the van's head unit connect mid-call. */
    private var deviceCallback: AudioDeviceCallback? = null

    /** Owns the head-unit call button while a call is up; idle starts use the receiver. */
    private var mediaSession: MediaSessionCompat? = null
    private var lastCallButtonAt = 0L

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
        speech.onBackchannel = {
            // Wired-headset only, decided in the recogniser; never over a
            // loudspeaker or a car kit.
            if (_callActive.value && !_speaking.value && !_muted.value) {
                scope.launch {
                    runCatching { speaker.speakBackchannel(voice.speakerId, speed) }
                }
            }
        }
        speech.onBargeIn = {
            CrashLog.note(this, TAG, "barge-in; stopping playback")
            // Only a real interruption of the voice counts — a barge while
            // the line is merely open is normal turn-taking.
            if (_speaking.value) AssistantMemory.wasInterrupted = true
            speaker.stopPlayback()
            cloudVoice?.stop()
        }
        // The answer to a question the assistant was still asking: kill the
        // audio, take the word as the answer, speak the next item straight
        // away — no thinking pause, no filler.
        speech.onInterjection = { text ->
            scope.launch { handleInterjection(text) }
        }
        speaker = KokoroEngine.get(this)
        // A call is "media" to the platform's button router. Activated only
        // while a call is up, so it never steals play/pause from music; the
        // idle start is handled by CallButtonReceiver instead.
        mediaSession = MediaSessionCompat(this, "AssistantCall").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS,
            )
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = noteCallButton()
                override fun onPause() = noteCallButton()
            })
        }
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
        registerTelephonyDefense()
        scope.launch {
            AssistantMemory.cloudConversationFlow(this@AssistantService).collect { cloudConversation = it }
        }
        scope.launch {
            AssistantPreferences.localThinkingFlow(this@AssistantService)
                .collect { localThinking = it }
        }
        scope.launch {
            AssistantPreferences.drivingModeFlow(this@AssistantService).collect {
                manualDrivingMode = it
                refreshDrivingMode()
            }
        }
        scope.launch {
            AssistantPreferences.autoDrivingModeFlow(this@AssistantService).collect {
                autoDrivingMode = it
                refreshBluetoothDriving()
            }
        }
        scope.launch {
            AssistantPreferences.pronunciationsFlow(this@AssistantService)
                .collect { Pronunciation.userEntries = it }
        }
        scope.launch {
            AssistantPreferences.personaToneFlow(this@AssistantService)
                .collect { personaTone = it }
        }
        scope.launch {
            AssistantPreferences.personaHumourFlow(this@AssistantService)
                .collect { personaHumour = it }
        }
        scope.launch {
            AssistantPreferences.personaLengthFlow(this@AssistantService)
                .collect { personaLength = it }
        }
        scope.launch {
            AssistantPreferences.onlineVoiceFlow(this@AssistantService).collect { onlineVoice = it }
        }
        scope.launch {
            AssistantPreferences.pickupFlow(this@AssistantService).collect { pickupGreeting = it }
        }
        scope.launch {
            AssistantPreferences.followUpFlow(this@AssistantService).collect { askFollowUp = it }
        }
        scope.launch {
            AssistantPreferences.asrEngineFlow(this@AssistantService).collect { selectedEngine = it }
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
            // The summary card is about this call only.
            CallLedger.clear()
            // Checked per call: unplugging between calls re-enables the cloud.
            checkBattery()
            // A new call must not inherit a reply from the last one.
            speaker.stopPlayback()
            cloudVoice?.stop()
            _speaking.value = false
            // Quick resume: with both models already warm the call connects
            // straight away, no dial tone needed. Cold calls ring out while
            // they load instead of showing a loading spinner.
            val warm = runCatching { selectedEngineWarm() }.getOrDefault(false)
            CrashLog.note(this, TAG, if (warm) "quick resume: models warm" else "cold start")
            _dialling.value = !warm
            if (!warm) ringback.start()
        }
        _callActive.value = true
        callActiveNow = true
        mediaSession?.isActive = true
        // Pick the hands-free microphone now: in the Vivaro this is the head
        // unit's HFP mic, on site it is the earphones, and only when neither
        // exists does the phone's own bottom mic stay in play.
        routeCallAudio()
        watchAudioDevices()
        refreshBluetoothDriving()
        startAudioRouteWatch()
        startInForeground(if (fresh) CALLING_TEXT else LISTENING_TEXT)
        scope.launch { AssistantPreferences.markUsed(this@AssistantService) }
        // A fresh call resets the dead-air clock; the watcher itself starts
        // once the greeting is done and the microphone is open.
        lastHeardAt = SystemClock.elapsedRealtime()
        silencePrompts = 0
        // Rejoining an existing call just reopens the microphone.
        if (!fresh) scope.launch { ensureListening() }
        scope.launch(Dispatchers.Default) {
            try {
                val callVoice = runCatching { AssistantPreferences.voiceFlow(this@AssistantService).first() }
                    .getOrDefault(voice)
                val callSpeed = runCatching { AssistantPreferences.speedFlow(this@AssistantService).first() }
                    .getOrDefault(speed)
                voice = callVoice
                speed = callSpeed
                // The recogniser is the risky allocation: 660 MB for Nemotron,
                // and a native OOM there kills the process without a Java
                // crash. Decide here, log it, then load voice first so the two
                // never peak together.
                applyMemoryGuard()
                speaker.warmUp()
                runCatching { speaker.prepareFiller(callVoice.speakerId, callSpeed) }
                CrashLog.note(this@AssistantService, TAG, "init: voice ready")
                runCatching { speech.prepare(forCall = true) }
                CrashLog.note(this@AssistantService, TAG, "init: recogniser step done")
                if (fresh) {
                    connectCall(callVoice.speakerId, callSpeed)
                    // Now the line is connected, finish warming the filler pool
                    // in the background; nothing waits on it.
                    if (cloudConversation && _callActive.value) {
                        runCatching { speaker.prepareRemainingFillers(callVoice.speakerId, callSpeed) }
                    }
                }
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (error: Throwable) {
                CrashLog.record(
                    this@AssistantService,
                    error as? Exception ?: Exception(error),
                    "assistant init",
                )
                runCatching { endCall() }
            }
        }
        return START_NOT_STICKY
    }

    /**
     * Log what the machine can afford, then pick the engine for this call.
     * Nemotron's 660 MB model is loaded by native code: if the allocation
     * fails the process aborts and no crash report can be written, so the
     * only defence is not to attempt it on a tight phone.
     */
    private fun applyMemoryGuard() {
        val manager = getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo().also { manager?.getMemoryInfo(it) }
        val availMb = info.availMem / (1024L * 1024L)
        val lowRam = manager?.isLowRamDevice == true
        val preferred = selectedEngine
        val moonshineInstalled = MoonshineDownloader.isInstalled(this)
        val nemotronInstalled = NemotronDownloader.isInstalled(this)
        val nemotronFailures = NemotronDownloader.loadFailures(this)
        val tight = lowRam || info.lowMemory || availMb < NEMOTRON_MIN_FREE_MB
        // Retired after two native deaths beats a memory check: it has to
        // survive a reboot with plenty of RAM free.
        val retired = preferred == AsrEngine.Nemotron && nemotronFailures >= 2
        val memoryTight = preferred == AsrEngine.Nemotron && tight && nemotronInstalled
        val effective = if (retired || memoryTight) AsrEngine.Moonshine else preferred
        speech.engineOverride = effective.takeIf { it != preferred }
        CrashLog.note(
            this,
            TAG,
            "init: sdk=${Build.VERSION.SDK_INT} avail=${availMb}MB lowRam=$lowRam " +
                "lowMemory=${info.lowMemory} engine=$preferred→$effective " +
                "(moonshine=$moonshineInstalled nemotron=$nemotronInstalled " +
                "nemotronFailures=$nemotronFailures)",
        )
        if (retired) {
            CrashLog.note(this, TAG, "init: Nemotron retired after native crashes; using the smaller engine")
            setNotification("Offline engine — Nemotron failed on this phone")
        } else if (memoryTight) {
            CrashLog.note(
                this,
                TAG,
                "init: Nemotron needs ${NEMOTRON_MIN_FREE_MB}MB free; using the smaller engine",
            )
            setNotification("Offline engine — not enough memory for Nemotron")
        }
    }

    /**
     * Finish dialling: stop the ringback, answer with today's schedule briefing
     * (or the cached hello when there is no data), then open the microphone.
     *
     * The microphone is deliberately opened *after* the greeting. Starting
     * AudioRecord just before playback can duck or reroute the speaker on some
     * phones, which is why a greeting that reported as spoken could be
     * inaudible. Greeting first also means the assistant cannot hear itself.
     */
    private suspend fun connectCall(speakerId: Int, speed: Float) {
        if (!_callActive.value) {
            ringback.stop()
            _dialling.value = false
            return
        }
        // The models are ready: kill the ringback, buzz the pocket, and
        // speak — no gap between the tone dying and the first word.
        ringback.stop()
        _dialling.value = false
        buzzConnected()

        val wantsGreeting = runCatching {
            AssistantPreferences.pickupFlow(this@AssistantService).first()
        }.getOrDefault(pickupGreeting)
        if (wantsGreeting && _callActive.value) {
            // A person answers the phone with a hello, not a diary read-out.
            // The schedule is still one question away ("what's on today?").
            val greeting = PICKUP_TEXT
            runCatching { AssistantMemory.noteCall(this@AssistantService) }
            AssistantMemory.lastSpokenReply = greeting
            CrashLog.note(this, TAG, "pickup greeting: $greeting")
            val started = System.currentTimeMillis()
            // Show it in the conversation immediately, even if the speaker is
            // muted or the clip fails.
            _turns.value = (_turns.value + AssistantTurn(fromUser = false, text = greeting))
                .takeLast(MAX_TURNS)
            // If a screen already opened the mic, suspend it for the greeting;
            // the speakerphone otherwise transcribes the assistant itself.
            val wasListening = speech.isListening()
            if (wasListening) speech.pauseForSpeech()
            _speaking.value = true
            val focused = requestAudioFocus()
            val spoken = try {
                // Let the ringback track release and the route settle before
                // the first syllable.
                delay(150)
                // Long enough for a Phonon hiccup and the local fallback to
                // load; the drain in PhononSpeech caps its own wait.
                withTimeoutOrNull(8_000) {
                    speaker.speakPickup(speakerId, speed)
                } ?: false
            } catch (error: Exception) {
                CrashLog.note(this, TAG, "pickup failed: ${error.message}")
                false
            } finally {
                if (focused) abandonAudioFocus()
                _speaking.value = false
                if (wasListening) speech.resumeAfterSpeech()
            }
            CrashLog.note(
                this,
                TAG,
                "pickup spoken=$spoken in ${System.currentTimeMillis() - started}ms",
            )
        } else {
            CrashLog.note(this, TAG, "pickup skipped: greeting is off")
        }

        if (!_callActive.value) return
        speech.startListening()
        setNotification(LISTENING_TEXT)
        startSilenceWatch()
        // Warm the local model after the line is open, not at app startup.
        // The first question then does not pay the 10-12 s engine load.
        scope.launch {
            if (localThinking && LocalLlm.isInstalled(this@AssistantService)) {
                LocalLlm.warm(this@AssistantService)
            }
        }
    }

    /**
     * Six seconds of open microphone with no voice is a person thinking, not
     * a fault. Say something light once, twice at most, and keep the line up.
     * Paused while the assistant speaks, thinks, or a question is pending.
     */
    private fun startSilenceWatch() {
        silenceJob?.cancel()
        lastHeardAt = SystemClock.elapsedRealtime()
        silencePrompts = 0
        micHeardAnything = false
        hearHintGiven = false
        silenceJob = scope.launch {
            while (_callActive.value) {
                delay(1_000)
                if (!_callActive.value) break
                val now = SystemClock.elapsedRealtime()
                val pending = actions.wrapUpActive() || actions.sendPending() ||
                    actions.chasePending() || actions.clarificationPending() ||
                    actions.readAllPending()
                if (_speaking.value || _thinking.value || _dialling.value || pending ||
                    !speech.isListening()
                ) {
                    lastHeardAt = now
                    continue
                }
                val state = speech.state.value
                if (state.level > 0.06f || state.partial.isNotBlank()) {
                    if (state.level > 0.08f) micHeardAnything = true
                    // A voice, a cough, anything: the clock is theirs again.
                    lastHeardAt = now
                    silencePrompts = 0
                    continue
                }
                // Twenty seconds of a microphone that has never carried any
                // real audio: it is not "take your time", it is "I can't hear
                // you" — and that is worth saying once, with the fix.
                if (!micHeardAnything && !hearHintGiven && now - lastHeardAt > 20_000) {
                    hearHintGiven = true
                    setNotification("No microphone input — check Assistant settings")
                    val wasListeningNow = speech.isListening()
                    if (wasListeningNow) speech.pauseForSpeech()
                    say(
                        "I'm not getting any sound from the microphone. Check the mic " +
                            "in the Assistant settings, or that the call is not held by " +
                            "another app.",
                    )
                    if (wasListeningNow) speech.resumeAfterSpeech()
                    lastHeardAt = SystemClock.elapsedRealtime()
                    continue
                }
                if (!micHeardAnything) continue
                if (now - lastHeardAt < SILENCE_PROMPT_MS) continue
                if (silencePrompts >= SILENCE_PROMPTS.size) continue
                val wasListening = speech.isListening()
                if (wasListening) speech.pauseForSpeech()
                say(SILENCE_PROMPTS[silencePrompts % SILENCE_PROMPTS.size])
                silencePrompts++
                if (wasListening) speech.resumeAfterSpeech()
                lastHeardAt = SystemClock.elapsedRealtime()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /** Start listening if this is the first bind or the loop stopped. */
    fun ensureListening() {
        if (_muted.value) return
        // The greeting and replies are played through the loudspeaker; opening
        // the mic then feeds them straight back as a user turn.
        if (_speaking.value || _thinking.value || _dialling.value) return
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
    /**
     * The fast path for an interjection: the audio is already dead, the word
     * is the answer, and the next reply starts immediately.
     */
    private suspend fun handleInterjection(text: String) {
        CrashLog.note(this, TAG, "interjection handled: ${text.take(60)}")
        _dialling.value = false
        speaker.stopPlayback()
        cloudVoice?.stop()
        _speaking.value = false
        _thinking.value = false
        if (!_callActive.value) return
        addTurn(AssistantTurn(fromUser = true, text = text))
        val heard = SpeechNormalizer.normalize(text)
        val sent = runCatching { actions.continueSend(heard) }
            .onFailure { CrashLog.note(this, TAG, "send reply failed: ${it.message}") }
            .getOrNull()
        val chased = sent ?: runCatching { actions.continueChase(heard) }
            .onFailure { CrashLog.note(this, TAG, "chase reply failed: ${it.message}") }
            .getOrNull()
        val clarified = chased ?: runCatching { actions.continueClarification(heard) }
            .onFailure { CrashLog.note(this, TAG, "clarification failed: ${it.message}") }
            .getOrNull()
        val readAll = clarified ?: runCatching { actions.continueReadAll(heard) }
            .onFailure { CrashLog.note(this, TAG, "read-all reply failed: ${it.message}") }
            .getOrNull()
        val reply = readAll ?: runCatching { actions.continueWrapUp(heard) }
            .onFailure { CrashLog.note(this, TAG, "wrap-up failed: ${it.message}") }
            .getOrNull()
        if (reply != null) {
            if (!actions.wrapUpActive()) raiseDraftInvoices()
            sayAnswer(reply)
        } else {
            // Something else entirely — treat it as a fresh command.
            answerTurn(heard)
        }
    }

    // --- battery-aware routing --------------------------------------------

    /**
     * Under 15% and unplugged, the cellular radio is the biggest drain left:
     * switch to offline-only for this call and say so in the greeting.
     */
    private fun checkBattery() {
        lowBattery = runCatching {
            val battery = getSystemService(BatteryManager::class.java)
                ?: return@runCatching false
            val level = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            !battery.isCharging && level in 1..14
        }.getOrDefault(false)
        CrashLog.note(this, TAG, "battery saver=$lowBattery")
    }

    /** VibrationEffect only exists from Oreo; older devices just skip it. */
    private fun vibrator(): android.os.Vibrator? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                getSystemService(VibratorManager::class.java)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(Vibrator::class.java)
            }
        }.getOrNull()
    }

    /** One short buzz: the line is live, pocket-friendly confirmation. */
    private fun buzzConnected() {
        runCatching {
            vibrator()?.vibrate(
                VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE),
            )
        }
    }

    /** Two short pulses: the audio route moved under your feet. */
    private fun buzzAudioChanged() {
        runCatching {
            vibrator()?.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 70, 90, 70), -1),
            )
        }
    }

    // --- GSM telephony defence --------------------------------------------

    private var telephonyCallback: Any? = null
    private var phoneStateListener: Any? = null

    /**
     * A real phone call outranks the assistant every time. When a GSM call
     * starts ringing or goes off-hook, stand the assistant down at once:
     * kill the voice, free the microphone, flush anything pending into the
     * CRM, and end the AI call so the phone's own UI has the road.
     *
     * Needs READ_PHONE_STATE; without it the defence is simply inactive and
     * the app behaves exactly as before.
     */
    private fun registerTelephonyDefense() {
        val granted = runCatching {
            checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE)
        }.getOrDefault(PackageManager.PERMISSION_DENIED) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            CrashLog.note(this, TAG, "telephony defence inactive: READ_PHONE_STATE not granted")
            return
        }
        val telephony = getSystemService(TelephonyManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) {
                    onGsmCallStateChanged(state)
                }
            }
            runCatching { telephony.registerTelephonyCallback(mainExecutor, callback) }
                .onSuccess { telephonyCallback = callback }
        } else {
            @Suppress("DEPRECATION")
            val listener = object : PhoneStateListener() {
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    onGsmCallStateChanged(state)
                }
            }
            runCatching { telephony.listen(listener, PhoneStateListener.LISTEN_CALL_STATE) }
                .onSuccess { phoneStateListener = listener }
        }
    }

    private fun unregisterTelephonyDefense() {
        val telephony = getSystemService(TelephonyManager::class.java) ?: return
        (telephonyCallback as? TelephonyCallback)?.let {
            runCatching { telephony.unregisterTelephonyCallback(it) }
        }
        telephonyCallback = null
        phoneStateListener?.let { listener ->
            @Suppress("DEPRECATION")
            runCatching { telephony.listen(listener as PhoneStateListener, PhoneStateListener.LISTEN_NONE) }
        }
        phoneStateListener = null
    }

    private fun onGsmCallStateChanged(state: Int) {
        if (state != TelephonyManager.CALL_STATE_RINGING &&
            state != TelephonyManager.CALL_STATE_OFFHOOK
        ) {
            return
        }
        if (!callActiveNow) return
        CrashLog.note(this, TAG, "GSM call state=$state — standing the assistant down")
        scope.launch {
            // Money first: anything the wrap-up logged goes out as drafts.
            raiseDraftInvoices()
            actions.clearWrapUp()
            endCall()
        }
    }

    private suspend fun say(text: String) {
        if (!_callActive.value) return
        // Wait for a filler that has already started: the online voice does
        // not share Kokoro's audio lock, so it could otherwise talk over it.
        thinkingMask?.cancelAndJoin()
        thinkingMask = null
        // Samsung can mute assistant output while AudioRecord is open. Close
        // the mic before speech; the caller/finally reopens listening.
        if (speech.isListening()) speech.stopListening()
        // The pace marker is runner-internal: keep it for the TTS layer,
        // strip it before anything user-visible.
        val stripped = TtsProsody.extract(text).second.trim()
        if (stripped.isEmpty()) return
        // A question with an answer pending (wrap-up, "send it") arms the
        // recogniser to catch a spoken-over answer and skips the thinking
        // pause when it arrives.
        speech.captureInterjections = actions.wrapUpActive() || actions.sendPending() ||
            actions.chasePending() || actions.clarificationPending() ||
            actions.readAllPending() || actions.hasPending()
        speech.fastPath = speech.captureInterjections
        AssistantMemory.lastSpokenReply = stripped
        _thinking.value = false
        addTurn(AssistantTurn(fromUser = false, text = stripped))
        // Hold audio focus only for the utterance: the call itself can stay
        // connected across tabs without pausing the van's music all day.
        val focused = requestAudioFocus()
        try {
            CrashLog.note(this, TAG, "reply voice=${if (onlineVoice) "online" else "device"}")
            // The natural online voice is optional; any failure falls back to
            // Kokoro rather than leaving the assistant silent.
            val spokenOnline = onlineVoice && !lowBattery &&
                runCatching { onlineSpeaker().speak(stripped) }.getOrDefault(false)
            CrashLog.note(this, TAG, "reply speech starting: ${stripped.take(80)}")
            if (!spokenOnline) {
                // Short confirmations brisk; anything with a figure or money
                // a shade slower so it is heard once.
                val naturalPace = when {
                    stripped.length < 50 -> 1.04f
                    Regex("\\d").containsMatchIn(stripped) -> 0.96f
                    else -> 1f
                }
                val drivingPace = if (drivingMode) 0.94f else 1f
                val personaPace = when (personaTone) {
                    PersonaTone.Brisk -> 1.04f
                    PersonaTone.Warm -> 0.98f
                }
                speaker.speakStreaming(
                    text.trim(),
                    voice.speakerId,
                    speed * naturalPace * drivingPace * personaPace,
                )
            }
        } finally {
            if (focused) abandonAudioFocus()
            CrashLog.note(this, TAG, "reply speech finished")
            if (_callActive.value && !_muted.value && !speech.isListening()) {
                speech.startListening()
            }
        }
    }

    /**
     * Play a cached "right, let me look" after a beat of genuine work. The
     * caller keeps running; the clip is queued on the voice lock, so the real
     * answer follows it without overlapping.
     */
    private fun startThinkingMask() {
        thinkingMask?.cancel()
        thinkingMask = scope.launch {
            // A beat first: a local rule answer should not be preceded by
            // "let me check" when it is already ready.
            delay(220)
            var rounds = 0
            while (_thinking.value && _callActive.value) {
                CrashLog.note(this@AssistantService, TAG, "thinking filler ${rounds + 1}")
                val fillerStarted = System.currentTimeMillis()
                // Samsung can mute assistant output while AudioRecord is open.
                // Close the microphone for the clip; the answer/finally reopens.
                if (speech.isListening()) speech.stopListening()
                // Fillers used to play without audio focus, unlike greetings
                // and replies. If another app or the car held focus the clip
                // could render to nothing while Phonon still reported samples.
                val fillerFocus = requestAudioFocus()
                try {
                    runCatching { speaker.speakFiller(voice.speakerId, speed) }
                } finally {
                    if (fillerFocus) abandonAudioFocus()
                }
                CrashLog.note(
                    this@AssistantService,
                    TAG,
                    "thinking filler ${rounds + 1} done in " +
                        "${System.currentTimeMillis() - fillerStarted}ms",
                )
                if (!_thinking.value || !_callActive.value) break
                rounds++
                // Keep the line alive while a 0.5B model loads or a cloud
                // turn thinks. Short gaps early, longer ones later, so it
                // sounds like thinking rather than a chatter loop.
                delay(if (rounds < 2) 450L else 1_300L)
            }
        }
    }

    /**
     * Speak a finished answer, optionally nudging the conversation along.
     * A reply that already ends in a question needs no prompt, and help or
     * small talk is left alone so it does not feel like a script.
     */
    private suspend fun sayAnswer(text: String) {
        // A short beat reads as listening rather than parsing.
        if (!speech.fastPath) delay(180)
        val lead = correctionLead
        correctionLead = null
        val clean = text.trim().let { if (lead != null) "$lead$it" else it }
        if (clean.isNotEmpty() && askFollowUp && !drivingMode &&
            personaLength == PersonaLength.Normal && !clean.endsWith("?")
        ) {
            val nudge = VoiceLines.pick(
                "Right — what next?",
                "Go on.",
                "What else can I do?",
                "Anything else while I'm here?",
            )
            say("$clean $nudge")
        } else {
            say(clean)
        }
    }

    private fun onlineSpeaker(): CloudVoice =
        cloudVoice ?: CloudVoice(this).also { cloudVoice = it }

    private fun addTurn(turn: AssistantTurn) {
        _turns.value = (_turns.value + turn).takeLast(MAX_TURNS)
        scope.launch { AssistantMemory.appendTurn(this@AssistantService, turn) }
    }

    /**
     * Turn a finished wrap-up into one draft invoice per client. The visits
     * are already saved and unbilled, so an offline failure costs nothing:
     * they stay in "ready to bill" and can be raised from the Invoices screen.
     */
    private fun raiseDraftInvoices() {
        val billables = actions.takeWrapUpBillables()
        if (billables.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            // The invoice function bills real visit rows, so the outbox has
            // to reach the server first. Offline this is a no-op and the
            // visits wait in "ready to bill" on the Invoices screen.
            val sync = runCatching { ServiceLocator.syncEngine.syncNow() }.getOrNull()
            if (sync == null || sync.offline) {
                CrashLog.note(
                    this@AssistantService,
                    TAG,
                    "wrap-up invoices deferred: ${sync?.error ?: "offline"}",
                )
                setNotification("Visits logged — ready to invoice")
                return@launch
            }
            val raised = runCatching { actions.raiseDraftInvoices(billables) }
                .onFailure {
                    CrashLog.note(this@AssistantService, TAG, "invoice attempt failed: ${it.message}")
                }
                .getOrDefault(0)
            CrashLog.note(
                this@AssistantService,
                TAG,
                "wrap-up invoices: $raised of ${billables.size} clients",
            )
            if (raised > 0) {
                setNotification(
                    if (raised == 1) "Draft invoice ready" else "$raised draft invoices ready",
                )
            }
        }
    }

    /**
     * The "send it" half of a voice-raised draft: build the same PDF the
     * Invoices screen shares, hand it to the email function, and report what
     * happened. Every failure leaves the invoice in drafts.
     */
    private suspend fun sendInvoiceByVoice(invoiceId: String, message: String? = null): String =
        runCatching {
        withContext(Dispatchers.IO) { ServiceLocator.syncEngine.syncNow() }
        val repository = ServiceLocator.crmRepository
        val invoice = repository.observeInvoices().first().firstOrNull { it.id == invoiceId }
            ?: return@runCatching "That draft isn't here any more."
        val client = repository.observeClients().first().firstOrNull { it.id == invoice.clientId }
            ?: return@runCatching "That invoice's client is missing."
        val pdf = withContext(Dispatchers.IO) {
            InvoicePdf.build(
                this@AssistantService,
                invoice,
                client,
                repository.invoiceItems(invoiceId),
                repository.getSettings(),
            )
        }
        val base64 = withContext(Dispatchers.IO) {
            Base64.encodeToString(pdf.readBytes(), Base64.NO_WRAP)
        }
        val result = repository.sendInvoice(invoiceId, base64, message = message)
        if (result.sent) {
            // Sent money cannot be un-sent; the card row stays, undoless.
            CallLedger.record("Invoice emailed to ${result.to ?: client.name}")
            "Sent to ${result.to ?: client.name}."
        } else {
            result.reason ?: "Email isn't set up yet, so it's still in drafts."
        }
    }.getOrElse { "I couldn't send it: ${it.message ?: "unknown error"}." }

    /** Share the invoice PDF plus drafted message through WhatsApp. */
    private suspend fun shareInvoiceWhatsAppByVoice(
        invoiceId: String,
        message: String?,
    ): String = runCatching {
        val repository = ServiceLocator.crmRepository
        val invoice = repository.observeInvoices().first().firstOrNull { it.id == invoiceId }
            ?: return@runCatching "That draft isn't here any more."
        val client = repository.observeClients().first().firstOrNull { it.id == invoice.clientId }
            ?: return@runCatching "That invoice's client is missing."
        val pdf = withContext(Dispatchers.IO) {
            InvoicePdf.build(
                this@AssistantService,
                invoice,
                client,
                repository.invoiceItems(invoiceId),
                repository.getSettings(),
            )
        }
        val text = message?.takeIf { it.isNotBlank() } ?: run {
            "Hi ${client.name.substringBefore(' ')}, here's invoice ${invoice.number} " +
                "for ${money(invoice.total)}. Thanks, ${VoiceLines.OWNER_NAME}."
        }
        withContext(Dispatchers.Main) {
            PdfSharing.shareViaWhatsApp(this@AssistantService, pdf, text)
        }
        "Opened WhatsApp — choose ${client.name}'s chat and send."
    }.getOrElse { "I couldn't open WhatsApp: ${it.message ?: "unknown error"}." }

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
    /**
     * The head-unit and headset buttons arrive as play/pause to the active
     * session. A single press is ignored (it may be music elsewhere), a
     * double press within the window ends the call.
     */
    private fun noteCallButton() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastCallButtonAt <= CALL_BUTTON_DOUBLE_MS) {
            lastCallButtonAt = 0
            CrashLog.note(this, TAG, "call button double-press")
            if (_callActive.value) endCall()
        } else {
            lastCallButtonAt = now
        }
    }

    fun endCall() {
        CrashLog.note(this, TAG, "call ended")
        silenceJob?.cancel()
        silenceJob = null
        // The next call decides its engine afresh from the owner's setting.
        speech.engineOverride = null
        _callActive.value = false
        callActiveNow = false
        mediaSession?.isActive = false
        _dialling.value = false
        _connectedAt.value = 0
        _muted.value = false
        actions.clearWrapUp()
        setSpeaker(false)
        ringback.stop()
        speaker.stopPlayback()
        cloudVoice?.stop()
        _speaking.value = false
        speech.stopListening()
        abandonAudioFocus()
        releaseCallAudio()
        runCatching { ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE) }
        foreground = false
        val summary = CallLedger.actions.value.map { it.label }.take(6).joinToString("; ")
        if (summary.isNotBlank()) {
            val app = applicationContext
            Thread({
                kotlinx.coroutines.runBlocking { AssistantMemory.setLastCall(app, summary) }
            }, "call-memory").start()
        }
        stopSelf()
    }

    /**
     * Mute the microphone for this call. The recogniser loop is stopped
     * rather than ignored, so nothing is decoded; unmuting reopens the mic.
     */
    fun setMuted(muted: Boolean) {
        if (_muted.value == muted) return
        _muted.value = muted
        CrashLog.note(this, TAG, if (muted) "mic muted" else "mic unmuted")
        if (muted) speech.stopListening() else ensureListening()
    }

    /** Route to the loudspeaker, best effort; some devices ignore the request. */
    fun setSpeaker(on: Boolean) {
        if (_speakerOn.value == on) return
        _speakerOn.value = on
        if (on) {
            val manager = getSystemService(AudioManager::class.java) ?: return
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val speaker = manager.availableCommunicationDevices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    }
                    if (speaker != null) manager.setCommunicationDevice(speaker)
                } else {
                    @Suppress("DEPRECATION")
                    manager.setSpeakerphoneOn(on)
                }
            }
        } else if (_callActive.value) {
            // Back off the loudspeaker onto the best hands-free mic (van head
            // unit, earphones) rather than the phone's bottom mic.
            routeCallAudio()
        } else {
            // Call over: undo whatever the speakerphone toggle left behind.
            releaseCallAudio()
        }
        CrashLog.note(this, TAG, "speakerphone=$on")
    }

    /**
     * Send the call audio to a hands-free microphone, most specific first:
     * a BLE headset (EarFun and the like), the van's classic Bluetooth HFP
     * unit, USB, then a wired headset. Without this the VOICE_COMMUNICATION
     * capture often stays on the bottom mic sitting in a pocket or cradle.
     *
     * Setting a Bluetooth device also opens the SCO link, which flips the
     * head unit into two-way HFP mode so its dashboard end-call button works.
     * Best effort throughout: with no headset attached the platform default
     * (earpiece) is left alone.
     */
    private fun routeCallAudio() {
        // The loudspeaker is an explicit user choice; it wins until toggled off.
        if (_speakerOn.value) return
        val manager = getSystemService(AudioManager::class.java) ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val preferences = listOf(
                    AudioDeviceInfo.TYPE_BLE_HEADSET,
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                    AudioDeviceInfo.TYPE_USB_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                )
                val device = manager.availableCommunicationDevices
                    .firstOrNull { it.type in preferences }
                val current = manager.communicationDevice
                if (device != null) {
                    if (current?.type != device.type) {
                        manager.setCommunicationDevice(device)
                        CrashLog.note(this, TAG, "call audio → ${deviceLabel(device)}")
                    }
                } else if (
                    current != null &&
                    current.type in preferences
                ) {
                    // The routed headset vanished mid-call: fall back to the
                    // loudspeaker — still audible on the drive — and buzz so
                    // the owner knows the output moved. Guarded by the stale
                    // route so repeated device events cannot re-buzz.
                    val speaker = manager.availableCommunicationDevices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    }
                    if (speaker != null) manager.setCommunicationDevice(speaker) else {
                        manager.clearCommunicationDevice()
                    }
                    buzzAudioChanged()
                    CrashLog.note(this, TAG, "headset gone; call audio → loudspeaker")
                }
            } else {
                // Pre-S has no communication-device API. A wired headset is
                // already preferred by the platform, so only Bluetooth needs
                // forcing into two-way HFP mode.
                val sco = manager.getDevices(AudioManager.GET_DEVICES_INPUTS).any {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                }
                @Suppress("DEPRECATION")
                if (sco && !manager.isBluetoothScoOn) {
                    manager.startBluetoothSco()
                    manager.isBluetoothScoOn = true
                    CrashLog.note(this, TAG, "call audio → bluetooth sco (legacy)")
                } else if (!sco && manager.isBluetoothScoOn) {
                    // The headset dropped: loudspeaker, same as the S+ path.
                    manager.stopBluetoothSco()
                    manager.isBluetoothScoOn = false
                    manager.setSpeakerphoneOn(true)
                    buzzAudioChanged()
                    CrashLog.note(this, TAG, "headset gone; call audio → loudspeaker (legacy)")
                }
                // The legacy speakerphone flag must not outlive a handover
                // back to a headset mic.
                @Suppress("DEPRECATION")
                if (sco) manager.setSpeakerphoneOn(false)
            }
        }.onFailure { CrashLog.record(this, it, "routeCallAudio") }
    }

    /**
     * Follow plug and pair events for the length of the call: climbing into
     * the Vivaro, or taking the earphones out on site, re-routes without
     * dropping the line.
     */
    private fun watchAudioDevices() {
        if (deviceCallback != null) return
        val manager = getSystemService(AudioManager::class.java) ?: return
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                routeCallAudio()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                routeCallAudio()
            }
        }
        runCatching {
            manager.registerAudioDeviceCallback(callback, null)
            deviceCallback = callback
        }
    }

    /**
     * Bluetooth HFP can drop a few seconds after the call opens (the car
     * re-negotiates the link). Device callbacks fire once; this slow check
     * puts the hands-free route back while the owner has not chosen the
     * loudspeaker.
     */
    private fun startAudioRouteWatch() {
        audioRouteJob?.cancel()
        audioRouteJob = scope.launch {
            while (_callActive.value) {
                delay(2_500)
                if (!_callActive.value) break
                refreshBluetoothDriving()
                if (!_speakerOn.value) routeCallAudio()
            }
        }
    }

    /** Auto driving mode follows the car kit rather than a manual switch. */
    private fun refreshBluetoothDriving() {
        val manager = getSystemService(AudioManager::class.java)
        val types = manager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            ?.map { it.type }
            ?.toSet()
            ?: emptySet()
        val connected = AudioDeviceInfo.TYPE_BLUETOOTH_SCO in types ||
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP in types ||
            AudioDeviceInfo.TYPE_BLE_HEADSET in types ||
            manager?.communicationDevice?.type in setOf(
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
            )
        bluetoothDriving = connected
        refreshDrivingMode()
    }

    private fun refreshDrivingMode() {
        val effective = manualDrivingMode || (autoDrivingMode && bluetoothDriving)
        if (drivingMode == effective) return
        drivingMode = effective
        TtsGain.boost = if (effective) 1.12f else 1f
        CrashLog.note(
            this,
            TAG,
            "driving mode=${if (effective) "on" else "off"} " +
                "(manual=$manualDrivingMode auto=$autoDrivingMode bluetooth=$bluetoothDriving)",
        )
    }

    /** Undo [routeCallAudio] and [watchAudioDevices] when the call is over. */
    private fun releaseCallAudio() {
        audioRouteJob?.cancel()
        audioRouteJob = null
        deviceCallback?.let { callback ->
            runCatching { getSystemService(AudioManager::class.java)?.unregisterAudioDeviceCallback(callback) }
        }
        deviceCallback = null
        val manager = getSystemService(AudioManager::class.java) ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                manager.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                if (manager.isBluetoothScoOn) {
                    manager.stopBluetoothSco()
                    manager.isBluetoothScoOn = false
                }
                @Suppress("DEPRECATION")
                manager.setSpeakerphoneOn(false)
            }
        }
    }

    /** Short human label for a routing log line. */
    private fun deviceLabel(device: AudioDeviceInfo): String {
        val kind = when (device.type) {
            AudioDeviceInfo.TYPE_BLE_HEADSET -> "bluetooth le headset"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bluetooth headset"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "usb headset"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired headset"
            else -> "type ${device.type}"
        }
        val name = device.productName?.toString()?.trim().orEmpty()
        return if (name.isEmpty()) kind else "$kind ($name)"
    }

    /** True when the selected recogniser and the voice are already loaded. */
    private fun selectedEngineWarm(): Boolean {
        val voiceWarm = speaker.isWarm()
        return voiceWarm && when (selectedEngine) {
            AsrEngine.Moonshine -> speech.moonshineWarm()
            AsrEngine.Nemotron -> speech.nemotronWarm()
        }
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
            // Any voice at all resets the dead-air clock.
            lastHeardAt = SystemClock.elapsedRealtime()
            silencePrompts = 0
            addTurn(AssistantTurn(fromUser = true, text = text))
            // The transcript keeps what was heard, but every rule and action
            // sees the normalised form: digits instead of "three and a half",
            // "£180" instead of "one hundred and eighty quid", no spoken
            // lead-ins. This is the cheapest accuracy gain in the pipeline.
            val heard = SpeechNormalizer.normalize(text)
            correctionLead = if (
                Regex("^(actually|sorry[, ]+i mean|i mean|make that|scratch that,)")
                    .containsMatchIn(heard.lowercase())
            ) {
                VoiceLines.pick(
                    "Ah, my mistake — ",
                    "Right, my mistake — ",
                    "Sorry — ",
                )
            } else {
                null
            }
            runningLateMinutes(heard)?.let { minutes ->
                say(openRunningLateWhatsApp(minutes))
                return@collect
            }
            pendingWhatsApp?.let { client ->
                pendingWhatsApp = null
                if (heard.contains("cancel") || heard.contains("forget it")) {
                    say("Cancelled that message.")
                    return@collect
                }
                val opened = Messaging.whatsapp(this, client.phone.orEmpty(), text.trim())
                say(
                    if (opened) {
                        "Opened WhatsApp for ${client.name} — tap send."
                    } else {
                        "I don't have a mobile number for ${client.name}, so I couldn't open WhatsApp."
                    },
                )
                return@collect
            }
            whatsAppRecipient(heard)?.let { spokenName ->
                val clients = runCatching { ServiceLocator.crmRepository.observeClients().first() }
                    .getOrDefault(emptyList())
                val client = clients.firstOrNull {
                    it.name.equals(spokenName, ignoreCase = true) ||
                        it.name.contains(spokenName, ignoreCase = true)
                }
                if (client == null) {
                    say("I couldn't find a client called $spokenName.")
                } else {
                    pendingWhatsApp = client
                    say("What shall I say to ${client.name}?")
                }
                return@collect
            }
            if (waitRequested(heard)) {
                say("No rush — take your time.")
                return@collect
            }
            if (sameAgainRequested(heard)) {
                val previous = lastCommand
                if (previous != null) {
                    say("Same again — ")
                    answerTurn(previous)
                } else {
                    say("Same as what? I haven't run anything yet.")
                }
                return@collect
            }
            lastCommand = heard
            // A pending answer ("Yep", "Next", "send it") skips the thinking
            // pause: the reply goes straight to the already-warm voice.
            if (!speech.fastPath) _thinking.value = true
            // Coughs, "um", stray "the": silently dropped, microphone carries
            // on. Never while an answer is pending — "no" is a real answer.
            val pendingAnswer = actions.wrapUpActive() || actions.sendPending() ||
                actions.chasePending() || actions.clarificationPending() ||
                actions.readAllPending() || actions.hasPending()
            if (!pendingAnswer && SpeechNormalizer.isNoise(heard)) {
                CrashLog.note(this, TAG, "noise rejected: ${text.take(30)}")
                return@collect
            }
            // A short turn should answer immediately; only cover a wait that
            // is actually being felt. The clip plays on the voice thread while
            // the coroutine continues the lookup.
            if (!speech.fastPath && !pendingAnswer) startThinkingMask()
            // Interlock starts before the filler: from here until Kokoro's
            // last sample the microphone frames are discarded, so neither the
            // filler nor the confirmation can feed back into the recognizer.
            val wasListening = speech.isListening()
            if (wasListening) speech.pauseForSpeech()
            _speaking.value = true
            try {
                // Mark-done now commits immediately (the summary card and
                // "undo that" are the safety net), so there is no pending
                // confirmation to answer.
                // "Say send it" — the second half of raising a draft invoice.
                val sent = runCatching { actions.continueSend(heard) }
                    .onFailure { CrashLog.note(this, TAG, "send reply failed: ${it.message}") }
                    .getOrNull()
                if (sent != null) {
                    sayAnswer(sent)
                    return@collect
                }
                // "Want me to flag that?" — a chase note on the client.
                val chased = runCatching { actions.continueChase(heard) }
                    .onFailure { CrashLog.note(this, TAG, "chase reply failed: ${it.message}") }
                    .getOrNull()
                if (chased != null) {
                    sayAnswer(chased)
                    return@collect
                }
                // "Which one — the rectory or Smiths farm?" — the next
                // sentence picks the client and the original command runs.
                val clarified = runCatching { actions.continueClarification(heard) }
                    .onFailure { CrashLog.note(this, TAG, "clarification failed: ${it.message}") }
                    .getOrNull()
                if (clarified != null) {
                    sayAnswer(clarified)
                    return@collect
                }
                // "Want me to read them all?"
                val readAll = runCatching { actions.continueReadAll(heard) }
                    .onFailure { CrashLog.note(this, TAG, "read-all reply failed: ${it.message}") }
                    .getOrNull()
                if (readAll != null) {
                    sayAnswer(readAll)
                    return@collect
                }
                if (undoRequested(heard)) {
                    sayAnswer(actions.undoLast())
                    return@collect
                }
                val remembered = runCatching { memoryCommand(heard) }
                    .onFailure { CrashLog.note(this, TAG, "memory command failed: ${it.message}") }
                    .getOrNull()
                if (remembered != null) {
                    sayAnswer(remembered)
                    return@collect
                }
                // "Say again": replay the last spoken sentence verbatim, with
                // no database work and no thinking pause.
                if (repeatRequested(heard)) {
                    val last = AssistantMemory.lastSpokenReply
                    say(last ?: "I haven't said anything yet.")
                    return@collect
                }
                // End-of-day wrap-up owns the conversation while it runs: an
                // answer is hours, a yes, a skip or a stop, never a new command.
                val wrapped = runCatching { actions.continueWrapUp(heard) }
                    .onFailure { CrashLog.note(this, TAG, "wrap-up failed: ${it.message}") }
                    .getOrNull()
                if (wrapped != null) {
                    CrashLog.note(this, TAG, "wrap-up reply: ${wrapped.take(120)}")
                    if (!actions.wrapUpActive()) raiseDraftInvoices()
                    sayAnswer(wrapped)
                    return@collect
                }
                // A previous turn may have asked "How many hours?" — finish
                // that before treating this sentence as a new command.
                val followUp = runCatching { actions.continuePending(heard) }
                    .onFailure { CrashLog.note(this, TAG, "pending action failed: ${it.message}") }
                    .getOrNull()
                if (followUp != null) {
                    CrashLog.note(this, TAG, "pending reply: ${followUp.take(120)}")
                    sayAnswer(followUp)
                } else {
                    answerTurn(heard)
                }
            } catch (error: Exception) {
                Log.w(TAG, "Kokoro playback failed", error)
            } finally {
                thinkingMask?.cancel()
                thinkingMask = null
                correctionLead = null
                _thinking.value = false
                _speaking.value = false
                if (wasListening) speech.resumeAfterSpeech()
                // Open handoff: the line is never left closed after a reply.
                if (_callActive.value && !_muted.value && !speech.isListening()) {
                    speech.startListening()
                }
            }
        }
    }

    /** Classify one finished sentence and either act locally or ask the cloud. */
    private suspend fun answerTurn(text: String) {
        val started = System.currentTimeMillis()

        // "log four hours for Smiths, then mark Smiths done" is two actions.
        // Run the recognised parts in order and speak one combined reply. This
        // is free and local by design; nothing multi-step goes to the cloud.
        val lowerText = text.lowercase()
        if (lowerText.contains("search online") || lowerText.contains("search the web")) {
            val web = freeWeb.answer(text, SpokenIntent.Lookup)
            sayAnswer(
                web ?: "I can look things up, but I can't compare live shop prices. " +
                    "Name the product and I'll find what's known about it.",
            )
            return
        }
        if (
            quickIsVagueLook(lowerText)
        ) {
            sayAnswer("At what?")
            return
        }

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
                sayAnswer(replies.joinToString(" "))
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
        val dateIndependentIntents = setOf(
            SpokenIntent.CheckSchedule,
            SpokenIntent.AddNote,
            SpokenIntent.ClientList,
            SpokenIntent.Unbilled,
            SpokenIntent.InvoiceStatus,
            SpokenIntent.NextJobForClient,
            SpokenIntent.TodayEarnings,
            // "run through today's jobs" contains a date word but is always
            // about today, so it must not be pushed to the cloud for it.
            SpokenIntent.DayWrapUp,
            // Unbilled visits are read locally; the draft call itself is
            // server-side and safe to attempt from the rule path.
            SpokenIntent.RaiseInvoice,
            SpokenIntent.AdjustCharge,
        )
        val dateIndependent = quick.intent in dateIndependentIntents
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

        // 1.5 Semantic fallback: the rules are word-for-word, so British slang
        //     ("stick Simon down for a half day") slips past them. A weighted
        //     phrase match against a curated corpus routes the clear cases
        //     locally and instantly; anything ambiguous still goes to the
        //     cloud below.
        if (!quick.requiresCloud) {
            val routed = SemanticMatcher.classify(lower)
            val dateSafe = routed != null && (!temporal || routed.first in dateIndependentIntents)
            if (routed != null && dateSafe) {
                val score = "%.2f".format(routed.second)
                CrashLog.note(
                    this,
                    TAG,
                    "intent=semantic:${routed.first.wire} score=$score " +
                        "in ${System.currentTimeMillis() - started}ms",
                )
                runIntent(text, IntentResult(routed.first, quick.clientOrTopic, false))
                return
            }
        }

        // 2. Exactly one optional cloud call. The business snapshot is built
        //    only now, and only when a cloud call is actually allowed: a local
        //    answer never pays for the Room/DataStore reads.
        val cloudAllowed = cloudConversation && !lowBattery
        if (cloudAllowed) {
            val context = businessContext()
            // The function classifies and, for conversational turns, produces
            // the answer in the same response — so a sentence never costs two
            // requests and never waits for the removed local model.
            val reply = runCatching { cloud.ask(text, context, _turns.value.takeLast(6)) }
                .onFailure { CrashLog.note(this, TAG, "cloud failed: ${it.message}") }
                .getOrNull()

            if (reply != null) {
                if (reply.answer.isNotBlank()) {
                    CrashLog.note(
                        this,
                        TAG,
                        "cloud answered in ${System.currentTimeMillis() - started}ms",
                    )
                    sayAnswer(reply.answer)
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
        //
        // If the cloud was asked and could not be reached, the words are
        // still worth keeping: a dictated quote in a dead zone is exactly
        // what the queue exists for. Small talk is excluded, and anything
        // this short cannot be a job description.
        val chatter = if (quick.intent == SpokenIntent.Other) smallTalk(text) else null
        val wordCount = text.split(Regex("\\s+")).count { it.isNotBlank() }
        val memo = if (
            cloudConversation &&
            chatter == null &&
            wordCount >= 4 &&
            (quick.requiresCloud || quick.intent == SpokenIntent.Other)
        ) {
            runCatching { PendingVoiceStore.enqueue(this@AssistantService, text) }
                .onFailure { CrashLog.note(this@AssistantService, TAG, "voice queue failed: ${it.message}") }
                .getOrNull()
        } else {
            null
        }

        // Free local thinking: a 0.5B model, loaded only when a turn reaches
        // this fallback and closed again after a minute idle. It can reason
        // about short questions; live facts still prefer the free web APIs.
        if (
            quick.intent == SpokenIntent.Other &&
            chatter == null &&
            memo == null &&
            !lowBattery &&
            localThinking &&
            LocalLlm.isInstalled(this) &&
            looksLikeQuestion(text) &&
            !looksLikeFactualQuestion(text)
        ) {
            val answer = withTimeoutOrNull(25_000) {
                LocalLlm.generate(this@AssistantService, localPrompt(), text)
            }
            if (!answer.isNullOrBlank()) {
                CrashLog.note(this, TAG, "local answer: ${answer.take(120)}")
                sayAnswer(spokenLocalAnswer(answer))
                return
            }
        }

        if (quick.intent == SpokenIntent.Other && chatter == null && !looksLikeQuestion(text) &&
            text.split(Regex("\\s+")).size < 6
        ) {
            sayAnswer(
                VoiceLines.pick(
                    "Sorry, I didn't catch that — say it again?",
                    "I didn't quite get that one. Go on?",
                    "Say that once more for me?",
                ),
            )
            return
        }

        // Anything that looks like a general question gets a free Wikipedia/
        // instant-answer attempt before the help line. It is not an LLM, but it
        // covers facts, definitions and "how does X work" questions at no cost.
        if (
            quick.intent == SpokenIntent.Other &&
            chatter == null &&
            memo == null &&
            !lowBattery &&
            looksLikeQuestion(text)
        ) {
            val web = freeWeb.answer(text, SpokenIntent.Lookup)
            if (web != null) {
                sayAnswer(web)
                return
            }
        }

        when {
            quick.requiresCloud -> say(
                when {
                    memo != null -> savedMemoLine(memo.clientName)
                    lowBattery ->
                        "Battery's running low, so I'm offline only. I can't look that up."
                    cloudConversation ->
                        "I couldn't reach the online assistant. Try again when you have signal."
                    else -> "Online answers are switched off in settings."
                },
            )

            quick.intent == SpokenIntent.Other -> {
                say(
                    when {
                        chatter != null -> chatter
                        memo != null -> savedMemoLine(memo.clientName)
                        lowBattery ->
                            "Battery's running low, so I'm on the local answers only."
                        cloudConversation ->
                            "I couldn't reach the online assistant. Try again when you have signal."
                        else -> HELP_TEXT
                    },
                )
            }

            else -> runIntent(text, quick)
        }
    }

    /** Spoken confirmation for a memo kept for the reconnect worker. */
    private fun savedMemoLine(clientName: String?): String =
        if (clientName != null) {
            "No signal, so I've saved that note for $clientName — I'll process it as soon as " +
                "we're back on data."
        } else {
            "No signal, so I've saved that — I'll process it as soon as we're back on data."
        }

    /** Carry out a local intent and speak its result. */
    private suspend fun runIntent(text: String, intent: IntentResult) {
        if (intent.intent == SpokenIntent.Weather || intent.intent == SpokenIntent.Lookup) {
            val lookedUp = freeWeb.answer(text, intent.intent)
            if (lookedUp != null) {
                sayAnswer(lookedUp)
            } else {
                sayAnswer(
                    if (intent.intent == SpokenIntent.Weather) {
                        "I couldn't reach the forecast. Check your connection."
                    } else {
                        "I couldn't find that online. Check your connection."
                    },
                )
            }
            return
        }
        if (intent.intent == SpokenIntent.Continue) {
            val next = actions.continueReadAll("go on")
            if (next != null) {
                sayAnswer(next)
            } else {
                sayAnswer(VoiceLines.pick("Go on.", "I'm listening.", "Right — what next?"))
            }
            return
        }
        val executed = runCatching { actions.run(text, intent) }
            .onFailure { CrashLog.note(this, TAG, "action failed: ${it.message}") }
            .getOrNull()
        CrashLog.note(this, TAG, "action reply: ${executed ?: "none"}")
        sayAnswer(executed ?: confirmationFor(intent))
    }

    /**
     * Split a sentence into independent commands. "and then", "then", ";"
     * and — once punctuation is enabled — a full stop before a capital are
     * explicit. A plain "and" only splits when both sides look like a
     * command, so "Louisa and George" stays one client name.
     */
    private fun splitMultiCommand(text: String): List<String> {
        val strong = text.split(
            Regex(
                "\\b(?:and then|then)\\b|;|(?<=[.!?])\\s+(?=[A-Z])",
                RegexOption.IGNORE_CASE,
            ),
        )
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

    /** "Same again", "the usual": replay the previous command. */
    private fun sameAgainRequested(text: String): Boolean {
        val lower = text.lowercase().trim().trimEnd('.', '!', '?', ',')
        return lower in setOf(
            "same again", "same as last time", "do that again", "the usual",
            "same as before", "run that again",
        )
    }

    private fun quickIsVagueLook(lower: String): Boolean =
        Regex("^(can you |could you )?(have a look|take a look|look|check|check that)[?.!]*$")
            .matches(lower.trim())

    private suspend fun localPrompt(): String {
        // Keep the 0.6B context tight: facts only. Old turns were making it
        // answer a previous weather question instead of the current one.
        val facts = runCatching { AssistantMemory.facts(this).map { it.text } }
            .getOrDefault(emptyList())
            .take(8)
        val now = java.time.ZonedDateTime.now()
        val clock = now.format(java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM, HH:mm"))
        return """
            You are Josh's personal secretary for a UK gardening business, speaking
            on the phone. Be calm, warm and efficient, like a good British secretary.
            Use contractions. Answer only the question in one or two short spoken
            sentences, then offer a useful next step if it helps.
            Never call yourself an AI, voice assistant, chatbot, model or assistant
            programme. If asked how you are, answer like a person and ask how his day
            is going. Address him as Josh occasionally, not in every sentence.
            If you do not know, say so briefly and suggest what would help.
            It is $clock. Business facts: ${facts.joinToString("; ").ifBlank { "a UK gardening business" }}
            ${runCatching { AssistantMemory.lastCall(this) }.getOrNull()?.let { "Last call: $it" } ?: ""}
            ${personalityInstruction()}
        """.trimIndent()
    }

    private fun spokenLocalAnswer(text: String): String {
        val clean = text
            .replace(Regex("<\\|[^>]*\\|>"), "")
            .replace("\n", " ")
            .trim()
        val sentences = clean.split(Regex("(?<=[.!?])\\s+"))
            .filter { it.isNotBlank() }
            .take(2)
            .joinToString(" ")
        return sentences.ifBlank { clean }.take(360)
    }

    /** Weather/facts should go to the web APIs rather than a 0.5B model. */
    private fun looksLikeFactualQuestion(text: String): Boolean {
        val lower = text.lowercase()
        if (
            listOf(
                "chance", "likely", "will it", "do you think", "should i",
                "this winter", "this year", "next year",
            ).any { lower.contains(it) }
        ) {
            return false
        }
        return listOf("weather", "forecast", "who is", "what is", "where is", "how tall").any {
            lower.contains(it)
        }
    }

    /** A general question, as opposed to a command or a fragment. */
    private fun personalityInstruction(): String {
        val tone = when (personaTone) {
            PersonaTone.Warm -> "Speak warmly, with contractions and a little humanity."
            PersonaTone.Brisk -> "Be brisk and efficient; no filler or small talk."
        }
        val humour = when (personaHumour) {
            PersonaHumour.Low -> "No jokes or banter."
            PersonaHumour.Normal -> "A dry aside is fine occasionally."
            PersonaHumour.High -> "A playful, lightly teasing tone is welcome."
        }
        val length = when (personaLength) {
            PersonaLength.Short -> "Keep answers to one short sentence."
            PersonaLength.Normal -> "Keep answers to one or two short sentences."
        }
        return "$tone $humour $length"
    }

    private fun looksLikeQuestion(text: String): Boolean {
        val lower = text.lowercase().trim()
        return Regex(
            "^(who|what|when|where|why|how|which|is|are|does|do|can|could|" +
                "tell|say|give|have|has|will|would|should)\\b",
        )
            .containsMatchIn(lower) && lower.split(Regex("\\s+")).size >= 3
    }

    /** "I'm running late" / "20 minutes late" -> delay in minutes. */
    private fun runningLateMinutes(text: String): Int? {
        val lower = text.lowercase()
        if (!lower.contains("running late") && !lower.contains("late for")) return null
        val parsed = Regex("(\\d+)\\s*(?:minutes?|mins?)").find(lower)
            ?.groupValues?.get(1)?.toIntOrNull()
        return (parsed ?: 15).coerceIn(5, 120)
    }

    /** Open a ready-to-send WhatsApp apology to the next booked client. */
    private suspend fun openRunningLateWhatsApp(minutes: Int): String = runCatching {
        val repository = ServiceLocator.crmRepository
        val now = java.time.Instant.now()
        val jobs = repository.observeJobs().first()
            .filter { it.status == "scheduled" }
            .mapNotNull { job ->
                runCatching { java.time.Instant.parse(job.startsAt) }.getOrNull()?.let { job to it }
            }
            .filter { (_, starts) -> !starts.isBefore(now) }
            .sortedBy { (_, starts) -> starts }
        val next = jobs.firstOrNull() ?: return@runCatching "Nothing else is booked."
        val clients = repository.observeClients().first()
        val client = clients.firstOrNull { it.id == next.first.clientId }
            ?: return@runCatching "That job's client is missing."
        val phone = client.phone?.takeIf { it.isNotBlank() }
            ?: return@runCatching "I have no mobile number for ${client.name}."
        val firstName = client.name.substringBefore(' ')
        val message = "Hi $firstName, running about $minutes minutes late — see you shortly. " +
            "Thanks, ${VoiceLines.OWNER_NAME}."
        val opened = Messaging.whatsapp(this, phone, message)
        if (opened) {
            "Opened WhatsApp for ${client.name} — tap send."
        } else {
            "I couldn't open WhatsApp for ${client.name}."
        }
    }.getOrElse { "I couldn't open that message: ${it.message ?: "unknown error"}." }

    /** "Message Louisa on WhatsApp" -> Louisa; null for "send it on WhatsApp". */
    private fun whatsAppRecipient(text: String): String? {
        val lower = text.lowercase().trim().trimEnd('.', '!', '?', ',')
        if (lower.startsWith("send it") || lower.startsWith("send that")) return null
        val match = Regex(
            "^(?:send (?:a )?(?:whatsapp|message|text) to|whatsapp|message|text)\\s+(.+?)(?:\\s+on\\s+whatsapp)?$",
        ).find(lower) ?: return null
        val name = match.groupValues[1]
            .removeSuffix(" on whatsapp")
            .trim()
        if (name.isBlank() || name in setOf("it", "that", "this", "me", "him", "her")) return null
        return name
    }

    /** "Hang on", "one sec": keep the line open without treating it as a command. */
    private fun waitRequested(text: String): Boolean {
        val lower = text.lowercase().trim().trimEnd('.', '!', '?', ',')
        return lower in setOf(
            "hang on", "hang on a sec", "hang on a second", "hold on", "hold on a sec",
            "one sec", "one second", "just a sec", "just a second", "wait", "wait a sec",
            "wait a second", "give me a sec", "give me a second", "two secs", "back in a sec",
        )
    }

    /** "Say again", "repeat that", "pardon": replay the last sentence. */
    private fun repeatRequested(text: String): Boolean {
        val lower = text.lowercase().trim().trimEnd('.', '!', '?', ',')
        return lower in setOf(
            "say again", "say that again", "repeat that", "repeat it", "repeat",
            "what was that", "didn't catch that", "didnt catch that", "come again",
            "pardon", "say that again please",
        ) || lower.contains("didn't catch") || lower.contains("didnt catch")
    }

    /** A few free replies so greetings are not answered with a help list. */
    private fun smallTalk(text: String): String? {
        // Punctuated input ("No thanks.") must still match the plain forms.
        val lower = text.lowercase().trim().trimEnd('.', '!', '?', ',')
        return when {
            Regex("^(hi|hello|hey|morning|afternoon|evening|good morning|good afternoon|good evening)\\b")
                .containsMatchIn(lower) -> VoiceLines.pick(
                "Hello ${VoiceLines.OWNER_NAME} — what do you need?",
                "Hey — go on then.",
                "Morning — what are we doing?",
                "Ha — afternoon ${VoiceLines.OWNER_NAME}, what's next?",
            )
            Regex("\\b(you alright|you ok|how's it going|hows it going|how are you)\\b")
                .containsMatchIn(lower) -> VoiceLines.pick(
                "All good, thanks. How's your day going?",
                "Can't complain — how are you keeping?",
                "Not bad at all. What's next for you?",
            )
            Regex("^(good|not bad|i'm good|im good|fine|okay|ok)\\b.*(thanks|thank you)?$")
                .containsMatchIn(lower) -> VoiceLines.pick(
                "Glad to hear it. What's next?",
                "Good stuff. What are we doing?",
            )
            lower in setOf(
                "no", "no thanks", "nothing", "that's all", "thats all", "all done",
                "we're done", "were done", "finished", "no thank you", "nothing else",
            ) -> VoiceLines.pick(
                "Right, I'll leave you to it.",
                "No worries — I'm here if you need me.",
            )
            lower in setOf(
                "bye", "goodbye", "night", "good night", "see you", "speak later",
                "that's me done", "thats me done",
            ) -> VoiceLines.pick(
                "Right — talk later, ${VoiceLines.OWNER_NAME}.",
                "Go on then — I'll be here.",
            )
            lower.contains("thank") || lower in setOf("ta", "nice one", "cheers") ->
                VoiceLines.pick("Any time.", "No worries.", "Ha — pleasure.")
            lower.contains("what can you do") || lower == "help" -> HELP_TEXT
            lower.contains("busy") && lower.split(Regex("\\s+")).size <= 4 ->
                VoiceLines.pick(
                    "Busy enough. What's next?",
                    "Busy one, aye. What do you need?",
                )
            else -> null
        }
    }

    /** The business snapshot handed to the local and cloud models. */
    private suspend fun businessContext(): String {
        val facts = runCatching { AssistantMemory.facts(this).map { it.text } }.getOrDefault(emptyList())
        val base = assistantContext.snapshot(facts, _turns.value.takeLast(8))
        val last = runCatching { AssistantMemory.lastCall(this) }.getOrNull()
        return if (last.isNullOrBlank()) base else "$base\nLast call: $last"
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
        unregisterTelephonyDefense()
        CrashLog.note(this, TAG, "service destroyed")
        _callActive.value = false
        callActiveNow = false
        callActiveNow = false
        _dialling.value = false
        _connectedAt.value = 0
        _muted.value = false
        _speakerOn.value = false
        pendingWhatsApp = null
        bluetoothDriving = false
        drivingMode = false
        TtsGain.boost = 1f
        audioRouteJob?.cancel()
        audioRouteJob = null
        ringback.stop()
        // The local model has its own idle close. Closing it here races a
        // load/generate that is still finishing and produced "Engine is not
        // initialized" after the call ended.
        abandonAudioFocus()
        releaseCallAudio()
        runCatching { mediaSession?.release() }
        mediaSession = null
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
            .setContentTitle("Form & Foliage")
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
        const val CALL_BUTTON_DOUBLE_MS = 900L

        /** Nemotron is ~660 MB resident; below this much free RAM it is unsafe. */
        const val NEMOTRON_MIN_FREE_MB = 1_200L

        /** Six seconds of silence earns one light nudge, twice at most. */
        const val SILENCE_PROMPT_MS = 6_000L
        val SILENCE_PROMPTS = arrayOf(
            "Take your time…",
            "No rush — just shout when you're ready.",
        )
        const val CHECKING_ONLINE = "Checking online rates."
        const val CALLING_TEXT = "Assistant is dialling…"
        const val LISTENING_TEXT = "Assistant is listening for your next command"
        const val CLOUD_TAG = "CloudRouter"
        const val HELP_TEXT = "I can log hours, check your diary, mark a job done, add a client " +
            "note, read out invoices and takings, say run through today's jobs to wrap up the " +
            "day, or say undo to reverse the last change."
        val TEMPORAL_WORDS = listOf("week", "tomorrow", "month", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
        const val MAX_TURNS = 40

        /** True while a call is connected, for the background model release. */
        @Volatile
        var callActiveNow: Boolean = false

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
        SpokenIntent.InvoiceEdit -> "Updating that invoice."
        SpokenIntent.NextJobForClient ->
            if (topic.isEmpty()) "Checking that booking." else "Checking when $topic is next in."
        SpokenIntent.TodayEarnings -> "Checking today's takings."
        SpokenIntent.DayWrapUp -> "Running through today's jobs."
        SpokenIntent.RaiseInvoice -> "Drafting that invoice."
        SpokenIntent.AdjustCharge -> "Adjusting that visit."
        SpokenIntent.AddExpense -> "Adding that to the account."
        SpokenIntent.NextVisitNote -> "Making a note of that."
        SpokenIntent.SayAgain -> "Repeating that."
        SpokenIntent.MarkJobDone ->
            if (topic.isEmpty()) "Marking that job done." else "Marking the job for $topic done."
        SpokenIntent.CheckDebtors -> "Checking who owes you."
        SpokenIntent.WeeklyEarnings -> "Checking this week's earnings."
        SpokenIntent.Quote ->
            if (topic.isEmpty()) "Preparing your quote." else "Preparing a quote for $topic."
        SpokenIntent.Continue -> "Go on."
        SpokenIntent.Weather -> "Checking the forecast."
        SpokenIntent.Lookup -> "Looking that up."
        SpokenIntent.Other ->
            "I can log hours, check your diary, add a note, or tell you who owes you."
    }
}
