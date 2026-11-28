package uk.co.jwgardenservices.crm.voice

/**
 * Speed shaping for Kokoro's sentence-by-sentence playback.
 *
 * Each micro-clause gets a multiplier relative to a brisk base pace:
 * short lead-ins are lifted, figures are slowed so they are heard once, and
 * ordinary conversation stays neutral. A short PCM breath is inserted between
 * clauses by the player.
 */
object ClauseProsody {
    const val BASE_SPEED = 1.04f
    const val SHORT_LEAD_IN = 1.06f
    const val FIGURES = 0.90f
    const val STANDARD = 1.00f
    const val BREATH_MS = 140

    /**
     * Pace is applied by changing the playback rate, which also moves the pitch.
     * Full swings between clauses (0.90 to 1.06) can sound like two speakers, so
     * the steady voice keeps only this share of each swing.
     */
    const val STEADY_SHARE = 0.35f

    fun steady(multiplier: Float): Float = 1f + (multiplier - 1f) * STEADY_SHARE

    private val figure = Regex(
        "[£$€]|\\b\\d+(?:[.,]\\d+)?\\s*(?:pounds?|quid|pence|p|hours?|minutes?|mins?|days?)\\b|" +
            "\\b\\d{1,2}[:.]\\d{2}\\b|" +
            "\\b\\d{1,2}[/-]\\d{1,2}(?:[/-]\\d{2,4})?\\b|" +
            "\\b[A-Z]{1,2}\\d{1,2}[A-Z]?\\s?\\d[A-Z]{2}\\b|" +
            "\\b(?:jan|feb|mar|apr|may|jun|jul|aug|sept?|oct|nov|dec)[a-z]*\\b",
        RegexOption.IGNORE_CASE,
    )

    fun speedMultiplier(clause: String): Float {
        val words = clause.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        return when {
            words.isEmpty() -> STANDARD
            figure.containsMatchIn(clause) -> FIGURES
            words.size <= 3 -> SHORT_LEAD_IN
            else -> STANDARD
        }
    }

    /** True for a clause that should be followed by a breath. */
    fun needsBreath(clause: String): Boolean = clause.isNotBlank()
}
