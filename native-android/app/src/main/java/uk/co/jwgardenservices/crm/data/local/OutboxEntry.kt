package uk.co.jwgardenservices.crm.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * One logical change waiting to reach Supabase. Mirrors the web app's
 * `QueuedChange`: a label for the UI, a timestamp for ordering, and a batch of
 * operations that are replayed in order.
 */
@Entity(tableName = "outbox", indices = [Index("createdAt")])
data class OutboxEntry(
    @PrimaryKey val id: String,
    val label: String,
    val createdAt: Long,
    val attempts: Int = 0,
    val opsJson: String,
)

/**
 * A single write inside an outbox entry. Kept as one flat JSON-friendly shape
 * rather than a sealed hierarchy so it can be encoded into a Room column and
 * replayed without a serializer registry.
 */
@Serializable
data class QueuedOpDto(
    val kind: String,
    val table: String? = null,
    val payload: JsonObject? = null,
    val match: JsonObject? = null,
    val onConflict: String? = null,
    val fn: String? = null,
    val args: JsonObject? = null,
) {
    companion object {
        const val INSERT = "insert"
        const val UPDATE = "update"
        const val UPSERT = "upsert"
        const val DELETE = "delete"
        const val RPC = "rpc"
    }
}
