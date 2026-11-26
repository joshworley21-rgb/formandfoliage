package uk.co.jwgardenservices.crm.voice

import kotlin.math.abs
import kotlinx.coroutines.flow.first
import uk.co.jwgardenservices.crm.data.repository.ServiceLocator

/**
 * Post-ASR correction of client names.
 *
 * Neither engine here supports decoder-side hotword biasing (Nemotron's NeMo
 * transducer accepts greedy_search only), so names are fixed after the
 * recogniser returns: a near miss is replaced with the canonical client name,
 * alias or patch word (one edit for five-letter names, two for nine or more).
 * The corrected transcript then flows into the rules and [SemanticMatcher].
 */
object AsrHotwords {
    private const val TAG = "AsrHotwords"
    private const val MAX_NAMES = 57
    private const val MAX_TOTAL = 240

    /** Client names for this call, sanitised for the tokenizer. */
    suspend fun names(): List<String> = runCatching {
        ServiceLocator.crmRepository.observeClients().first()
    }.getOrDefault(emptyList())
        .map { sanitize(it.name) }
        .filter { it.length in 3..30 }
        .distinct()
        .take(MAX_NAMES)

    /**
     * Everything the corrector may rewrite: client names first, then the
     * nicknames saved for them ("the rectory"), then the patch vocabulary
     * and trade terms from the bundled website profile.
     */
    suspend fun correctionTerms(context: android.content.Context): List<String> {
        val clientRows = runCatching {
            ServiceLocator.crmRepository.observeClients().first()
        }.getOrDefault(emptyList())
        val rawClients = clientRows.map { it.name.trim() }
            .filter { it.length in 3..30 }
            .take(MAX_NAMES)
        val rawVillages = clientRows
            .flatMap { villagesFromAddress(it.address) }
            .filter { it.length in 3..30 }
            .distinct()
        // Full names, first names and couple variants. Narrowband Bluetooth
        // often drops "Louisa & George" down to a single mangled word, so a
        // first-name entry gives the corrector something to land on.
        val clients = rawClients.flatMap { nameVariants(it) }
            .map { sanitize(it) }
            .filter { it.length >= 3 }
            .distinct()
        val villages = rawVillages.map { sanitize(it) }
        val profile = BusinessBrain.load(context)
        val aliases = runCatching {
            ServiceLocator.crmRepository.allVoiceAliases().map { sanitize(it.second) }
        }.getOrDefault(emptyList())
        val patch = (BusinessBrain.vocabulary(profile) + BusinessBrain.tradeTerms(profile))
            .map { sanitize(it) }
            .filter { it.length in 3..30 }
        val dictionary = PronunciationDictionary.canonicalTerms()
            .map { sanitize(it) }
            .filter { it.length in 2..30 }
        // Keep the shared dictionary and the live clients available on both
        // the ASR and TTS sides of the same call.
        PronunciationDictionary.setDynamicTerms(rawClients + rawVillages)
        return (clients + villages + dictionary + aliases + patch).distinct().take(MAX_TOTAL)
    }

