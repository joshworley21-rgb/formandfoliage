package uk.co.jwgardenservices.crm.voice

import java.util.concurrent.atomic.AtomicInteger

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
    private val counter = AtomicInteger()

    fun pick(vararg options: String): String {
        val index = Math.floorMod(counter.getAndIncrement(), options.size)
        return options[index]
    }
}
