package uk.co.jwgardenservices.crm.voice

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * The phone's own text-to-speech, used to confirm that the transcription
 * loop produced something. Nothing is sent to a server.
 *
 * [speak] is safe to call before the engine has finished starting: the text
 * is held until initialisation completes (or dropped with the callback run,
 * so callers that paused listening are never left paused).
 */
class AssistantSpeaker(context: Context) : AutoCloseable {

    private val appContext = context.applicationContext

    private var tts: TextToSpeech? = null
    private var ready = false
    private var closed = false

    /** Text asked for before the engine was ready. */
    private var pendingText: String? = null
    private var pendingDone: (() -> Unit)? = null

    private var onDone: (() -> Unit)? = null

    init {
        tts = TextToSpeech(appContext) { status -> onInit(status) }
    }

    /** Say [text] out loud, calling [done] once the engine has finished. */
    fun speak(text: String, done: (() -> Unit)? = null) {
        if (closed) {
            done?.invoke()
            return
        }
        if (!ready) {
            pendingText = text
            pendingDone = done
            return
        }
        speakNow(text, done)
    }

    private fun speakNow(text: String, done: (() -> Unit)?) {
        val engine = tts ?: run {
            done?.invoke()
            return
        }
        onDone = done
        val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), UTTERANCE_ID)
        if (result != TextToSpeech.SUCCESS) {
            onDone = null
            done?.invoke()
        }
    }

    private fun onInit(status: Int) {
        if (closed) return
        val engine = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "Text-to-speech did not start (status $status)")
            flushPending()
            return
        }
        ready = true
        val language = engine.setLanguage(Locale.UK)
        if (language == TextToSpeech.LANG_MISSING_DATA || language == TextToSpeech.LANG_NOT_SUPPORTED) {
            engine.language = Locale.getDefault()
        }
        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}

            override fun onDone(utteranceId: String?) = finish()

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finish()

            override fun onStop(utteranceId: String?, interrupted: Boolean) = finish()

            private fun finish() {
                val callback = onDone
                onDone = null
                callback?.invoke()
            }
        })
        flushPending()
    }

    private fun flushPending() {
        val text = pendingText ?: return
        val done = pendingDone
        pendingText = null
        pendingDone = null
        if (ready) speakNow(text, done) else done?.invoke()
    }

    override fun close() {
        closed = true
        pendingText = null
        pendingDone = null
        onDone = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }

    private companion object {
        const val TAG = "AssistantSpeaker"
        const val UTTERANCE_ID = "assistant-confirmation"
    }
}
