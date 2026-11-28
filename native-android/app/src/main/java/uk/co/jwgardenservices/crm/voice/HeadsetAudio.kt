package uk.co.jwgardenservices.crm.voice

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Level control for a Bluetooth HFP/SCO headset, whose microphone arrives quiet
 * (a real call peaked at 0.01 against 0.18 from the phone's own mic).
 *
 * A 100 Hz high-pass removes wind and handling rumble, then an AGC lifts the
 * voice toward RMS 0.12, by up to 16x for a very quiet headset (the old limit of
 * 2.6x left such a signal far below what the recogniser and the voice detector
 * need). The gain only chases frames that are clearly louder than the slowly
 * tracked quiet level, so pauses are not pumped up; in a pause it settles to a
 * capped hold level instead of sinking back to 1.4x, so the first word after a
 * pause is not lost. A loud frame drops the gain at once, and the result is
 * softly clipped to +-1.0.
 */
internal class HeadsetAudio {
    private var lastInput = 0f
    private var lastOutput = 0f
    private var gain = START_GAIN
    private var noise = INITIAL_NOISE

    fun reset() {
        lastInput = 0f
        lastOutput = 0f
        gain = START_GAIN
        noise = INITIAL_NOISE
    }

    fun process(raw: FloatArray): FloatArray {
        if (raw.isEmpty()) return raw
        val filtered = FloatArray(raw.size)
        var energy = 0.0
        for (index in raw.indices) {
            val sample = raw[index]
            val out = HPF_ALPHA * (lastOutput + sample - lastInput)
            lastInput = sample
            lastOutput = out
            filtered[index] = out
            energy += out * out
        }
        val rms = sqrt(energy / raw.size).toFloat()
        noise = if (rms < noise) noise * 0.8f + rms * 0.2f else noise * 0.998f + rms * 0.002f
        val active = rms >= max(MIN_ACTIVE_RMS, noise * ACTIVE_RATIO)
        val desired = if (active) {
            (TARGET_RMS / rms).coerceIn(MIN_GAIN, MAX_GAIN)
        } else {
            min(gain, PAUSE_GAIN).coerceAtLeast(MIN_GAIN)
        }
        // Quiet speech must be lifted within a few frames; a loud burst must
        // not be, so the gain falls faster than it rises.
        gain += (desired - gain) * if (desired < gain) 0.7f else 0.5f
        return FloatArray(raw.size) { index -> softClip(filtered[index] * gain) }
    }

    private fun softClip(value: Float): Float {
        val magnitude = abs(value)
        if (magnitude <= 0.9f) return value
        val over = (magnitude - 0.9f) / 0.1f
        val shaped = 0.9f + 0.1f * tanh(over.toDouble()).toFloat()
        return if (value < 0f) -shaped else shaped
    }

    private companion object {
        const val TARGET_RMS = 0.12f
        const val MIN_GAIN = 1.4f
        const val MAX_GAIN = 16f

        /** Where the gain settles in a pause, so a quiet headset is not dropped back to 1.4x. */
        const val PAUSE_GAIN = 8f
        const val START_GAIN = 2.2f
        const val INITIAL_NOISE = 0.001f

        /** A frame counts as speech when it clears both of these. */
        const val MIN_ACTIVE_RMS = 0.0012f
        const val ACTIVE_RATIO = 2f

        /** 100 Hz one-pole high-pass at 16 kHz. */
        const val HPF_ALPHA = 0.9622f
    }
}
