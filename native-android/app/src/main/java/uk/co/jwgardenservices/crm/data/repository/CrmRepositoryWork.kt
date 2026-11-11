package uk.co.jwgardenservices.crm.data.repository


// Jobs, series, visits, job tasks and job photos, split out of
// CrmRepository.kt to keep both halves readable. These are extension
// functions on the same class: same behaviour, same call sites.


import androidx.room.withTransaction
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.query.PostgrestRequestBuilder
import io.github.jan.supabase.storage.storage
import io.ktor.http.ContentType
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import java.io.IOException
import kotlin.time.Duration.Companion.seconds
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import uk.co.jwgardenservices.crm.data.local.CrmDatabase
import uk.co.jwgardenservices.crm.data.local.OutboxEntry
import uk.co.jwgardenservices.crm.data.local.QueuedOpDto
import uk.co.jwgardenservices.crm.data.model.Client
import uk.co.jwgardenservices.crm.data.model.ClientDraft
import uk.co.jwgardenservices.crm.data.model.CreateInvoiceResult
import uk.co.jwgardenservices.crm.data.model.Enquiry
import uk.co.jwgardenservices.crm.data.model.Invoice
import uk.co.jwgardenservices.crm.data.model.InvoiceEmailEvent
import uk.co.jwgardenservices.crm.data.model.InvoiceItem
import uk.co.jwgardenservices.crm.data.model.JobDraft
import uk.co.jwgardenservices.crm.data.model.JobPhoto
import uk.co.jwgardenservices.crm.data.model.JobPhotoWithUrl
import uk.co.jwgardenservices.crm.data.model.JobTask
import uk.co.jwgardenservices.crm.data.model.NewInvoiceInput
import uk.co.jwgardenservices.crm.data.model.RebuildInvoiceResult
import uk.co.jwgardenservices.crm.data.model.Quote
import uk.co.jwgardenservices.crm.data.model.QuoteDraft
import uk.co.jwgardenservices.crm.data.model.QuoteItem
import uk.co.jwgardenservices.crm.data.model.ScheduledJob
import uk.co.jwgardenservices.crm.data.model.SendInvoiceResult
import uk.co.jwgardenservices.crm.data.model.SendQuoteResult
import uk.co.jwgardenservices.crm.data.model.SeriesDraft
import uk.co.jwgardenservices.crm.data.model.SeriesEditInput
import uk.co.jwgardenservices.crm.data.model.Settings
import uk.co.jwgardenservices.crm.data.model.StaffClientRow
import uk.co.jwgardenservices.crm.data.model.SettingsDraft
import uk.co.jwgardenservices.crm.data.model.TeamMember
import uk.co.jwgardenservices.crm.data.model.Visit
import uk.co.jwgardenservices.crm.data.model.VisitDraft
import uk.co.jwgardenservices.crm.data.model.WorkspaceRow
import uk.co.jwgardenservices.crm.domain.atTime
import uk.co.jwgardenservices.crm.domain.Frequency
import uk.co.jwgardenservices.crm.domain.QuoteLine
import uk.co.jwgardenservices.crm.domain.SeriesJob
import uk.co.jwgardenservices.crm.domain.editableVisits
import uk.co.jwgardenservices.crm.domain.lineAmount
import uk.co.jwgardenservices.crm.domain.localDateOf
import uk.co.jwgardenservices.crm.domain.parseDate
import uk.co.jwgardenservices.crm.domain.parseTimestamp
import uk.co.jwgardenservices.crm.domain.quoteTotals
import uk.co.jwgardenservices.crm.domain.retimedVisits
import uk.co.jwgardenservices.crm.domain.round2
import uk.co.jwgardenservices.crm.domain.seriesShift
import uk.co.jwgardenservices.crm.domain.step
import uk.co.jwgardenservices.crm.data.offline.OfflineJson
import uk.co.jwgardenservices.crm.data.remote.SupabaseProvider

