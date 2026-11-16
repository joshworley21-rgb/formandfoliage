package uk.co.jwgardenservices.crm.voice

/**
 * Speak a little louder over noise, never louder in a quiet garden.
 *
 * The recogniser already measures the microphone level every frame for the
 * UI meter. Those samples feed a slow rolling average here — a van on a dual
 * carriageway in the rain sits well above a still garden — and the playback
 * paths multiply the TTS samples by this gain.
 *
 * Pure arithmetic, no extra sensors, no extra threads.
 */
object TtsGain {

    /** Multiplier applied to Phonon/Kokoro samples just before the speaker. */
    @Volatile
    var current: Float = 1f
        private set

    /**
     * [ambient] is the rolling average of the recogniser's 0..1 level meter.
     * Quiet rooms sit near 0.02; a moving van's road noise around 0.15-0.3.
     */
    fun updateFromAmbient(ambient: Float) {
        current = when {
            ambient >= 0.24f -> 1.15f
            ambient >= 0.12f -> 1.08f
            else -> 1f
        }
    }

    fun reset() {
        current = 1f
    }
}
