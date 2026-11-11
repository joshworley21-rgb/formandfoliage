package uk.co.jwgardenservices.crm.data.offline

import kotlinx.serialization.json.Json

/** JSON config shared by the cache and the outbox. */
val OfflineJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    // Defaults must be written on updates too, otherwise turning a value back
    // to its default (a fixed price to 0, reminders off) would be omitted and
    // the server would keep the old value.
    encodeDefaults = true
}