suspend fun CrmRepository.refreshJobTasks(jobId: String) {
        val rows = runCatching {
            client.from("job_tasks").select {
                filter { eq("job_id", jobId) }
                order("position", order = Order.ASCENDING)
                order("created_at", order = Order.ASCENDING)
            }.decodeList<JobTask>()
        }.getOrNull() ?: return
        database.withTransaction {
            jobTasks.deleteByJob(jobId)
            jobTasks.upsertAll(rows)
        }
    }

suspend fun CrmRepository.addJobTask(jobId: String, title: String, position: Int) {
        val row = JobTask(
            id = UUID.randomUUID().toString(),
            userId = workspaceOwnerId(),
            jobId = jobId,
            title = title.trim(),
            position = position,
        )
        database.withTransaction {
            jobTasks.upsert(row)
            enqueue(
                label = "Add job task",
                ops = listOf(
                    QueuedOpDto(
                        kind = QueuedOpDto.INSERT,
                        table = "job_tasks",
                        payload = OfflineJson.encodeToJsonElement(
                            JobTask.serializer(),
                            row,
                        ).jsonObject,
                    ),
                ),
            )
        }
        onQueued()
    }

suspend fun CrmRepository.setJobTaskDone(task: JobTask, done: Boolean) {
        database.withTransaction {
            jobTasks.upsert(task.copy(done = done))
            enqueue(
                label = "Job task",
                ops = listOf(
                    QueuedOpDto(
                        kind = QueuedOpDto.UPDATE,
                        table = "job_tasks",
                        payload = buildJsonObject { put("done", done) },
                        match = idMatch(task.id),
                    ),
                ),
            )
        }
        onQueued()
    }

suspend fun CrmRepository.deleteJobTask(id: String) {
        database.withTransaction {
            jobTasks.deleteById(id)
            enqueue(
                label = "Delete job task",
                ops = listOf(
                    QueuedOpDto(
                        kind = QueuedOpDto.DELETE,
                        table = "job_tasks",
                        match = idMatch(id),
                    ),
                ),
            )
        }
        onQueued()
    }

    /** Recent photos across every job for one client, for the photo history. */
suspend fun CrmRepository.clientPhotos(clientId: String, limit: Long = 24): List<JobPhotoWithUrl> {
        val jobIds = jobs.observeAll().first()
            .filter { it.clientId == clientId }
            .map { it.id }
        if (jobIds.isEmpty()) return emptyList()
        val rows = client.from("job_photos").select {
            filter { isIn("job_id", jobIds) }
            order("created_at", order = Order.DESCENDING)
            limit(limit)
        }.decodeList<JobPhoto>()
        return rows.map { photo ->
            val url = runCatching {
                client.storage.from("job-photos").createSignedUrl(photo.storagePath, 3600.seconds)
            }.getOrNull()
            JobPhotoWithUrl(photo, url)
        }
    }

suspend fun CrmRepository.jobPhotos(jobId: String): List<JobPhotoWithUrl> {
        val rows = client.from("job_photos").select {
            filter { eq("job_id", jobId) }
            order("created_at", order = Order.DESCENDING)
        }.decodeList<JobPhoto>()
        return rows.map { photo ->
            val url = runCatching {
                client.storage.from("job-photos").createSignedUrl(photo.storagePath, 3600.seconds)
            }.getOrNull()
            JobPhotoWithUrl(photo, url)
        }
    }

suspend fun CrmRepository.uploadJobPhoto(
        jobId: String,
        bytes: ByteArray,
        mimeType: String,
        extension: String,
        caption: String? = null,
    ) {
        val owner = workspaceOwnerId()
        val path = "$owner/$jobId/${UUID.randomUUID()}.$extension"
        try {
            client.storage.from("job-photos").upload(path, bytes) {
                upsert = false
                contentType = ContentType.parse(mimeType)
            }
        } catch (failure: IOException) {
            throw IllegalStateException(
                "Adding a photo needs a connection. Try again when you have signal.",
                failure,
            )
        }
        val row = JobPhoto(
            id = UUID.randomUUID().toString(),
            userId = owner,
            jobId = jobId,
            storagePath = path,
            caption = caption.clean(),
        )
        client.from("job_photos").insert(row)
    }

