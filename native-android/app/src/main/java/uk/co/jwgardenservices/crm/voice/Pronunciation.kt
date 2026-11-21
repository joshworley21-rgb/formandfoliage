package uk.co.jwgardenservices.crm.voice

/**
 * TTS-only respellings for words the engines get wrong: local place names,
 * acronyms and a few names. The transcript and UI keep the real spelling;
 * only the string handed to Phonon/Kokoro changes.
 *
 * Keep these as whole-word, case-insensitive replacements. Add a line here
 * when a place or client is consistently mispronounced.
 */
object Pronunciation {
    private val spoken = linkedMapOf(
        // Buckinghamshire places seen in the diary.
        "bierton" to "Beerton",
        "aylesbury" to "Ailsbury",
        "hazlemere" to "Hazelmeer",
        "hazelemere" to "Hazelmeer",
        "beaconsfield" to "Beckonsfield",
        "chartridge" to "Chartridge",
        "wendover" to "Wendover",
        // Names.
        "aideen" to "Aydeen",
        // Initialisms should be letters, not invented words.
        "gta" to "G T A",
        "vat" to "V A T",
        "crm" to "C R M",
        "asr" to "A S R",
        "hp22" to "H P twenty two",
    )

    /** User additions from Settings, lower-case word -> sounds-like. */
    @Volatile
    var userEntries: Map<String, String> = emptyMap()

    fun apply(text: String): String {
        val all = spoken + userEntries
        if (all.isEmpty()) return text
        val pattern = Regex(
            "\\b(" + all.keys.joinToString("|") { Regex.escape(it) } + ")\\b",
            RegexOption.IGNORE_CASE,
        )
        return pattern.replace(text) { match ->
            all[match.value.lowercase()] ?: match.value
        }
    }
}
