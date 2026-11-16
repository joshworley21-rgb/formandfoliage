package uk.co.jwgardenservices.crm.voice

import kotlinx.coroutines.flow.first
import uk.co.jwgardenservices.crm.data.repository.ServiceLocator
import kotlin.math.abs

/**
 * Post-recognition correction for the words the recogniser gets wrong most
 * often: client names.
 *
 * The streaming Zipformer in this app uses a 500-piece BPE vocabulary without
 * the merge table, so sherpa-onnx's token-level hotwords cannot be encoded
 * safely. Instead the names are rebuilt from the Room cache at each call and
 * close matches in the final transcript are replaced with the canonical name.
 * A misspelt name then still reaches the intent rules and the transcript.
 */
object AsrHotwords {

    suspend fun names(): List<String> = runCatching {
        ServiceLocator.crmRepository.observeClients().first()
    }.getOrDefault(emptyList())
        .map { it.name.trim().uppercase() }
        .filter { it.length in 3..40 }
        .distinct()

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
