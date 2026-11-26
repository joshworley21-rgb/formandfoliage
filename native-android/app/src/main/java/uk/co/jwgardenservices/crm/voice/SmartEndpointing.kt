package uk.co.jwgardenservices.crm.voice

/**
 * Word-aware end-of-turn timing.
 *
 * Silero VAD decides whether speech has stopped; this decides how long to
 * trust that silence. The live partial is the cheapest signal available:
 *
 *  - a complete question or crisp command: 350 ms
 *  - ordinary speech: 800 ms
 *  - a hesitation or trailing conjunction: 1,000 ms
 *  - Bluetooth adds 100 ms for the car kit's slower, noisier path
 *  - a noisy background (engine, road) adds 150 ms
 *
 * Moonshine has no streaming partials, so its loop uses the patient wait.
 */
object SmartEndpointing {
    const val FAST_MS = 350L
    const val NORMAL_MS = 800L
    const val PATIENT_MS = 1_000L
    const val BLUETOOTH_EXTRA_MS = 100L

    /** Engine and road noise make the VAD flicker mid-sentence; give it room. */
    const val NOISY_EXTRA_MS = 150L
    const val NOISY_FLOOR = 0.02f

    private val hesitationTail = Regex(
        "\\b(?:um+|uh+|er+|erm|hmm+|and|plus|also|the|a|an|for|at|with|to|of|but|or|then|" +
            "because|so|like|just|about)\\s*$",
        RegexOption.IGNORE_CASE,
    )

    private val crispTail = Regex(
        "\\b(?:yes|yeah|yep|no|nope|okay|ok|cancel|stop|done|thanks|cheers|" +
            "tomorrow|today|tonight|now|next|first|last|week|month|hours?|minutes?|" +
            "invoice|invoices|schedule|rota|clients?|money|profit|health|that|it|me|" +
            "note|ready|here|there)\\s*$",
        RegexOption.IGNORE_CASE,
    )

    private val completeQuestion = Regex(
        "^(?:what|when|where|who|why|how|which|is|are|do|does|did|can|could|would|will)\\b" +
            ".*\\b(?:today|tomorrow|tonight|this week|next week|monday|tuesday|wednesday|" +
            "thursday|friday|saturday|sunday)\\s*$",
        RegexOption.IGNORE_CASE,
    )

    fun waitMs(
        partial: String,
        onBluetooth: Boolean = false,
        noiseFloor: Float = 0f,
    ): Long {
        val clean = partial.trim().trimEnd('.', ',', '!', ';', ':').lowercase()
        val base = when {
            clean.isEmpty() -> NORMAL_MS
            hesitationTail.containsMatchIn(clean) -> PATIENT_MS
            crispTail.containsMatchIn(clean) || completeQuestion.containsMatchIn(clean) -> FAST_MS
            else -> NORMAL_MS
        }
        return base +
            (if (onBluetooth) BLUETOOTH_EXTRA_MS else 0L) +
            (if (noiseFloor >= NOISY_FLOOR) NOISY_EXTRA_MS else 0L)
    }
}
