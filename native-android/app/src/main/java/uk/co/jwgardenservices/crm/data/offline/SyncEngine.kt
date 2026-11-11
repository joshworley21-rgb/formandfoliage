package uk.co.jwgardenservices.crm.data.offline

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.filter.PostgrestFilterBuilder
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import uk.co.jwgardenservices.crm.data.local.CrmDatabase
import uk.co.jwgardenservices.crm.data.local.QueuedOpDto
import uk.co.jwgardenservices.crm.data.remote.SupabaseProvider

data class SyncResult(
    val synced: Int = 0,
    val refreshed: Boolean = false,
    val offline: Boolean = false,
    val error: String? = null,
)

/**
 * Replays the outbox, then refreshes the local cache.
 *
 * Ordering is strict and the queue stops at the first network failure, exactly
 * like the web app: a create followed by an update must reach the server in that
 * order. A permanent failure (validation, permissions) parks that one entry as
 * failed so the queue cannot wedge, keeps the change, and reports why. The user
 * can retry or discard it from the app.
 */
class SyncEngine(
    private val database: CrmDatabase,
    private val client: SupabaseClient = SupabaseProvider.client,
    private val refresh: suspend () -> Unit = {},
) {
    private val mutex = Mutex()
    private val outbox = database.outboxDao()

    suspend fun syncNow(): SyncResult = mutex.withLock {
        val flushed = flush()
        if (flushed.offline) {
            return@withLock SyncResult(synced = flushed.synced, offline = true, error = flushed.error)
        }

        var refreshed = false
        var error = flushed.error
        try {
            refresh()
            refreshed = true
        } catch (failure: Exception) {
            if (SyncErrors.isRetryable(failure)) {
                return@withLock SyncResult(
                    synced = flushed.synced,
                    offline = true,
                    error = flushed.error,
                )
            }
            error = failure.message ?: "Could not refresh from the server"
        }

        SyncResult(
            synced = flushed.synced,
            refreshed = refreshed,
            offline = false,
            error = error,
        )
    }

    private suspend fun flush(): FlushResult {
        var synced = 0
        var lastError: String? = null

        while (true) {
            val entry = outbox.next() ?: break
            try {
                val ops = OfflineJson.decodeFromString<List<QueuedOpDto>>(entry.opsJson)
                ops.forEach { execute(it) }
                outbox.delete(entry.id)
                synced += 1
            } catch (failure: Exception) {
                if (SyncErrors.isRetryable(failure)) {
                    return FlushResult(synced = synced, offline = true, error = lastError)
                }
                val reason = failure.message ?: "could not be saved"
                outbox.markFailed(entry.id, reason)
                lastError = "${entry.label}: $reason"
            }
        }

        return FlushResult(synced = synced, error = lastError)
    }

    private suspend fun execute(op: QueuedOpDto) {
        when (op.kind) {
            QueuedOpDto.INSERT -> client.from(op.requireTable()).insert(op.requirePayload())
            QueuedOpDto.UPDATE -> client.from(op.requireTable()).update(op.requirePayload()) {
                filter { applyMatch(op.match) }
            }
            QueuedOpDto.UPSERT -> client.from(op.requireTable()).upsert(op.requirePayload()) {
                op.onConflict?.let { onConflict = it }
            }
            QueuedOpDto.DELETE -> client.from(op.requireTable()).delete {
                filter { applyMatch(op.match) }
            }
            QueuedOpDto.RPC -> client.postgrest.rpc(
                op.fn ?: error("RPC without a function"),
                op.args ?: JsonObject(emptyMap()),
            )
            else -> error("Unknown queue operation: ${op.kind}")
        }
    }

    private fun PostgrestFilterBuilder.applyMatch(match: JsonObject?) {
        match?.forEach { (column, element) ->
            val primitive = element as? JsonPrimitive ?: return@forEach
            val boolean = primitive.booleanOrNull
            val long = primitive.longOrNull
            val double = primitive.doubleOrNull
            when {
                boolean != null -> eq(column, boolean)
                long != null -> eq(column, long)
                double != null -> eq(column, double)
                else -> eq(column, primitive.content)
            }
        }
    }

    private fun QueuedOpDto.requireTable(): String =
        table ?: error("${kind} operation without a table")

    private fun QueuedOpDto.requirePayload(): JsonObject =
        payload ?: error("${kind} operation without a payload")

    private data class FlushResult(
        val synced: Int = 0,
        val offline: Boolean = false,
        val error: String? = null,
    )
}
