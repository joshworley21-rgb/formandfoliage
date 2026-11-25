package uk.co.jwgardenservices.crm.data.repository

import androidx.room.withTransaction
import java.time.Instant
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import uk.co.jwgardenservices.crm.data.local.QueuedOpDto
import uk.co.jwgardenservices.crm.data.model.BusinessProfileResult
import uk.co.jwgardenservices.crm.data.model.Settings
import uk.co.jwgardenservices.crm.data.offline.OfflineJson
import uk.co.jwgardenservices.crm.data.website.WebsiteProfileReader

/**
 * Website-scanning lives on the server, behind the signed-in check, because it
 * spends an OpenAI token and fetches a URL on the owner's behalf. The result
 * is written to the same settings row the assistant already reads before every
 * voice turn, so a learned profile reaches the phone through the normal sync.
 */
suspend fun CrmRepository.learnBusinessProfile(url: String): BusinessProfileResult {
    val text = invokeFunction(
        "business-profile",
        buildJsonObject { put("url", url.trim()) },
    )
    val body = OfflineJson.parseToJsonElement(text).jsonObject
    val profile = body["profile"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    if (profile.isBlank()) error("The website did not give enough to learn from")

    val source = body["sourceUrl"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().ifBlank {
        url.trim()
    }
    val pages = body["pages"]?.jsonArray
        ?.mapNotNull { it.jsonPrimitive.contentOrNull }
        .orEmpty()
    val fetchedAt = body["fetchedAt"]?.jsonPrimitive?.contentOrNull

    saveBusinessKnowledge(profile, source, fetchedAt)
    return BusinessProfileResult(profile, source, pages, fetchedAt)
}

/**
 * The free path: read the public pages on the phone and keep the facts it can
 * see. No OpenAI call and no API key, so it works when the owner does not want
 * a bill; the AI scan can replace this profile later.
 */
suspend fun CrmRepository.learnBusinessProfileLocally(url: String): BusinessProfileResult {
    val result = WebsiteProfileReader.read(url)
    saveBusinessKnowledge(result.profile, result.sourceUrl)
    return BusinessProfileResult(
        profile = result.profile,
        sourceUrl = result.sourceUrl,
        pages = result.pages,
        fetchedAt = Instant.now().toString(),
    )
}

/**
 * Store a profile written by the website scan, or corrected by hand in
 * Settings. A blank value clears it; Secretary then falls back to the profile
 * bundled with the app.
 */
suspend fun CrmRepository.saveBusinessKnowledge(
    profile: String,
    sourceUrl: String?,
    updatedAt: String? = null,
) {
    val uid = userId()
    val existing = settings.get()
    val collectedSource = sourceUrl.clean()?.trimEnd('/')
    val base = existing ?: Settings(userId = uid)
    val cleaned = profile.trim().take(4_000)
    val row = base.copy(
        // The address that was actually scanned is the one worth keeping, even
        // if the form had not been saved yet.
        publicSiteUrl = collectedSource ?: base.publicSiteUrl,
        // Empty rather than null: the outbox omits nulls, which would leave
        // the previous profile alive on the server after a reset.
        businessProfile = cleaned,
        businessProfileSource = collectedSource,
        businessProfileUpdatedAt = if (cleaned.isBlank()) null else {
            updatedAt?.takeIf { it.isNotBlank() } ?: Instant.now().toString()
        },
    )
    database.withTransaction {
        settings.upsert(row)
        enqueue(
            label = if (cleaned.isBlank()) "Reset business knowledge" else "Business knowledge update",
            ops = listOf(
                QueuedOpDto(
                    kind = QueuedOpDto.UPSERT,
                    table = "settings",
                    payload = OfflineJson.encodeToJsonElement(
                        Settings.serializer(),
                        row,
                    ).jsonObject,
                    onConflict = "user_id",
                ),
            ),
        )
    }
    onQueued()
}
