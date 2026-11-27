package uk.co.jwgardenservices.crm.voice

import java.time.LocalDate

/**
 * Two recognisers hear each sentence: the streaming one live, and an offline one
 * over the whole utterance afterwards. Neither is always right (in real calls
 * the streaming one heard nothing on one sentence and the offline one nothing on
 * the next, and in two more each garbled a different word). This picks the one
 * that reads as something the owner could plausibly have said to the assistant:
 * a command the rules recognise, a client's name, a day, a time or a length.
 * On a tie the streaming one stands, as before.
 */
object TranscriptChooser {
    fun choose(
        streaming: String,
        offline: String,
        clientNames: List<String>,
        today: LocalDate = LocalDate.now(),
    ): String {
        val live = streaming.trim()
        val whole = offline.trim()
        if (live.isEmpty()) return whole
        if (whole.isEmpty()) return live
        if (live.equals(whole, ignoreCase = true)) return live
        return if (score(whole, clientNames, today) > score(live, clientNames, today)) whole else live
    }

    fun score(text: String, clientNames: List<String>, today: LocalDate = LocalDate.now()): Int {
        val words = text.lowercase().replace(Regex("[^a-z0-9' ]"), " ").split(Regex("\\s+")).filter { it.isNotBlank() }
        var score = 0
        val intent = IntentRules.classify(text)
        score += when {
            IntentRules.isConfident(intent.intent) && !intent.requiresCloud -> 3
            intent.intent != SpokenIntent.Other -> 1
            else -> 0
        }
        val spokenNames = clientNames
            .flatMap { it.lowercase().split(Regex("[^a-z]+")) }
            .filter { it.length >= 4 }
            .toSet()
        if (words.any { it.removeSuffix("'s") in spokenNames || it.removeSuffix("s") in spokenNames }) score += 2
        if (VoiceCalendar.periodIn(text, today) != null || VoiceCalendar.dateIn(text, today) != null) score += 1
        if (VoiceCalendar.timeIn(text) != null) score += 1
        if (VoiceCalendar.durationMinutesIn(text) != null) score += 1
        if (words.size < 2) score -= 2
        val last = words.lastOrNull()
        if (last != null && last.length == 1 && last != "a" && last != "i") score -= 1
        if (words.zipWithNext().any { (a, b) -> a == b && a.length > 3 }) score -= 1
        return score
    }
}
