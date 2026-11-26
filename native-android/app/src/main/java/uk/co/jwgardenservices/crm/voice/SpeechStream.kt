package uk.co.jwgardenservices.crm.voice

/**
 * Turns streamed model output into sentences that can be spoken as soon as
 * each one is complete, so the first words play while the rest is still being
 * generated.
 *
 * The runtime may emit either new tokens or the whole text so far. The mode is
 * settled on the second chunk: a chunk that starts with the first one means
 * cumulative output.
 */
class SpeechStream {
    private var raw = ""
    private var chunks = 0
    private var cumulative = false
    private var firstChunk = ""
    private var consumed = 0

    /** Everything heard so far, with reasoning blocks and control tokens removed. */
    val text: String get() = clean(raw).trim()

    /** Sentences completed by [chunk], oldest first. */
    fun push(chunk: String): List<String> {
        if (chunk.isEmpty()) return emptyList()
        chunks++
        when {
            chunks == 1 -> {
                firstChunk = chunk
                raw = chunk
            }
            chunks == 2 && chunk.length > firstChunk.length && chunk.startsWith(firstChunk) -> {
                cumulative = true
                raw = chunk
            }
            cumulative -> raw = chunk
            else -> raw += chunk
        }
        return drain(final = false)
    }

    /** The unfinished tail, once generation has stopped. */
    fun finish(): String? = drain(final = true).firstOrNull()

    private fun drain(final: Boolean): List<String> {
        val cleaned = withoutOpenTag(clean(raw))
        if (consumed > cleaned.length) consumed = cleaned.length
        val out = mutableListOf<String>()
        while (true) {
            val rest = cleaned.substring(consumed)
            val end = boundary(rest, final)
            if (end < 0) break
            val sentence = rest.substring(0, end).trim()
            consumed += end
            if (sentence.isNotEmpty()) out += sentence
            if (final) break
        }
        return out
    }

    private fun boundary(rest: String, final: Boolean): Int {
        if (final) return if (rest.isBlank()) -1 else rest.length
        var i = 0
        while (i < rest.length) {
            val c = rest[i]
            val closes = c == '.' || c == '!' || c == '?'
            if (closes && i + 1 < rest.length && rest[i + 1].isWhitespace() &&
                i + 1 >= MIN_CHARS && !abbreviation(rest, i)
            ) {
                var end = i + 1
                while (end < rest.length && rest[end].isWhitespace()) end++
                return end
            }
            i++
        }
        if (rest.length >= MAX_RUN) {
            val cut = rest.lastIndexOfAny(charArrayOf(',', ';', ':'), MAX_RUN)
            if (cut >= MIN_CHARS && cut + 1 < rest.length && rest[cut + 1] == ' ') return cut + 2
        }
        return -1
    }

    private fun abbreviation(text: String, dot: Int): Boolean {
        if (text[dot] != '.') return false
        val start = text.lastIndexOf(' ', dot).let { if (it < 0) 0 else it + 1 }
        val word = text.substring(start, dot).lowercase().trimStart('(', '"', '\'')
        return word in ABBREVIATIONS || (word.length == 1 && word[0].isLetter())
    }

    private fun withoutOpenTag(text: String): String {
        val open = text.lastIndexOf('<')
        return if (open >= 0 && text.indexOf('>', open) < 0) text.substring(0, open) else text
    }

    companion object {
        private const val MIN_CHARS = 14
        private const val MAX_RUN = 180

        private val ABBREVIATIONS = setOf(
            "mr", "mrs", "ms", "dr", "st", "approx", "etc", "vs", "e.g", "i.e", "a.m", "p.m",
        )
        private val thinkBlock = Regex("<think>.*?</think>", RegexOption.DOT_MATCHES_ALL)
        private val openThink = Regex("<think>.*", RegexOption.DOT_MATCHES_ALL)
        private val controlToken = Regex("<\\|[^>]*\\|>")
        private val markdown = Regex("[*_#`]+")

        fun clean(raw: String): String = raw
            .replace(thinkBlock, "")
            .replace(openThink, "")
            .replace(controlToken, "")
            .replace(markdown, "")
            .replace(Regex("\\s*\\n+\\s*"), " ")
    }
}