    /** Full name, first name, and the "X and Y" / "X Y" couple forms. */
    private fun nameVariants(raw: String): List<String> {
        val cleaned = raw.replace("&", " and ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val tokens = sanitize(cleaned).split(' ')
            .filter { it.length >= 3 && it !in PERSON_STOP }
        if (tokens.isEmpty()) return listOf(cleaned)
        val variants = mutableListOf(cleaned)
        variants += tokens.first()
        val lower = cleaned.lowercase().split(Regex("\\s+"))
        val andAt = lower.indexOf("and")
        if (andAt in 0 until lower.lastIndex) {
            variants += lower[andAt + 1]
            variants += lower.filterIndexed { index, _ -> index != andAt }.joinToString(" ")
        }
        return variants
    }

    /** Last meaningful address line, with the postcode removed. */
    private fun villagesFromAddress(address: String?): List<String> {
        if (address.isNullOrBlank()) return emptyList()
        val withoutPostcode = address.replace(
            Regex("\\b[A-Z]{1,2}\\d{1,2}[A-Z]?\\s?\\d[A-Z]{2}\\b", RegexOption.IGNORE_CASE),
            " ",
        )
        return withoutPostcode
            .split(',', '\n')
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { it.length in 3..30 }
            .filter { it.split(Regex("\\s+")).size <= 3 }
            .takeLast(2)
    }

    fun correct(
        text: String,
        names: List<String>,
        aggressive: Boolean = false,
    ): String {
        if (text.isBlank() || names.isEmpty()) return text
        val tokens = text.split(Regex("\\s+")).toMutableList()
        var index = 0
        while (index < tokens.size) {
            // An exact name is left alone, but skip the whole span so a
            // shorter client name cannot match the tail of a longer one.
            var exactEnd = -1
            for (name in names) {
                val end = index + words(name)
                if (end > tokens.size) continue
                if (slice(tokens, index, end).equals(name, ignoreCase = true)) {
                    exactEnd = end
                    break
                }
            }
            if (exactEnd != -1) {
                index = exactEnd
                continue
            }

            // Otherwise take the closest near miss at this position. The
            // window is the name's own word count (1-gram for a first name,
            // 2-gram for "Louisa George"), and a phonetic code catches the
            // narrowband symptoms where the leading consonant is lost.
            var bestName: String? = null
            var bestEnd = index
            var bestScore = Int.MAX_VALUE
            for (name in names) {
                val end = index + words(name)
                if (end > tokens.size) continue
                val allowed = allowedEdits(name) + if (aggressive) 1 else 0
                val heard = slice(tokens, index, end).uppercase()
                val lengthGap = abs(heard.length - name.length)
                val distance = distance(heard, name)
                val fuzzy = distance in 1..allowed
                // The sound-alike rule is for a headset that drops a name's first
                // letter, so it needs a long name and a word of some length, and
                // must never rewrite an everyday word: "today" must not become
                // "GTA" or "hours" "BARK".
                val phonetic = !fuzzy && heard.length >= 4 && name.length >= 6 &&
                    lengthGap <= (if (aggressive) 4 else 2) &&
                    heard.split(' ').none { it in COMMON_WORDS } &&
                    phoneticMatch(heard, name)
                if (!fuzzy && !phonetic) continue
                val score = if (fuzzy) distance else allowed + 1 + lengthGap
                if (score < bestScore) {
                    bestName = name
                    bestEnd = end
                    bestScore = score
                }
            }
            if (bestName == null) {
                index += 1
                continue
            }

            val tail = tokens[bestEnd - 1].takeLastWhile { it in ",.!?;:" }
            for (i in index until bestEnd) tokens[i] = ""
            tokens[index] = bestName + tail
            // Advance past the replacement: without this, a multi-word name
            // leaves blank tokens behind and the same span matches again on
            // the next pass, spinning forever.
            index = bestEnd
        }
        return tokens.filter { it.isNotEmpty() }.joinToString(" ")
    }

    private fun slice(tokens: List<String>, start: Int, end: Int): String =
        tokens.subList(start, end)
            .joinToString(" ") { it.trim(',', '.', '!', '?', ';', ':') }
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun words(name: String): Int =
        name.split(Regex("\\s+")).count { it.isNotBlank() }

    private fun allowedEdits(name: String): Int = when {
        name.length >= 9 -> 2
        name.length >= 5 -> 1
        else -> 0
    }

    /** Uppercase, letters and spaces only; the tokenizer has no apostrophes. */
    private fun sanitize(name: String): String = name.uppercase()
        .replace(Regex("[^A-Z ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    /**
     * Soundex with the leading consonant deliberately ignored on the tail
     * comparison: "WEAS" and "LOUISA" both reduce to 200 there, which is how
     * a narrowband headset drops the first letter.
     */
    private fun phoneticMatch(heard: String, name: String): Boolean {
        val heardWords = heard.uppercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        val nameWords = name.uppercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (heardWords.size != nameWords.size) return false
        return heardWords.zip(nameWords).all { (a, b) ->
            val codeA = soundex(a)
            val codeB = soundex(b)
            codeA.isNotEmpty() && codeB.isNotEmpty() &&
                (codeA == codeB ||
                    (codeA.length == 4 && codeB.length == 4 &&
                        codeA.drop(1) == codeB.drop(1)))
        }
    }

    private fun soundex(value: String): String {
        val letters = value.filter { it in 'A'..'Z' }
        if (letters.isEmpty()) return ""
        val code = StringBuilder().append(letters[0])
        var previous = digit(letters[0])
        for (index in 1 until letters.length) {
            val current = digit(letters[index])
            if (current != '0' && current != previous) code.append(current)
            previous = current
        }
        return (code.toString() + "000").take(4)
    }

    private fun digit(letter: Char): Char = when (letter) {
        'B', 'F', 'P', 'V' -> '1'
        'C', 'G', 'J', 'K', 'Q', 'S', 'X', 'Z' -> '2'
        'D', 'T' -> '3'
        'L' -> '4'
        'M', 'N' -> '5'
        'R' -> '6'
        else -> '0'
    }

    private fun distance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1, previous[j - 1] + cost)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }

    private val PERSON_STOP = setOf(
        "THE", "AND", "LTD", "LIMITED", "LLP", "MR", "MRS", "MS", "MISS", "DR",
    )

    /** Everyday and command words, uppercase like the text they are compared with. */
    private val COMMON_WORDS = setOf(
        "TODAY", "TOMORROW", "YESTERDAY", "VISIT", "HOURS", "HOUR", "MARK",
        "LOG", "DONE", "CLIENT", "WHICH", "THAT", "THIS", "WHAT", "WHEN",
        "WHERE", "PLEASE", "THANKS", "MORNING", "AFTERNOON", "SCHEDULE",
        "CAN", "YOU", "ADD", "FOR", "WITH", "FROM", "HAVE", "GOT", "SHOW", "TELL",
        "ONE", "TWO", "THREE", "FOUR", "FIVE", "SIX", "SEVEN", "EIGHT", "NINE",
        "TEN", "ELEVEN", "TWELVE", "HALF", "QUARTER", "MINUTE", "MINUTES",
        "NEXT", "JOB", "JOBS", "DIARY", "NOTE", "MONEY", "OWES", "OWED", "PAID",
        "UNPAID", "INVOICE", "INVOICES", "WEEK", "MONTH", "BOOKED", "CALENDAR",
        "EARNINGS", "TAKINGS", "STARTED", "FINISH", "FINISHED", "COMPLETE",
        "COMPLETED", "WORKED", "WORK", "MONDAY", "TUESDAY", "WEDNESDAY",
        "THURSDAY", "FRIDAY", "SATURDAY", "SUNDAY",
    )
}
