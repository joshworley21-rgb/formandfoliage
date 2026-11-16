package uk.co.jwgardenservices.crm.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A nickname the owner actually says for a client — "the rectory", "Smiths",
 * "the pub" — kept on this phone only.
 *
 * This is deliberately NOT a column on [Client]: the clients table is synced
 * from Postgres, whose schema has no such column, and every outbox payload
 * would then be rejected by the server. Voice-specific data lives beside the
 * synced tables instead.
 */
@Entity(
    tableName = "voice_aliases",
    indices = [Index("clientId"), Index("alias")],
)
data class VoiceAlias(
    @PrimaryKey val id: String,
    val clientId: String,
    /** Lowercased for matching; the original wording is not needed. */
    val alias: String,
)
