package uk.co.jwgardenservices.crm.voice

import kotlinx.coroutines.CancellationException

/**
 * Holds a change back until the owner says yes. Every write goes through
 * [commit] with a plain description of what it will do; when confirmation is on
 * the description is read out as a question and the write waits here, and when
 * it is off the write just runs.
 */
class ChangeGate {
    /** Off only when the owner has turned "confirm before saving" off. */
    @Volatile
    var enabled: Boolean = true

    private class Pending(val summary: String, val block: suspend () -> String)

    private var pending: Pending? = null

    /** True while an approved change runs, so a save nested inside it does not ask again. */
    private var approved = false

    val isPending: Boolean get() = pending != null

    suspend fun commit(summary: String, block: suspend () -> String): String {
        if (!enabled || approved) return block()
        pending = Pending(summary, block)
        return "${TtsProsody.SLOW}$summary — shall I go ahead?"
    }

    /**
     * The owner's reply to the question. Null means it was not an answer, so the
     * sentence is a new command and the held change is dropped.
     */
    suspend fun answer(text: String, isNoise: (String) -> Boolean = { false }): String? {
        val waiting = pending ?: return null
        return when (ConfirmationGate.interpret(text)) {
            ConfirmationGate.Answer.Yes -> {
                pending = null
                approved = true
                try {
                    waiting.block()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    "Sorry, that didn't save: ${error.message ?: "something went wrong"}."
                } finally {
                    approved = false
                }
            }
            ConfirmationGate.Answer.No -> {
                pending = null
                "${TtsProsody.FAST}Okay, I've left it. What should it be instead?"
            }
            ConfirmationGate.Answer.Other ->
                if (isNoise(text)) {
                    "${TtsProsody.SLOW}Sorry, ${waiting.summary}. Shall I go ahead?"
                } else {
                    pending = null
                    null
                }
        }
    }

    fun clear() {
        pending = null
    }
}
