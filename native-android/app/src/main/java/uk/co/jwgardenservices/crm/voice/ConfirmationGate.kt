package uk.co.jwgardenservices.crm.voice

/**
 * Reads a spoken answer to "shall I go ahead?". It is deliberately strict about
 * yes: a change is only saved when the answer is nothing but agreement, so a
 * sentence that merely contains "yeah" ("yeah, and also add a note...") is
 * treated as a new command, never as approval.
 */
object ConfirmationGate {
    enum class Answer { Yes, No, Other }

    private val strongYes = setOf(
        "yes", "yeah", "yep", "yup", "yea", "sure", "ok", "okay", "correct", "confirm", "confirmed",
        "ahead", "proceed", "affirmative", "aye", "absolutely", "definitely", "perfect", "lovely",
        "brilliant", "right", "fine", "good", "great",
    )

    /** Words that may sit around a yes without changing it. */
    private val fillers = setOf(
        "please", "do", "it", "go", "on", "that", "that's", "thats", "is", "sounds", "then", "thanks",
        "thank", "you", "mate", "cheers", "josh", "sorted", "just", "and", "so", "all", "now",
    )

    private val noWords = setOf(
        "no", "nope", "nah", "cancel", "stop", "don't", "dont", "wrong", "scrap", "forget", "never",
        "leave", "wait", "hold", "incorrect", "not",
    )

    /** Agreement with no single strong word in it. */
    private val agreeingPhrases = setOf("do it", "please do", "do it please", "go for it", "make it so", "go on then")

    /** Weak agreement words only count in a very short answer. */
    private val weakYes = setOf("right", "fine", "good", "great")

    fun interpret(text: String): Answer {
        val words = text.lowercase().replace(Regex("[^a-z' ]"), " ").split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return Answer.Other
        if (words.joinToString(" ") in agreeingPhrases) return Answer.Yes

        val saysNo = words.any { it in noWords }
        val saysYes = words.any { it in strongYes }
        if (saysNo && !saysYes) {
            // "No." or "cancel that" is a no; "no, make it three hours instead
            // and also..." is a correction, which goes through as a new command.
            return if (words.size <= 4) Answer.No else Answer.Other
        }
        if (saysYes && !saysNo && words.size <= 6 && words.all { it in strongYes || it in fillers }) {
            val strong = words.filter { it in strongYes }
            if (strong.all { it in weakYes } && words.size > 3) return Answer.Other
            return Answer.Yes
        }
        return Answer.Other
    }
}
