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
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.support.v4.media.session.MediaSessionCompat
import android.util.Base64
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
import uk.co.jwgardenservices.crm.data.repository.ServiceLocator
import uk.co.jwgardenservices.crm.data.repository.invoiceItems
import uk.co.jwgardenservices.crm.data.repository.sendInvoice
import uk.co.jwgardenservices.crm.ui.invoices.InvoicePdf

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
    private val actions by lazy {
        VoiceActionRunner(sendInvoice = { invoiceId -> sendInvoiceByVoice(invoiceId) })
    }

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    /** True while a heard sentence is being classified and acted on. */
    private val _thinking = MutableStateFlow(false)
    val thinking: StateFlow<Boolean> = _thinking.asStateFlow()

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
    val speakerOn: StateFlow<Boolean> = _speakerOn.asStateFlow()

    /** True from the green button until the line is ready and the assistant answers. */
    private val _dialling = MutableStateFlow(false)
    val dialling: StateFlow<Boolean> = _dialling.asStateFlow()

    private val _turns = MutableStateFlow<List<AssistantTurn>>(emptyList())
    val turns: StateFlow<List<AssistantTurn>> = _turns.asStateFlow()

    private var foreground = false
    private var cloudConversation = true
    private var onlineVoice = false
    private var cloudVoice: CloudVoice? = null
    // Lazy: the base context is only attached after construction, and the
    // profile asset is read once per process on first use.
    private val assistantContext by lazy {
        AssistantContext(businessProfile = BusinessBrain.load(this))
    }
    private val callBriefing = CallBriefing()

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
    private val ringback = VoiceRingback()
    private var audioManager: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null

    /** Re-routes the call when earphones or the van's head unit connect mid-call. */
    private var deviceCallback: AudioManager.AudioDeviceCallback? = null

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
        speech.onBargeIn = {
            CrashLog.note(this, TAG, "barge-in; stopping playback")
            speaker.stopPlayback()
            cloudVoice?.stop()
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
        scope.launch {
            AssistantMemory.cloudConversationFlow(this@AssistantService).collect { cloudConversation = it }
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
                launch { runCatching { speech.prepare(forCall = true) } }
                speaker.warmUp()
                runCatching { speaker.prepareFiller(callVoice.speakerId, callSpeed) }
            }
            if (fresh) {
                connectCall(callVoice.speakerId, callSpeed)
                // Now the line is connected, finish warming the filler pool in
                // the background; nothing waits on it.
                if (cloudConversation && _callActive.value) {
                    runCatching { speaker.prepareRemainingFillers(callVoice.speakerId, callSpeed) }
                }
            }
        }
        return START_NOT_STICKY
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
        ringback.stop()
        _dialling.value = false

        val wantsGreeting = runCatching {
            AssistantPreferences.pickupFlow(this@AssistantService).first()
        }.getOrDefault(pickupGreeting)
        if (wantsGreeting && _callActive.value) {
            // The briefing names today's first/active job, so the owner can
            // answer "log four hours and mark it done" without a client name.
            // It is read from Room only, and only after the models are warm.
            val briefing = callBriefing.line()
            val greeting = briefing ?: PICKUP_TEXT
            CrashLog.note(this, TAG, "pickup greeting: $greeting")
            val started = System.currentTimeMillis()
            // Show it in the conversation immediately, even if the speaker is
            // muted or the clip fails.
            _turns.value = (_turns.value + AssistantTurn(fromUser = false, text = greeting))
                .takeLast(MAX_TURNS)
            _speaking.value = true
            val focused = requestAudioFocus()
            val spoken = try {
                // Let the ringback track release and the route settle before
                // the first syllable.
                delay(150)
                // Long enough for a Phonon hiccup and the local fallback to
                // load; the drain in PhononSpeech caps its own wait.
                withTimeoutOrNull(12_000) {
                    if (briefing != null) {
                        // Streamed sentence by sentence; the first words start
                        // while the rest of the briefing is still generating.
                        speaker.speakStreaming(greeting, speakerId, speed)
                        true
                    } else {
                        speaker.speakPickup(speakerId, speed)
                    }
                } ?: false
            } catch (error: Exception) {
                CrashLog.note(this, TAG, "pickup failed: ${error.message}")
                false
            } finally {
                if (focused) abandonAudioFocus()
                _speaking.value = false
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
    }

    override fun onBind(intent: Intent?): IBinder = binder

    /** Start listening if this is the first bind or the loop stopped. */
    fun ensureListening() {
        if (_muted.value) return
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
        if (!_callActive.value) return
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

    /**
     * Speak a finished answer, optionally nudging the conversation along.
     * A reply that already ends in a question needs no prompt, and help or
     * small talk is left alone so it does not feel like a script.
     */
    private suspend fun sayAnswer(text: String) {
        val clean = text.trim()
        if (clean.isNotEmpty() && askFollowUp && !clean.endsWith("?")) {
            val nudge = VoiceLines.pick(
                "Anything else?",
                "Anything else I can do?",
                "What else?",
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
    private suspend fun sendInvoiceByVoice(invoiceId: String): String = runCatching {
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
        val result = repository.sendInvoice(invoiceId, base64)
        if (result.sent) {
            // Sent money cannot be un-sent; the card row stays, undoless.
            CallLedger.record("Invoice emailed to ${result.to ?: client.name}")
            "Sent to ${result.to ?: client.name}."
        } else {
            result.reason ?: "Email isn't set up yet, so it's still in drafts."
        }
    }.getOrElse { "I couldn't send it: ${it.message ?: "unknown error"}." }

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
                if (device != null) {
                    if (manager.communicationDevice?.type != device.type) {
                        manager.setCommunicationDevice(device)
                        CrashLog.note(this, TAG, "call audio → ${deviceLabel(device)}")
                    }
                } else if (manager.communicationDevice != null) {
                    // A headset vanished mid-call: drop back to the default
                    // route instead of holding a dead device.
                    manager.clearCommunicationDevice()
                    CrashLog.note(this, TAG, "call audio → default (headset gone)")
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
                }
                // The legacy speakerphone flag must not outlive a handover
                // back to a headset mic.
                @Suppress("DEPRECATION")
                manager.setSpeakerphoneOn(false)
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
        val callback = object : AudioManager.AudioDeviceCallback() {
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

    /** Undo [routeCallAudio] and [watchAudioDevices] when the call is over. */
    private fun releaseCallAudio() {
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
            addTurn(AssistantTurn(fromUser = true, text = text))
            // The transcript keeps what was heard, but every rule and action
            // sees the normalised form: digits instead of "three and a half",
            // "£180" instead of "one hundred and eighty quid", no spoken
            // lead-ins. This is the cheapest accuracy gain in the pipeline.
            val heard = SpeechNormalizer.normalize(text)
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
                val confirmed = runCatching { actions.answerConfirmation(heard) }
                    .onFailure { CrashLog.note(this, TAG, "confirmation failed: ${it.message}") }
                    .getOrNull()
                if (confirmed != null) {
                    sayAnswer(confirmed)
                    return@collect
                }
                // "Say send it" — the second half of raising a draft invoice.
                val sent = runCatching { actions.continueSend(heard) }
                    .onFailure { CrashLog.note(this, TAG, "send reply failed: ${it.message}") }
                    .getOrNull()
                if (sent != null) {
                    sayAnswer(sent)
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

        when {
            quick.requiresCloud -> say(
                when {
                    memo != null -> savedMemoLine(memo.clientName)
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

    /** A few free replies so greetings are not answered with a help list. */
    private fun smallTalk(text: String): String? {
        // Punctuated input ("No thanks.") must still match the plain forms.
        val lower = text.lowercase().trim().trimEnd('.', '!', '?', ',')
        return when {
            Regex("^(hi|hello|hey|good morning|good afternoon|good evening)\b")
                .containsMatchIn(lower) -> "Hello. What can I do?"
            lower in setOf(
                "no", "no thanks", "nothing", "that's all", "thats all", "all done",
                "we're done", "were done", "finished", "no thank you",
            ) -> "Right, I'll leave you to it."
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
        callActiveNow = false
        callActiveNow = false
        _dialling.value = false
        _connectedAt.value = 0
        _muted.value = false
        _speakerOn.value = false
        ringback.stop()
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
        const val CALL_BUTTON_DOUBLE_MS = 900L
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
        SpokenIntent.NextJobForClient ->
            if (topic.isEmpty()) "Checking that booking." else "Checking when $topic is next in."
        SpokenIntent.TodayEarnings -> "Checking today's takings."
        SpokenIntent.DayWrapUp -> "Running through today's jobs."
        SpokenIntent.RaiseInvoice -> "Drafting that invoice."
        SpokenIntent.AdjustCharge -> "Adjusting that visit."
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
