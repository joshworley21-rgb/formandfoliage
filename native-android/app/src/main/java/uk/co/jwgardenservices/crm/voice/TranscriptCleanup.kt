package uk.co.jwgardenservices.crm.voice

/**
 * Small, pure clean-ups for what the recognisers return, done before the text
 * is corrected, punctuated or understood.
 */
object TranscriptCleanup {
    private val fillers = Regex("\\b(?:u+h+m*|u+m+|e+r+m*|a+h+m+|h+m+|m+h?m+)\\b[,.]?", RegexOption.IGNORE_CASE)

    // Whisper writes silence and noise as "[BLANK_AUDIO]", "(music)" or "*cough*",
    // and a clipped one can be left open, such as "[BL".
    private val notes = Regex("[\\[(*][^\\])*]*(?:[\\])*]|$)")

    /** Drops "um", "uh", "er", "hmm" and the like so they are not read as words. */
    fun stripFillers(text: String): String =
        text.replace(fillers, " ").replace(Regex("\\s+"), " ").replace(Regex("\\s+([,.?!])"), "$1").trim()

    /** A recogniser's own sound notes are not speech. */
    fun stripNotes(text: String): String =
        text.replace(notes, " ").replace(Regex("\\s+"), " ").trim()

    fun clean(text: String): String = stripFillers(stripNotes(text))
}
