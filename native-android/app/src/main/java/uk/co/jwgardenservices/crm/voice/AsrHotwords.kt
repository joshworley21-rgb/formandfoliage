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
    private const val MAX_NAMES = 40
    private const val MAX_TOTAL = 90

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
        val clients = rawClients.map { sanitize(it) }
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

    fun correct(text: String, names: List<String>): String {
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
                if (slice(tokens, index, end) == name) {
                    exactEnd = end
                    break
                }
            }
            if (exactEnd != -1) {
                index = exactEnd
                continue
            }

            // Otherwise take the closest near miss at this position.
            var bestName: String? = null
            var bestEnd = index
            var bestDistance = Int.MAX_VALUE
            for (name in names) {
                val end = index + words(name)
                if (end > tokens.size) continue
                val allowed = allowedEdits(name)
                if (allowed == 0) continue
                val heard = slice(tokens, index, end)
                if (abs(heard.length - name.length) > allowed) continue
                val distance = distance(heard, name)
                if (distance in 1..allowed && distance < bestDistance) {
                    bestName = name
                    bestEnd = end
                    bestDistance = distance
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
}
