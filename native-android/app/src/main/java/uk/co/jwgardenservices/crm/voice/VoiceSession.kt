package uk.co.jwgardenservices.crm.voice

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * The microphone and the voice of the assistant, wrapped around Android's
 * offline speech recognition and text-to-speech so the whole loop works
 * without signal.
 *
 * The turn pattern is: listen until you finish, think, speak a short reply,
 * then listen again. True simultaneous conversation (barge-in) needs a
 * streaming cloud model and is deliberately left out of this version.
 */
class VoiceSession(
    private val context: Context,
    private val listener: Listener,
) : RecognitionListener {

    interface Listener {
        /** A finished utterance to act on. */
        fun onHeard(text: String)

        /** Nothing usable was said. */
        fun onSilence()

        /** The assistant has finished speaking; listening can resume. */
        fun onSpoken()

        /** The mic or speaker cannot work right now. */
        fun onEngineError(message: String)
    }

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var started = false
    private var listening = false

    fun start() {
        if (started) return
        started = true
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                tts?.language = Locale.UK
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        abandonFocus()
                        listener.onSpoken()
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        abandonFocus()
                        listener.onSpoken()
                    }
                })
            } else {
                listener.onEngineError("This phone has no voice output. Check the transcript instead.")
            }
        }
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(this@VoiceSession)
            }
            listen()
        } else {
            listener.onEngineError("This phone has no speech recognition.")
        }
    }

    fun speak(text: String) {
        requestFocus()
        val params = Bundle()
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "assistant-${System.currentTimeMillis()}")
    }

    fun stop() {
        started = false
        listening = false
        abandonFocus()
        recognizer?.stopListening()
        recognizer?.destroy()
        recognizer = null
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    fun listen() {
        val recognizer = recognizer ?: return
        if (!started || listening) return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.UK.toLanguageTag())
        }
        listening = true
        recognizer.startListening(intent)
    }

    /** RecognitionListener */
    override fun onResults(results: Bundle) {
        listening = false
        val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()?.trim().orEmpty()
        if (text.isBlank()) listener.onSilence() else listener.onHeard(text)
    }

    override fun onError(error: Int) {
        listening = false
        when (error) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                listener.onSilence()
            else -> listener.onEngineError("Microphone error ($error). Tap the mic to try again.")
        }
    }

    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {
        listening = false
    }
    override fun onPartialResults(partialResults: Bundle?) {}
    override fun onEvent(eventType: Int, params: Bundle?) {}

    private fun requestFocus() {
        val manager = context.getSystemService(AudioManager::class.java) ?: return
        focusRequest?.let { return }
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .build()
        manager.requestAudioFocus(focusRequest!!)
    }

    private fun abandonFocus() {
        val request = focusRequest ?: return
        context.getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(request)
        focusRequest = null
    }

    private var focusRequest: AudioFocusRequest? = null
}