suspend fun CrmRepository.updateJobPhotoCaption(id: String, caption: String?) {
        val cleaned = caption.clean()
        client.from("job_photos").update(
            buildJsonObject {
                if (cleaned == null) put("caption", JsonNull) else put("caption", cleaned)
            },
        ) {
            filter { eq("id", id) }
        }
    }

suspend fun CrmRepository.deleteJobPhoto(id: String, path: String) {
        client.storage.from("job-photos").delete(listOf(path))
        client.from("job_photos").delete {
            filter { eq("id", id) }
        }
    }

suspend fun CrmRepository.saveVisit(draft: VisitDraft) {
        val uid = userId()
        val id = draft.id ?: UUID.randomUUID().toString()
        val existing = draft.id?.let { visits.findById(it) }

        // A visit on a sent invoice is not a working document; the invoice keeps
        // its own copy of the money. A draft invoice may still be corrected.
        val invoiceStatus = existing?.invoiceId?.let { invoices.findById(it)?.status }
        val locked = existing?.billed == true && invoiceStatus != "draft"

        val row = Visit(
            id = id,
            userId = uid,
            clientId = if (locked) existing!!.clientId else draft.clientId,
            visitDate = if (locked) existing!!.visitDate else draft.visitDate,
            hours = if (locked) existing!!.hours else if (draft.missed) 0.0 else draft.hours,
            description = draft.description.clean(),
            materialsCost = if (locked) {
                existing!!.materialsCost
            } else if (draft.missed) {
                0.0
            } else {
                draft.materialsCost
            },
            materialsNote = if (locked) {
                existing!!.materialsNote
            } else if (draft.missed) {
                null
            } else {
                draft.materialsNote.clean()
            },
            amount = if (locked) existing!!.amount else if (draft.missed) 0.0 else round2(draft.amount),
            billed = existing?.billed ?: false,
            invoiceId = existing?.invoiceId,
            missed = if (locked) existing!!.missed else draft.missed,
            createdAt = existing?.createdAt,
            updatedAt = null,
        )

        database.withTransaction {
            visits.upsert(row)

            val ops = mutableListOf<QueuedOpDto>()
            val payload = OfflineJson.encodeToJsonElement(
                Visit.serializer(),
                row.copy(createdAt = null, updatedAt = null),
            ).jsonObject
            ops += if (existing == null) {
                QueuedOpDto(kind = QueuedOpDto.INSERT, table = "visits", payload = payload)
            } else {
                QueuedOpDto(
                    kind = QueuedOpDto.UPDATE,
                    table = "visits",
                    payload = payload,
                    match = idMatch(id),
                )
            }

            draft.completesJobId?.let { jobId ->
                jobs.findById(jobId)?.let { job ->
                    val updatedJob = job.copy(
                        status = draft.jobOutcome,
                        visitId = id,
                        updatedAt = null,
                    )
                    jobs.upsert(updatedJob)
                    ops += QueuedOpDto(
                        kind = QueuedOpDto.UPDATE,
                        table = "scheduled_jobs",
                        payload = OfflineJson.encodeToJsonElement(
                            ScheduledJob.serializer(),
                            updatedJob.copy(createdAt = null, updatedAt = null),
                        ).jsonObject,
                        match = idMatch(jobId),
                    )
                }
            }

            enqueue(
                label = if (existing == null) "New visit" else "Visit update",
                ops = ops,
            )
        }
        onQueued()
    }

suspend fun CrmRepository.deleteVisit(id: String) {
        val existing = visits.findById(id) ?: return
        if (existing.billed) error("This visit is on an invoice and cannot be deleted")
        database.withTransaction {
            visits.deleteById(id)
            enqueue(
                label = "Delete visit",
                ops = listOf(
                    QueuedOpDto(
                        kind = QueuedOpDto.DELETE,
                        table = "visits",
                        match = idMatch(id),
                    ),
                ),
            )
        }
        onQueued()
    }

