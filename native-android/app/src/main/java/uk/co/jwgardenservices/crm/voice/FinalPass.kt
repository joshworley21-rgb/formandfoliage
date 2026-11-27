package uk.co.jwgardenservices.crm.voice

/** Which offline recogniser takes the second look at each sentence. */
enum class FinalPass(val id: String, val label: String) {
    Moonshine("moonshine", "Moonshine (default, fast)"),
    Whisper("whisper", "Whisper small.en (slower, built for noisy speech)"),
    WhisperCpp("whisper_cpp", "whisper.cpp small.en (shorter window, with voice detection)"),
    ;

    companion object {
        fun fromId(id: String?): FinalPass = entries.firstOrNull { it.id == id } ?: Moonshine
    }
}
