package uk.co.jwgardenservices.crm.voice

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One line of the assistant conversation, kept across restarts. */
@Serializable
data class AssistantTurn(
    val fromUser: Boolean,
    val text: String,
    val at: Long = System.currentTimeMillis(),
)

/** A fact the owner asked the assistant to remember. */
@Serializable
data class MemoryFact(
    val text: String,
    val at: Long = System.currentTimeMillis(),
)

private val Context.assistantMemoryStore by preferencesDataStore(name = "assistant_memory")

/**
 * The assistant's memory: what it was told about the business, and the last
 * few turns of conversation.
 *
 * This is deliberately local. The CRM itself lives in Postgres, but "remember
 * that Mrs Smith's gate sticks" is a note to the phone, not a business record
 * that needs syncing or appearing on an invoice.
 */
object AssistantMemory {

    const val MAX_TURNS = 24
    const val MAX_FACTS = 50

    /**
     * The last sentence the assistant actually spoke, so "say again" can
     * replay it without re-running database logic. Deliberately in memory:
     * it only makes sense within the current call.
     */
    @Volatile
    var lastSpokenReply: String? = null

    private val FACTS = stringPreferencesKey("facts")
    private val TURNS = stringPreferencesKey("turns")
    private val CLOUD_CONVERSATION = booleanPreferencesKey("cloud_conversation")
    private val CALL_COUNT = intPreferencesKey("call_count")
    private val CALL_DAY = stringPreferencesKey("call_day")

    /**
     * How many calls today, 1-based, resetting when the calendar day rolls
     * over. The briefing shortens itself as the day goes on: full context
     * once, then "what are we logging?", then a word.
     */
    suspend fun noteCall(context: Context): Int {
        val today = java.time.LocalDate.now().toString()
        var count = 1
        context.assistantMemoryStore.edit { preferences ->
            val sameDay = preferences[CALL_DAY] == today
            count = (if (sameDay) preferences[CALL_COUNT] ?: 0 else 0) + 1
            preferences[CALL_COUNT] = count
            preferences[CALL_DAY] = today
        }
        return count
    }

    private val json = Json { ignoreUnknownKeys = true }

    private fun readFacts(raw: String?): List<MemoryFact> = raw
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { json.decodeFromString<List<MemoryFact>>(it) }.getOrNull() }
        .orEmpty()

    private fun readTurns(raw: String?): List<AssistantTurn> = raw
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { json.decodeFromString<List<AssistantTurn>>(it) }.getOrNull() }
        .orEmpty()

    fun factsFlow(context: Context): Flow<List<MemoryFact>> =
        context.assistantMemoryStore.data.map { readFacts(it[FACTS]) }

    suspend fun facts(context: Context): List<MemoryFact> =
        readFacts(context.assistantMemoryStore.data.map { it[FACTS] }.first())

    /** Store [text]; false when it is already remembered. */
    suspend fun remember(context: Context, text: String): Boolean {
        val clean = text.trim().trimEnd('.', '!', '?').replaceFirstChar { it.uppercase() }
        if (clean.isBlank()) return false
        var added = false
        context.assistantMemoryStore.edit { preferences ->
            val facts = readFacts(preferences[FACTS])
            if (facts.none { it.text.equals(clean, ignoreCase = true) }) {
                val updated = (facts + MemoryFact(clean)).takeLast(MAX_FACTS)
                preferences[FACTS] = json.encodeToString(updated)
                added = true
            }
        }
        return added
    }

    /** Remove facts containing [needle]; returns how many went. */
    suspend fun forget(context: Context, needle: String): Int {
        val lower = needle.trim().lowercase()
        if (lower.isBlank()) return 0
        var removed = 0
        context.assistantMemoryStore.edit { preferences ->
            val facts = readFacts(preferences[FACTS])
            val remaining = facts.filterNot { it.text.lowercase().contains(lower) }
            removed = facts.size - remaining.size
            if (removed > 0) preferences[FACTS] = json.encodeToString(remaining)
        }
        return removed
    }

    suspend fun forgetEverything(context: Context) {
        context.assistantMemoryStore.edit { preferences ->
            preferences.remove(FACTS)
        }
    }

    suspend fun turns(context: Context): List<AssistantTurn> =
        readTurns(context.assistantMemoryStore.data.map { it[TURNS] }.first())

    suspend fun appendTurn(context: Context, turn: AssistantTurn) {
        context.assistantMemoryStore.edit { preferences ->
            val turns = (readTurns(preferences[TURNS]) + turn).takeLast(MAX_TURNS)
            preferences[TURNS] = json.encodeToString(turns)
        }
    }

    suspend fun clearTurns(context: Context) {
        context.assistantMemoryStore.edit { preferences ->
            preferences.remove(TURNS)
        }
    }

    /**
     * Whether a sentence the rules cannot place may be sent to the cloud.
     *
     * Off by default: the free, instant path is the diary, money, hours and
     * jobs; open conversation costs the owner money per reply, so it is an
     * explicit opt-in.
     */
    fun cloudConversationFlow(context: Context): Flow<Boolean> =
        context.assistantMemoryStore.data.map { it[CLOUD_CONVERSATION] ?: false }

    suspend fun setCloudConversation(context: Context, enabled: Boolean) {
        context.assistantMemoryStore.edit { preferences ->
            preferences[CLOUD_CONVERSATION] = enabled
        }
    }
}

