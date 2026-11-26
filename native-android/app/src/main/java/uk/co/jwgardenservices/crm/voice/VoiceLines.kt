package uk.co.jwgardenservices.crm.voice

import kotlin.random.Random

/**
 * Spoken-variation helper.
 *
 * Saying "Logged three hours for Smiths, £90." in the exact same cadence
 * every time breaks the phone-call illusion, so the common replies have a
 * small pool of phrasings. [pick] rotates through the pool and avoids the
 * immediately previous line, which spaces repeats out without any randomness
 * to reproduce.
 */
object VoiceLines {
    /**
     * The owner's first name. Resolved from the user's profile/session by the
     * assistant service; the fallback keeps the greeting human before that
     * first call.
     */
    @Volatile
    private var ownerName: String = "Josh"

    val OWNER_NAME: String
        get() = ownerName

    private val recent = ArrayDeque<String>()

    /** Called once the signed-in profile is available. */
    fun setOwnerName(value: String?) {
        val clean = value?.trim()?.takeIf { it.isNotEmpty() } ?: "Josh"
        ownerName = clean.replaceFirstChar { it.uppercase() }
    }

    /** Random, avoiding the immediately previous line when there is a choice. */
    fun pick(vararg options: String): String {
        if (options.isEmpty()) return ""
        if (options.size == 1) return options[0]
        val fresh = options.filterNot { it == recent.lastOrNull() }
        val choice = (fresh.ifEmpty { options.toList() }).random(Random.Default)
        recent.addLast(choice)
        while (recent.size > 8) recent.removeFirst()
        return choice
    }

    /** True roughly once in [oneIn], for occasional conversational colour. */
    fun chance(oneIn: Int): Boolean = oneIn > 0 && Random.Default.nextInt(oneIn) == 0

    /** The opening line of a fresh call. */
    fun pickup(): String = pick(
        "Hello $OWNER_NAME, how are you?",
        "Morning $OWNER_NAME — what do you need?",
        "Hi $OWNER_NAME, what's first?",
        "Hello $OWNER_NAME. What can I do for you?",
        "Afternoon $OWNER_NAME — what's next?",
        "Hi $OWNER_NAME, how's the day going?",
    )

    /** A short close after a completed task, never after a bare lookup. */
    fun taskCheckIn(): String = pick(
        "All logged — anything else?",
        "Done. What's next?",
        "Sorted — need anything else?",
        "Right, that's done. What next?",
        "All sorted. Anything else while I'm here?",
        "That's in. What else?",
        "Finished that one. Where to now?",
    )

    /** Quiet acknowledgements while the owner is still talking. */
    val BACKCHANNELS = listOf("Mm-hmm.", "Right.", "Mm.")

    fun backchannel(): String = pick(*BACKCHANNELS.toTypedArray())

    /** Ending the call in the owner's own register. */
    fun signOff(): String = pick(
        "No worries, I'll leave you to it.",
        "Cheers, speak in a bit.",
        "Right, talk later.",
        "Okay, catch you later.",
        "No problem — shout if you need me.",
        "All right, I'll let you get on.",
        "Speak in a bit, $OWNER_NAME.",
    )

    /** A natural little acknowledgement when an answer needs no task nudge. */
    fun acknowledgement(): String = pick(
        "Okay.",
        "Right.",
        "Got it.",
        "Understood.",
        "Fair enough.",
    )
}