suspend fun CrmRepository.saveJob(draft: JobDraft) {
        val uid = userId()
        val day = parseDate(draft.date) ?: error("Date is invalid")
        val startsAt = runCatching { atTime(day, draft.time) }
            .getOrElse { error("Time must be HH:mm") }
            .toString()
        val existing = draft.id?.let { jobs.findById(it) }
        val duration = draft.durationMinutes.coerceIn(15, 24 * 60)

        val row = ScheduledJob(
            id = draft.id ?: UUID.randomUUID().toString(),
            userId = uid,
            clientId = draft.clientId,
            seriesId = existing?.seriesId,
            startsAt = startsAt,
            durationMinutes = duration,
            status = existing?.status ?: "scheduled",
            notes = draft.notes.clean(),
            visitId = existing?.visitId,
            createdAt = existing?.createdAt,
            updatedAt = null,
        )

        database.withTransaction {
            jobs.upsert(row)
            val payload = OfflineJson.encodeToJsonElement(
                ScheduledJob.serializer(),
                row.copy(createdAt = null, updatedAt = null),
            ).jsonObject
            enqueue(
                label = if (existing == null) "New job" else "Job update",
                ops = listOf(
                    if (existing == null) {
                        QueuedOpDto(
                            kind = QueuedOpDto.INSERT,
                            table = "scheduled_jobs",
                            payload = payload,
                        )
                    } else {
                        QueuedOpDto(
                            kind = QueuedOpDto.UPDATE,
                            table = "scheduled_jobs",
                            payload = payload,
                            match = idMatch(row.id),
                        )
                    },
                ),
            )
        }
        onQueued()
    }

    /**
     * A repeating run is booked as real jobs up front, exactly like the web app:
     * the series row is only a tag that makes "edit/delete the whole run" possible.
     */
suspend fun CrmRepository.bookSeries(draft: SeriesDraft) {
        val count = draft.occurrences.coerceIn(1, 60)
        if (count == 1) {
            saveJob(
                JobDraft(
                    clientId = draft.clientId,
                    date = draft.date,
                    time = draft.time,
                    durationMinutes = draft.durationMinutes,
                    notes = draft.notes,
                ),
            )
            return
        }

        val uid = userId()
        val day = parseDate(draft.date) ?: error("Date is invalid")
        val duration = draft.durationMinutes.coerceIn(15, 24 * 60)
        val notes = draft.notes.clean()
        val seriesId = UUID.randomUUID().toString()

        val seriesPayload = buildJsonObject {
            put("id", seriesId)
            put("user_id", uid)
            put("client_id", draft.clientId)
            put("frequency", draft.frequency.wire)
            put("start_date", draft.date)
            put("start_time", draft.time)
            put("duration_minutes", duration)
            notes?.let { put("notes", it) }
            put("active", true)
        }

        val rows = (0 until count).map { index ->
            ScheduledJob(
                id = UUID.randomUUID().toString(),
                userId = uid,
                clientId = draft.clientId,
                seriesId = seriesId,
                startsAt = atTime(step(day, draft.frequency, index), draft.time).toString(),
                durationMinutes = duration,
                status = "scheduled",
                notes = notes,
            )
        }

        database.withTransaction {
            jobs.upsertAll(rows)
            val ops = mutableListOf(
                QueuedOpDto(
                    kind = QueuedOpDto.INSERT,
                    table = "job_series",
                    payload = seriesPayload,
                ),
            )
            rows.forEach { row ->
                ops += QueuedOpDto(
                    kind = QueuedOpDto.INSERT,
                    table = "scheduled_jobs",
                    payload = OfflineJson.encodeToJsonElement(
                        ScheduledJob.serializer(),
                        row.copy(createdAt = null, updatedAt = null),
                    ).jsonObject,
                )
            }
            enqueue(label = "$count jobs booked", ops = ops)
        }
        onQueued()
    }

    /**
     * Move every remaining visit in a run at once, optionally changing its
     * rhythm. Completed and past visits are left alone: they are the record of
     * what happened, not a plan.
     */
