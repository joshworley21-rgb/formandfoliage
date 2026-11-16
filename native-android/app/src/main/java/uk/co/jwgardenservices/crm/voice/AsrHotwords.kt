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
            var replaced = false
            for (name in names) {
                val parts = name.split(Regex("\\s+"))
                val end = index + parts.size
                if (end > tokens.size) continue
                val slice = tokens.subList(index, end)
                    .joinToString(" ") { it.trim(',', '.', '!', '?', ';', ':') }
                if (!similar(slice, name)) continue
                val tail = tokens[end - 1].takeLastWhile { it in ",.!?;:" }
                for (i in index until end) tokens[i] = ""
                tokens[index] = name + tail
                replaced = true
                break
            }
            if (!replaced) index += 1
        }
        return tokens.filter { it.isNotEmpty() }.joinToString(" ")
    }

    /** Uppercase, letters and spaces only; the tokenizer has no apostrophes. */
    private fun sanitize(name: String): String = name.uppercase()
        .replace(Regex("[^A-Z ]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    /** True only for a near miss: exact matches need no replacement. */
    private fun similar(heard: String, name: String): Boolean {
        if (heard == name) return false
        val allowed = when {
            name.length >= 9 -> 2
            name.length >= 5 -> 1
            else -> 0
        }
        if (allowed == 0) return false
        if (abs(heard.length - name.length) > allowed) return false
        return distance(heard, name) <= allowed
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
}
