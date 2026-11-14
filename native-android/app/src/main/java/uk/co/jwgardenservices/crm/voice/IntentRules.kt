package uk.co.jwgardenservices.crm.voice

/**
 * The deterministic fallback used when no GGUF model is installed, or when it
 * returns something that does not fit the schema.
 *
 * It is deliberately rule-based and boring: it must never invent a client or
 * an amount. It lives in its own file so the evaluation set can score it on a
 * plain JVM, and so tightening a keyword is a visible, testable change.
 */
object IntentRules {

    private const val MAX_TOPIC = 120

    fun classify(transcript: String): QwenIntentEngine.Intent {
        val text = transcript.lowercase()
        val kind = when {
            text.containsAny("owe me", "owes", "owed", "unpaid", "outstanding", "invoice", "been paid") ->
                QwenIntentEngine.Kind.WhoOwesMe
            text.containsAny("earn", "earned", "earnings", "takings", "taken this week", "made this week", "make this week", "income") ->
                QwenIntentEngine.Kind.WeeklyEarnings
            text.containsAny(
                "next job",
                "next appointment",
                "next visit",
                "who's next",
                "what's next",
                "whats next",
            ) -> QwenIntentEngine.Kind.NextJob
            text.containsAny("mark", "finished", "complete", "completed", "done with") &&
                !text.containsAny("how many", "hours", "hrs") -> QwenIntentEngine.Kind.MarkJobDone
            text.containsAny("hour", "hours", "hrs", "log", "worked", "work for") ->
                QwenIntentEngine.Kind.LogHours
            text.containsAny(
                "schedule",
                "diary",
                "booked",
                "anything on",
                "what's on",
                "whats on",
                "calendar",
            ) -> QwenIntentEngine.Kind.CheckSchedule
            text.containsAny("quote", "price", "cost", "charge", "rate", "how much") ->
                QwenIntentEngine.Kind.Quote
            else -> QwenIntentEngine.Kind.Other
        }

        // Only a live quote/pricing question leaves the phone. Everything the
        // CRM already knows is answered locally, even when the same words
        // ("how much do they owe") sound like a price question.
        val needsCloud = kind == QwenIntentEngine.Kind.Quote || text.containsAny(
            "weather",
            "forecast",
            "traffic",
            "supplier",
            "live price",
            "current price",
            "price list",
            "website",
            "online",
        )
        return QwenIntentEngine.Intent(kind, transcript.take(MAX_TOPIC), needsCloud)
    }

    private fun String.containsAny(vararg needles: String): Boolean = needles.any { contains(it) }
}
