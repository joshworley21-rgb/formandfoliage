package uk.co.jwgardenservices.crm.voice

/**
 * Which offline recogniser takes the second look at each sentence, and speaks
 * for the call on its own if the live recogniser is unavailable.
 */
enum class FinalPass(val id: String, val label: String) {
    Off("off", "Off (live recogniser only)"),
    Parakeet("parakeet", "Parakeet TDT 0.6B (most accurate, heaviest)"),
    Whisper("whisper", "Whisper small.en (slower, built for noisy speech)"),
    WhisperCpp("whisper_cpp", "whisper.cpp small.en (shorter window, with voice detection)"),
    ;

    companion object {
        fun fromId(id: String?): FinalPass = entries.firstOrNull { it.id == id } ?: Off
    }
}
