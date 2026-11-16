package uk.co.jwgardenservices.crm.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * Everything a call changed, kept for the post-call summary card.
 *
 * Each entry carries its own undo, closing over the repository through the
 * service locator rather than over the runner instance, so "Undo" still works
 * after the service has stopped itself. A new call clears the ledger: the
 * card verifies the latest call, not a running history.
 */
object CallLedger {

    data class Entry(
        val id: String,
        val label: String,
        /** Null for actions that cannot be reversed (an emailed invoice). */
        val undo: (suspend () -> String)?,
    )

    private val _actions = MutableStateFlow<List<Entry>>(emptyList())
    val actions: StateFlow<List<Entry>> = _actions.asStateFlow()

    private val ids = AtomicLong()

    fun record(label: String, undo: (suspend () -> String)? = null) {
        _actions.value = _actions.value + Entry(
            id = ids.incrementAndGet().toString(),
            label = label,
            undo = undo,
        )
    }

    /** Run an entry's undo and drop it from the card. Null when unknown. */
    suspend fun undo(id: String): String? {
        val entry = _actions.value.firstOrNull { it.id == id } ?: return null
        val reply = entry.undo?.invoke()
        _actions.value = _actions.value.filterNot { it.id == id }
        return reply ?: "Already gone."
    }

    fun clear() {
        _actions.value = emptyList()
    }
}
