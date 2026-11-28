package uk.co.jwgardenservices.crm.voice

import java.io.File

/** The JNI entry points in libwhisper_jni.so, built from whisper.cpp by the APK workflow. */
internal object WhisperCppNative {
    @JvmStatic external fun nativeInit(modelPath: String): Long

    @JvmStatic external fun nativeTranscribe(
        handle: Long,
        samples: FloatArray,
        threads: Int,
        audioCtx: Int,
        vadPath: String,
    ): String

    @JvmStatic external fun nativeFree(handle: Long)
}

/**
 * whisper.cpp as the offline second look. Unlike the ONNX Whisper, the encoder
 * window can be shortened to fit the sentence instead of always covering 30
 * seconds, which is where most of the time goes. The Silero VAD model, when
 * present, trims silence so a pause is not written up as "[BLANK_AUDIO]".
 */
internal class WhisperCpp private constructor(
    private var handle: Long,
    private val vadPath: String,
) {
    @Synchronized
    fun transcribe(samples: FloatArray, threads: Int = THREADS): String {
        if (handle == 0L || samples.isEmpty()) return ""
        val ctx = audioContextFor(samples.size / SAMPLE_RATE.toFloat())
        return WhisperCppNative.nativeTranscribe(handle, samples, threads, ctx, vadPath).trim()
    }

    @Synchronized
    fun close() {
        if (handle != 0L) WhisperCppNative.nativeFree(handle)
        handle = 0L
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val THREADS = 4
        private const val FRAMES_PER_SECOND = 50 // Whisper's encoder: 1500 frames for 30 s
        private const val MIN_CONTEXT = 500
        private const val MAX_CONTEXT = 1500
        private const val SLACK = 150

        /**
         * Encoder frames to use for a clip: its length plus some slack, never
         * below a floor (very short windows read badly) or above the full 30 s.
         */
        fun audioContextFor(seconds: Float): Int =
            (seconds * FRAMES_PER_SECOND + SLACK).toInt().coerceIn(MIN_CONTEXT, MAX_CONTEXT)

        /** True once the native library has loaded; false on a build without it. */
        val available: Boolean by lazy { runCatching { System.loadLibrary("whisper_jni") }.isSuccess }

        fun open(model: File, vad: File?): WhisperCpp? {
            if (!available || !model.isFile) return null
            val handle = runCatching { WhisperCppNative.nativeInit(model.absolutePath) }.getOrDefault(0L)
            if (handle == 0L) return null
            return WhisperCpp(handle, vad?.takeIf { it.isFile }?.absolutePath.orEmpty())
        }
    }
}
