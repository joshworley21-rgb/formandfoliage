package uk.co.jwgardenservices.crm.voice

/** Small helpers for the typed chat, kept free of Android so they can be tested. */
object ChatHints {
    /**
     * Tap-to-answer buttons for a question the assistant just asked, so a
     * confirmation never has to be typed.
     */
    fun quickReplies(lastAssistantText: String?): List<String> {
        val text = lastAssistantText?.trim()?.lowercase().orEmpty()
        if (text.isEmpty() || !text.endsWith("?")) return emptyList()
        return when {
            text.contains("go ahead") || text.contains("shall i") || text.contains("should i") ||
                text.contains("want me to") || text.contains("would you like me to") ||
                text.contains("is that right") || text.contains("is that ok") -> listOf("Yes", "No")
            else -> emptyList()
        }
    }

    /** Typed text is trimmed and capped so a stray paste cannot flood the pipeline. */
    fun clean(typed: String): String =
        typed.replace(Regex("\\s+"), " ").trim().take(500)
}
