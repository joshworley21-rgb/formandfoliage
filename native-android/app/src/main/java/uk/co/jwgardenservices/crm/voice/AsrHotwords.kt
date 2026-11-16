package uk.co.jwgardenservices.crm.voice

import android.content.Context
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.flow.first
import uk.co.jwgardenservices.crm.core.CrashLog
import uk.co.jwgardenservices.crm.data.repository.ServiceLocator

/**
 * Client-name biasing for the recogniser.
 *
 * Two layers work together:
 *
 * 1. **Token biasing.** sherpa's context graph boosts named phrases while the
 *    decoder is still listening. It needs the hotwords encoded into the
 *    model's BPE pieces, and this checkpoint ships only `tokens.txt` (no
 *    merge table), so [ensureVocab] writes an equal-score `bpe.vocab` from the
 *    pieces themselves — the same pattern sherpa's own CI uses. ssentencepiece
 *    then segments each name by longest match. Longest match does not always
 *    equal the model's true merge segmentation, so a boost can silently fail;
 *    that is why layer two exists.
 *
 * 2. **Transcript correction.** After recognition, near misses are replaced
 *    with the canonical client name (one edit for five-letter names, two for
 *    nine or more). This catches the cases the biasing misses, and is the only
 *    layer the offline Moonshine model gets.
 */
object AsrHotwords {
    private const val TAG = "AsrHotwords"
    private const val MAX_NAMES = 40
    private const val MAX_TOTAL = 64
    private const val BOOST = 2.0f

    /** Client names for this call, sanitised for the tokenizer. */
    suspend fun names(): List<String> = runCatching {
        ServiceLocator.crmRepository.observeClients().first()
    }.getOrDefault(emptyList())
        .map { sanitize(it.name) }
        .filter { it.length in 3..30 }
        .distinct()
        .take(MAX_NAMES)

    /**
     * Everything to bias this call with: client names first, then the patch
     * vocabulary and trade terms from the bundled website profile. Places
     * ("Berryfields") and plants ("Ceanothus") come straight from the site's
     * own words, so the beam search holds onto them while you speak.
     */
    suspend fun biasTerms(context: android.content.Context): List<String> {
        val clients = names()
        val profile = BusinessBrain.load(context)
        val patch = (BusinessBrain.vocabulary(profile) + BusinessBrain.tradeTerms(profile))
            .map { sanitize(it) }
            .filter { it.length in 3..30 }
        return (clients + patch).distinct().take(MAX_TOTAL)
    }

    /**
     * Build a `bpe.vocab` next to the copied model files, once. Equal scores
     * make ssentencepiece's Viterbi prefer fewer, longer pieces. Returns null
     * when the file cannot be built, in which case no hotwords are passed and
     * the recogniser behaves exactly as before.
     */
    fun ensureVocab(context: Context, modelDir: File): String? {
        val tokens = File(modelDir, "tokens.txt")
        if (!tokens.isFile) return null
        val vocab = File(modelDir, "bpe.vocab")
        if (vocab.isFile && vocab.length() > 0) return vocab.absolutePath
        return runCatching {
            val lines = tokens.readLines().mapNotNull { line ->
                val piece = line.substringBefore(' ').trim()
                if (piece.isEmpty()) null else "$piece\t-1.0"
            }
            if (lines.size < 100) return@runCatching null
            val temp = File(modelDir, "bpe.vocab.tmp")
            temp.writeText(lines.joinToString("\n") + "\n")
            if (!temp.renameTo(vocab)) {
                temp.copyTo(vocab, overwrite = true)
                temp.delete()
            }
            vocab.absolutePath
        }.getOrElse { error ->
            CrashLog.note(context, TAG, "could not build bpe.vocab: ${error.message}")
            null
        }
    }

    /** One "SMITHS :2.0" line per name, the format createStream takes. */
    fun buildBias(names: List<String>): String =
        names.filter { it.isNotBlank() }
            .joinToString("\n") { "$it :$BOOST" }

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
