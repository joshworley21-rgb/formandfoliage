package uk.co.jwgardenservices.crm.voice

/**
 * Shared pronunciation dictionary for both sides of the pipeline.
 *
 * Pre-TTS it respells words the engines trip over. Post-ASR the same canonical
 * terms are handed to [AsrHotwords] so a near miss is written back in its real
 * spelling. Client names and villages discovered from Room are added at call
 * start and remain available to both.
 */
object PronunciationDictionary {
    private data class Entry(
        val word: String,
        val canonical: String,
        val soundsLike: String,
    )

    private val builtIn = listOf(
        // Buckinghamshire places seen in the diary.
        Entry("aylesbury", "Aylesbury", "Ailsbury"),
        Entry("bierton", "Bierton", "Beerton"),
        Entry("beaconsfield", "Beaconsfield", "Beckonsfield"),
        Entry("amersham", "Amersham", "Ammershum"),
        Entry("tring", "Tring", "Tring"),
        Entry("buckinghamshire", "Buckinghamshire", "Buckinghamshur"),
        Entry("hazlemere", "Hazlemere", "Hazelmeer"),
        Entry("hazelemere", "Hazelemere", "Hazelmeer"),
        Entry("chartridge", "Chartridge", "Chartridge"),
        Entry("wendover", "Wendover", "Wendover"),
        // Trade names and tools.
        Entry("stihl", "Stihl", "Steel"),
        Entry("monzo", "Monzo", "Monzo"),
        Entry("secateurs", "secateurs", "sekateurs"),
        Entry("herbaceous", "herbaceous", "herbayshus"),
        Entry("chafer", "chafer", "chayfer"),
        Entry("scarify", "scarify", "scairify"),
        Entry("overseeding", "overseeding", "overseeding"),
        Entry("strimmer", "strimmer", "strimmer"),
        // Names.
        Entry("aideen", "Aideen", "Aydeen"),
        // Initialisms should be letters, not invented words.
        Entry("gta", "GTA", "G T A"),
        Entry("vat", "VAT", "V A T"),
        Entry("crm", "CRM", "C R M"),
        Entry("asr", "ASR", "A S R"),
        Entry("hp22", "HP22", "H P twenty two"),
    )

    /** User additions from Settings, lower-case word -> sounds-like. */
    @Volatile
    var userEntries: Map<String, String> = emptyMap()

    /** Clients and villages loaded at call start, used for canonicalisation. */
    @Volatile
    private var dynamicTerms: List<String> = emptyList()

    private val byWord = builtIn.associateBy { it.word }
    private val canonical = builtIn.associate { it.word to it.canonical }
    private val stopWords = setOf(
        "the", "and", "for", "with", "from", "near", "over", "under", "road",
        "street", "lane", "close", "avenue", "drive", "house", "farm", "cottage",
    )

    fun setDynamicTerms(terms: List<String>) {
        dynamicTerms = terms
            .map { it.trim() }
            .filter { it.length in 3..40 }
            .filterNot { it.lowercase() in stopWords }
            .distinctBy { it.lowercase() }
    }

    /** Terms the ASR corrector may treat as canonical. */
    fun canonicalTerms(): List<String> =
        builtIn.map { it.canonical } + dynamicTerms

    fun apply(text: String): String {
        if (text.isEmpty()) return text
        val all = linkedMapOf<String, String>()
        byWord.forEach { (word, entry) -> all[word] = entry.soundsLike }
        all.putAll(userEntries)
        if (all.isEmpty()) return text

        var shaped = text
        // Restore canonical spelling for a known term that ASR or a typed
        // value left in lower case.
        for (term in dynamicTerms) {
            val lower = term.lowercase()
            if (canonical.containsKey(lower)) continue
            val pattern = Regex("\\b${Regex.escape(lower)}\\b", RegexOption.IGNORE_CASE)
            shaped = pattern.replace(shaped, term)
        }
        for ((word, _) in canonical) {
            val term = canonical.getValue(word)
            val pattern = Regex("\\b${Regex.escape(word)}\\b", RegexOption.IGNORE_CASE)
            shaped = pattern.replace(shaped, term)
        }

        val pattern = Regex(
            "\\b(" + all.keys.joinToString("|") { Regex.escape(it) } + ")\\b",
            RegexOption.IGNORE_CASE,
        )
        return pattern.replace(shaped) { match ->
            all[match.value.lowercase()] ?: match.value
        }
    }
}
