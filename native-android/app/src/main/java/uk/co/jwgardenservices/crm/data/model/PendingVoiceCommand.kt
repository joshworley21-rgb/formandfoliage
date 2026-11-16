package uk.co.jwgardenservices.crm.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Words that arrived while the phone had no signal and no local rule could
 * act on them — an open-ended quote, a survey description, a long note.
 *
 * The transcript is kept exactly as heard (the normaliser is for matching,
 * not for storage) and posted through `voice-route` once the phone is back on
 * data. It only exists on this device; nothing is sent until the worker runs.
 */
@Entity(tableName = "pending_voice")
data class PendingVoiceCommand(
    @PrimaryKey val id: String,
    val transcript: String,
    /** ISO-8601 instant, for ordering and for the dated client note. */
    val createdAt: String,
    /** Best guess at the client being spoken about, when one was matched. */
    val clientId: String? = null,
    val clientName: String? = null,
    /** True once the cloud has processed it (or it was dismissed). */
    val processed: Boolean = false,
    /** The cloud's reply, kept for review and for the crash log. */
    val outcome: String? = null,
)
