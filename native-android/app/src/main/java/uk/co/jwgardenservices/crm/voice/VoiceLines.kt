package uk.co.jwgardenservices.crm.voice

import kotlin.random.Random

/**
 * Spoken-variation helper.
 *
 * Saying "Logged three hours for Smiths, £90." in the exact same cadence
 * every time breaks the phone-call illusion, so the common replies have a
 * small pool of phrasings. [pick] rotates deterministically through the pool
 * across everything the session says, which spaces repeats out without any
 * randomness to reproduce.
 */
object VoiceLines {
    /** The owner's name, used in greetings and conversational pivots. */
    const val OWNER_NAME = "Josh"

    private val recent = ArrayDeque<String>()

    /** Random, avoiding the immediately previous line when there is a choice. */
    fun pick(vararg options: String): String {
        if (options.isEmpty()) return ""
        if (options.size == 1) return options[0]
        val fresh = options.filterNot { it == recent.lastOrNull() }
        val choice = (fresh.ifEmpty { options.toList() }).random(Random.Default)
        recent.addLast(choice)
        while (recent.size > 6) recent.removeFirst()
        return choice
    }

    /** True roughly once in [oneIn], for occasional conversational colour. */
    fun chance(oneIn: Int): Boolean = oneIn > 0 && Random.Default.nextInt(oneIn) == 0
}
