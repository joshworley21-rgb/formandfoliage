package uk.co.jwgardenservices.crm.voice

import android.content.Context

/**
 * The distilled business profile bundled as an asset, written once from the
 * website instead of dumping raw HTML into prompts: who the business is, the
 * four service families with their clean line-item names, the villages and
 * estates on the patch, and the trade words the recogniser should know.
 *
 * Two consumers:
 *  - [AssistantContext] appends it to every model snapshot, so the local
 *    rules and the cloud both speak about the right services and places.
 *  - [AsrHotwords] parses [vocabulary] and [tradeTerms] into recogniser
 *    biasing, so "Berryfields" and "ceanothus" survive a noisy mic.
 *
 * The asset is a couple of kilobytes; it is read once per process.
 */
object BusinessBrain {

    private const val ASSET = "business_knowledge.txt"

    @Volatile
    private var cached: String? = null

    /** The full profile text, or null when the asset is missing or unreadable. */
    fun load(context: Context): String? {
        cached?.let { return it }
        return runCatching {
            context.assets.open(ASSET).bufferedReader().use { it.readText() }.trim()
        }.getOrNull()?.also { cached = it }
    }

    /** Everything after the "VOCABULARY:" marker, one entry per comma. */
    fun vocabulary(profile: String?): List<String> = section(profile, "VOCABULARY")

    /** Everything after the "TRADE TERMS:" marker, one entry per comma. */
    fun tradeTerms(profile: String?): List<String> = section(profile, "TRADE TERMS")

    private fun section(profile: String?, marker: String): List<String> {
        if (profile.isNullOrBlank()) return emptyList()
        val line = profile.lineSequence()
            .firstOrNull { it.trim().uppercase() == marker || it.trim().startsWith("$marker:") }
            ?: return emptyList()
        return line.substringAfter(':', "")
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }
}

/**
 * Maps shorthand spoken work ("maintenance", "hedge", "turf") onto the clean
 * service line-item names from the website taxonomy, so a visit logged by
 * voice carries the same description into the Room database, the PDF invoice
 * and the client-facing email — while the spoken reply stays short.
 */
object TradeTerms {

    /** Spoken shorthand → invoice line item, longest keys first. */
    private val LINE_ITEMS = listOf(
        "hedge" to "Hedge trimming",
        "border" to "Border weeding",
        "weeding" to "Border weeding",
        "patio" to "Patios & paths",
        "path" to "Patios & paths",
        "fence" to "Fencing",
        "decking" to "Decking",
        "sleeper" to "Sleeper beds",
        "pergola" to "Pergolas",
        "turf" to "New turf",
        "wildflower" to "Wildflower turf",
        "winter tidy" to "Winter tidy",
        "car park" to "Car park weed control",
        "leaf" to "Leaf & litter clearance",
        "litter" to "Leaf & litter clearance",
        "lawn" to "Lawn cut",
        "mow" to "Lawn cut",
        "maintenance" to "Scheduled Garden Maintenance",
    )

    /**
     * The line item for a spoken sentence, or null when nothing trade-specific
     * was said and the generic description is the honest one.
     */
    fun lineItemFor(text: String): String? {
        val lower = text.lowercase()
        return LINE_ITEMS.firstOrNull { (needle, _) -> lower.contains(needle) }?.second
    }
}
