package uk.co.jwgardenservices.crm.data.repository

import androidx.room.withTransaction
import java.util.UUID
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import uk.co.jwgardenservices.crm.data.local.QueuedOpDto
import uk.co.jwgardenservices.crm.data.model.RunningCost
import uk.co.jwgardenservices.crm.data.model.RunningCostDraft
import uk.co.jwgardenservices.crm.data.offline.OfflineJson
import uk.co.jwgardenservices.crm.domain.round2

/**
 * Running costs follow the same offline-first path as every other record:
 * write Room, queue the server operation, and let [SyncEngine] replay it.
 *
 * [existing] is the row being corrected. A new cost gets a client-side UUID so
 * the list can render before the server has answered.
 */
suspend fun CrmRepository.saveRunningCost(
    draft: RunningCostDraft,
    existing: RunningCost? = null,
) {
    val uid = userId()
    val row = RunningCost(
        id = existing?.id ?: UUID.randomUUID().toString(),
        userId = uid,
        name = draft.name.trim(),
        category = draft.category,
        amount = round2(draft.amount),
        frequency = draft.frequency,
        notes = draft.notes.clean(),
        createdAt = existing?.createdAt,
        updatedAt = null,
    )
    database.withTransaction {
        runningCosts.upsert(row)
        val payload = OfflineJson.encodeToJsonElement(
            RunningCost.serializer(),
            row.copy(createdAt = null, updatedAt = null),
        ).jsonObject
        enqueue(
            label = if (existing == null) {
                "New running cost \"${row.name}\""
            } else {
                "Running cost \"${row.name}\" update"
            },
            ops = listOf(
                QueuedOpDto(
                    kind = if (existing == null) QueuedOpDto.INSERT else QueuedOpDto.UPDATE,
                    table = "running_costs",
                    payload = payload,
                    match = if (existing == null) null else idMatch(row.id),
                ),
            ),
        )
    }
    onQueued()
}

suspend fun CrmRepository.deleteRunningCost(id: String) {
    database.withTransaction {
        runningCosts.deleteById(id)
        enqueue(
            label = "Delete running cost",
            ops = listOf(
                QueuedOpDto(
                    kind = QueuedOpDto.DELETE,
                    table = "running_costs",
                    match = idMatch(id),
                ),
            ),
        )
    }
    onQueued()
}
