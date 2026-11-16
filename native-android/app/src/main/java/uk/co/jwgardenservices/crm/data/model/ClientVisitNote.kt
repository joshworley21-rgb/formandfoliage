package uk.co.jwgardenservices.crm.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The thing the owner asked to be reminded of at a client's next visit —
 * "get compost", "check the fence post". One per client; writing a new one
 * replaces the old.
 *
 * Local only, like [VoiceAlias]: the synced clients table has no such column,
 * and an outbox payload carrying one would be rejected by Postgres. The
 * briefing reads it back at the start of the call, and the client editor
 * keeps it editable by hand.
 */
@Entity(tableName = "client_visit_notes")
data class ClientVisitNote(
    @PrimaryKey val clientId: String,
    val note: String,
    val updatedAt: String,
)