suspend fun CrmRepository.editSeries(input: SeriesEditInput) {
        // Prefer the server's copy of the whole run: the local cache is a
        // bounded window and a long run may extend past it. Offline, fall back
        // to whatever is already cached.
        runCatching {
            fetchAll<ScheduledJob>("scheduled_jobs") {
                filter { eq("series_id", input.seriesId) }
                order("starts_at", order = Order.ASCENDING)
                }
        }.getOrNull()?.let { remote ->
            database.withTransaction { jobs.upsertAll(remote) }
        }

        val rows = jobs.bySeries(input.seriesId)
        if (rows.isEmpty()) error("This repeating run has no visits")

        val domainJobs = rows.mapNotNull { row ->
            parseTimestamp(row.startsAt)?.let { starts -> SeriesJob(row.id, starts, row.status) }
        }
        val editable = editableVisits(domainJobs, Instant.now())
        if (editable.isEmpty()) error("Nothing left to move in this run")

        val retimed = retimedVisits(
            visits = editable,
            startTime = input.startTime,
            // Ignored when not respacing; the first visit's own day is kept.
            frequency = input.frequency ?: Frequency.WEEKLY,
            respace = input.frequency != null,
        )

        val byId = rows.associateBy { it.id }
        val duration = input.durationMinutes.coerceIn(15, 24 * 60)
        val notes = input.notes.clean()
        val updated = retimed.mapNotNull { visit ->
            byId[visit.id]?.copy(
                startsAt = visit.startsAt.toString(),
                durationMinutes = duration,
                notes = notes,
                updatedAt = null,
            )
        }
        if (updated.isEmpty()) error("Nothing left to move in this run")

        database.withTransaction {
            jobs.upsertAll(updated)
            val ops = updated.map { row ->
                QueuedOpDto(
                    kind = QueuedOpDto.UPDATE,
                    table = "scheduled_jobs",
                    payload = OfflineJson.encodeToJsonElement(
                        ScheduledJob.serializer(),
                        row.copy(createdAt = null, updatedAt = null),
                    ).jsonObject,
                    match = idMatch(row.id),
                )
            }.toMutableList()
            ops += QueuedOpDto(
                kind = QueuedOpDto.UPDATE,
                table = "job_series",
                payload = buildJsonObject {
                    put("start_time", input.startTime)
                    put("duration_minutes", duration)
                    putNullable("notes", notes)
                    input.frequency?.let { put("frequency", it.wire) }
                },
                match = idMatch(input.seriesId),
            )
            enqueue(label = "Series update", ops = ops)
        }
        onQueued()
    }

    /**
     * Drag one visit of a repeating run onto another day and carry the rest of
     * the run with it: this visit and every scheduled one after it shift by
     * the same number of days, each keeping its own time of day. Done and past
     * visits are left alone.
     */
    suspend fun CrmRepository.moveSeriesFrom(jobId: String, newDay: LocalDate) {
        val dragged = jobs.findById(jobId) ?: error("This job no longer exists")
        val seriesId = dragged.seriesId ?: error("This job is not part of a repeating run")
        val draggedStart = parseTimestamp(dragged.startsAt) ?: error("This job has no start time")
        val dayDelta = ChronoUnit.DAYS.between(localDateOf(draggedStart), newDay)
        if (dayDelta == 0L) return

        // Prefer the server's copy of the whole run: the local cache is a
        // bounded window and a long run may extend past it. Offline, fall back
        // to whatever is already cached.
        runCatching {
            fetchAll<ScheduledJob>("scheduled_jobs") {
                filter { eq("series_id", seriesId) }
                order("starts_at", order = Order.ASCENDING)
            }
        }.getOrNull()?.let { remote -> database.withTransaction { jobs.upsertAll(remote) } }

        val rows = jobs.bySeries(seriesId)
        val domainJobs = rows.mapNotNull { row ->
            parseTimestamp(row.startsAt)?.let { starts -> SeriesJob(row.id, starts, row.status) }
        }
        val moves = seriesShift(domainJobs, jobId, dayDelta)
        if (moves.isEmpty()) error("Nothing left to move in this run")

        val byId = rows.associateBy { it.id }
        val updated = moves.mapNotNull { visit ->
            byId[visit.id]?.copy(startsAt = visit.startsAt.toString(), updatedAt = null)
        }
        if (updated.isEmpty()) error("Nothing left to move in this run")

        database.withTransaction {
            jobs.upsertAll(updated)
            enqueue(
                label = "Repeating run moved",
                ops = updated.map { row ->
                    QueuedOpDto(
                        kind = QueuedOpDto.UPDATE,
                        table = "scheduled_jobs",
                        payload = OfflineJson.encodeToJsonElement(
                            ScheduledJob.serializer(),
                            row.copy(createdAt = null, updatedAt = null),
                        ).jsonObject,
                        match = idMatch(row.id),
                    )
                },
            )
        }
        onQueued()
    }

