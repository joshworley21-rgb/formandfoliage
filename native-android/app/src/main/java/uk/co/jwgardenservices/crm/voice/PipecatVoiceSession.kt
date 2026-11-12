package uk.co.jwgardenservices.crm.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.concurrent.TimeUnit

/**
 * The server-brain assistant: streams microphone audio to the VPS voice
 * pipeline (Faster-Whisper + Ollama + Kokoro over Pipecat's WebSocket
 * transport) and plays the 24 kHz reply. The server transcribes, thinks and
 * speaks; the phone is just ears and a mouth, so this works with any signal
 * that reaches the VPS.
 *
 * Barge-in is handled two ways: the server sends InterruptionFrame when its
 * own VAD hears the user over the bot, and a light energy check on the mic
 * flushes the speaker the moment the user talks over it.
 */
class PipecatVoiceSession(
    private val context: Context,
    private val listener: Listener,
    private val url: String,
) {
    interface Listener {
        /** What the user said, as transcribed by the server. */
        fun onUserTranscript(text: String)

        /** What the assistant is saying (from the LLM text frames). */
        fun onAssistantText(text: String)

        /** Connection is live and the pipeline is flowing. */
        fun onConnected()

        /** The server cannot be used; the caller should fall back offline. */
        fun onUnavailable(message: String)
    }

    private val okHttp = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var captureThread: Thread? = null
    private var focusRequest: AudioFocusRequest? = null

    @Volatile private var running = false
    @Volatile private var captureActive = true
    @Volatile private var botSpeaking = false
    @Volatile private var bargeStreak = 0
    @Volatile private var gotPipelineTraffic = false

    fun start() {
        if (running) return
        running = true
        val request = Request.Builder().url(url).build()
        webSocket = okHttp.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                listener.onConnected()
                startCapture()
                startPlayback()
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                gotPipelineTraffic = true
                when (val frame = PipecatFrames.decode(bytes.toByteArray())) {
                    is PipecatFrames.Frame.Audio -> {
                        botSpeaking = true
                        listener.onConnected()
                        play(frame.audio)
                    }
                    is PipecatFrames.Frame.Transcription ->
                        if (frame.text.isNotBlank()) listener.onUserTranscript(frame.text)
                    is PipecatFrames.Frame.Text ->
                        if (frame.text.isNotBlank()) listener.onAssistantText(frame.text)
                    is PipecatFrames.Frame.Interruption -> flushPlayback()
                    else -> {}
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (running) listener.onUnavailable("Server assistant unreachable (${t.message ?: "connection failed"})")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (running) listener.onUnavailable("Server assistant closed the connection")
            }
        })
    }

    fun stop() {
        if (!running) return
        running = false
        try {
            webSocket?.close(1000, "Call ended")
        } catch (error: Exception) {
            // The socket may already be gone; nothing to do about it here.
        }
        webSocket = null
        stopCapture()
        stopPlayback()
        abandonFocus()
        okHttp.dispatcher.let { /* keep the client; calls are cheap */ }
    }

    /** The server does the talking in this mode. */
    fun speak(text: String) {}

    /** The server is always listening in this mode. */
    fun listen() {}

    fun setMuted(muted: Boolean) {
        captureActive = !muted
    }

    @SuppressLint("MissingPermission") // CallScreen checks RECORD_AUDIO before starting.
    private fun startCapture() {
        if (audioRecord != null) return
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_IN, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferSize = maxOf(minBuffer, CHUNK_BYTES * 8)
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE_IN,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize,
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            listener.onUnavailable("Microphone could not be opened")
            return
        }
        audioRecord = record
        record.startRecording()
        captureThread = Thread {
            val buffer = ByteArray(CHUNK_BYTES)
            while (running) {
                if (!captureActive) {
                    Thread.sleep(60)
                    continue
                }
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) {
                    val chunk = if (read == buffer.size) buffer else buffer.copyOf(read)
                    webSocket?.send(PipecatFrames.encodeAudio(chunk).toByteString())
                    maybeLocalBargeIn(chunk)
                } else if (read < 0) {
                    break
                }
            }
        }.apply { priority = Thread.MAX_PRIORITY - 1; start() }
    }

    private fun stopCapture() {
        captureThread = null
        audioRecord?.let {
            runCatching { it.stop() }
            it.release()
        }
        audioRecord = null
    }

    private fun startPlayback() {
        if (audioTrack != null) return
        requestFocus()
        val minBuffer = AudioTrack.getMinBufferSize(
            SAMPLE_RATE_OUT, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE_OUT)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minBuffer, CHUNK_BYTES_OUT * 4))
            .build()
        audioTrack?.play()
    }

    private fun stopPlayback() {
        audioTrack?.let {
            runCatching { it.stop() }
            it.release()
        }
        audioTrack = null
        botSpeaking = false
    }

    private fun play(pcm: ByteArray) {
        val track = audioTrack ?: return
        track.write(pcm, 0, pcm.size)
        if (!running) flushPlayback()
    }

    /** Stop the bot mid-sentence so the user can talk over it. */
    private fun flushPlayback() {
        audioTrack?.let {
            runCatching {
                it.pause()
                it.flush()
                it.play()
            }
        }
        botSpeaking = false
    }

    /**
     * Light energy VAD: while the bot is speaking, a sustained loud mic signal
     * flushes playback immediately rather than waiting for the server's
     * interruption round-trip. Server-side VAD remains the authority.
     */
    private fun maybeLocalBargeIn(chunk: ByteArray) {
        if (!botSpeaking) {
            bargeStreak = 0
            return
        }
        var sum = 0L
        for (i in chunk.indices step 2) {
            val sample = (chunk[i].toInt() and 0xFF) or (chunk[i + 1].toInt() shl 8)
            sum += sample.toLong() * sample
        }
        val rms = kotlin.math.sqrt(sum / (chunk.size / 2).coerceAtLeast(1).toDouble())
        if (rms > BARGE_IN_RMS) bargeStreak += 1 else bargeStreak = 0
        if (bargeStreak >= BARGE_IN_STREAK) {
            bargeStreak = 0
            flushPlayback()
        }
    }

    private fun requestFocus() {
        val manager = context.getSystemService(AudioManager::class.java) ?: return
        focusRequest?.let { return }
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
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

    companion object {
        private const val SAMPLE_RATE_IN = 16_000
        private const val SAMPLE_RATE_OUT = 24_000
        private const val CHUNK_MS = 20
        private const val CHUNK_BYTES = SAMPLE_RATE_IN / 50 * 2 // 20 ms of 16-bit mono
        private const val CHUNK_BYTES_OUT = SAMPLE_RATE_OUT / 50 * 2
        private const val BARGE_IN_RMS = 1_200.0
        private const val BARGE_IN_STREAK = 4
    }
}
