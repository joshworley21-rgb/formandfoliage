package uk.co.jwgardenservices.crm.voice

/**
 * Small prosody fixes applied to text just before it is spoken.
 *
 * Phonon renders short clauses very fast, so a reply that opens on a
 * connector ("Right then, two visits…") can come out as one breathless run.
 * Making the pause explicit with a comma gives the TTS prosody model a place
 * to breathe and drop its pitch, which is how a person picks up a call.
 *
 * This is speak-only: the recogniser never sees it, so ASR matching is
 * unaffected.
 */
object TtsProsody {

    private val leadIn = Regex(
        "^(right then|no worries|no problem|all sorted|sorted|alright|okay|ok|cheers|now then|good stuff)\\b[.,!?]*\\s*",
        RegexOption.IGNORE_CASE,
    )

    fun smooth(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return trimmed
        val match = leadIn.find(trimmed) ?: return trimmed
        val rest = trimmed.substring(match.range.last + 1).trimStart()
        if (rest.isEmpty()) return trimmed
        val lead = match.value.trimEnd(' ', '.', ',', '!', '?')
        return "$lead, $rest"
    }
}