suspend fun CrmRepository.deleteJob(id: String, wholeSeriesId: String? = null) {
        database.withTransaction {
            if (wholeSeriesId != null) {
                jobs.deleteScheduledBySeries(wholeSeriesId)
                enqueue(
                    label = "Delete repeating run",
                    ops = listOf(
                        QueuedOpDto(
                            kind = QueuedOpDto.DELETE,
                            table = "scheduled_jobs",
                            match = buildJsonObject {
                                put("series_id", wholeSeriesId)
                                put("status", "scheduled")
                            },
                        ),
                        QueuedOpDto(
                            kind = QueuedOpDto.UPDATE,
                            table = "job_series",
                            payload = buildJsonObject { put("active", false) },
                            match = idMatch(wholeSeriesId),
                        ),
                    ),
                )
            } else {
                jobs.deleteById(id)
                enqueue(
                    label = "Delete job",
                    ops = listOf(
                        QueuedOpDto(
                            kind = QueuedOpDto.DELETE,
                            table = "scheduled_jobs",
                            match = idMatch(id),
                        ),
                    ),
                )
            }
        }
        onQueued()
    }

    /** Mark a job done against a visit that was already logged for that day. */
    /** Staff completion is priced and recorded by the server. */
suspend fun CrmRepository.staffCompleteJob(jobId: String, hours: Double, description: String?) {
        database.withTransaction {
            jobs.findById(jobId)?.let { job ->
                jobs.upsert(job.copy(status = "done", updatedAt = null))
            }
            enqueue(
                label = "Job completed",
                ops = listOf(
                    QueuedOpDto(
                        kind = QueuedOpDto.RPC,
                        fn = "staff_complete_job",
                        args = buildJsonObject {
                            put("_job_id", jobId)
                            put("_hours", hours)
                            description.clean()?.let { put("_description", it) }
                        },
                    ),
                ),
            )
        }
        onQueued()
    }

suspend fun CrmRepository.completeJobWithVisit(jobId: String, visitId: String, status: String = "done") {
        val job = jobs.findById(jobId) ?: return
        val updated = job.copy(status = status, visitId = visitId, updatedAt = null)
        database.withTransaction {
            jobs.upsert(updated)
            enqueue(
                label = "Job completed",
                ops = listOf(
                    QueuedOpDto(
                        kind = QueuedOpDto.UPDATE,
                        table = "scheduled_jobs",
                        payload = OfflineJson.encodeToJsonElement(
                            ScheduledJob.serializer(),
                            updated.copy(createdAt = null, updatedAt = null),
                        ).jsonObject,
                        match = idMatch(jobId),
                    ),
                ),
            )
        }
        onQueued()
    }
