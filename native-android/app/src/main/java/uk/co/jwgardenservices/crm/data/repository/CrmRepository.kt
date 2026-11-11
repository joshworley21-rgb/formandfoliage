package uk.co.jwgardenservices.crm.data.repository

import androidx.room.withTransaction
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.storage.storage
import io.ktor.http.ContentType
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import java.io.IOException
import kotlin.time.Duration.Companion.seconds
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
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
import uk.co.jwgardenservices.crm.domain.parseDate
import uk.co.jwgardenservices.crm.domain.parseTimestamp
import uk.co.jwgardenservices.crm.domain.quoteTotals
import uk.co.jwgardenservices.crm.domain.retimedVisits
import uk.co.jwgardenservices.crm.domain.round2
import uk.co.jwgardenservices.crm.domain.step
import uk.co.jwgardenservices.crm.data.offline.OfflineJson
import uk.co.jwgardenservices.crm.data.remote.SupabaseProvider

/**
 * Offline-first CRM data.
 *
 * Reads come from Room, so the screens paint from the last synced snapshot and
 * keep working without signal. Writes update Room immediately and enqueue a
 * batch of operations in the outbox; [SyncEngine] replays them when the network
 * returns. This is the native counterpart of the web app's React Query cache
 * plus `offline-queue.ts`.
 */
class CrmRepository(
    private val database: CrmDatabase,
    private val client: SupabaseClient = SupabaseProvider.client,
    private val onQueued: () -> Unit = {},
) {
    private val clients = database.clientDao()
    private val enquiries = database.enquiryDao()
    private val team = database.teamMemberDao()
    private val jobTasks = database.jobTaskDao()
    private val visits = database.visitDao()
    private val jobs = database.scheduledJobDao()
    private val invoices = database.invoiceDao()
    private val invoiceLines = database.invoiceItemDao()
    private val emailEvents = database.invoiceEmailEventDao()
    private val quotes = database.quoteDao()
    private val quoteLines = database.quoteItemDao()
    private val settings = database.settingsDao()
    private val outbox = database.outboxDao()

    fun observeClients(): Flow<List<Client>> = clients.observeAll()
    fun observeVisits(): Flow<List<Visit>> = visits.observeAll()
    fun observeInvoices(): Flow<List<Invoice>> = invoices.observeAll()
    fun observeJobs(): Flow<List<ScheduledJob>> = jobs.observeAll()
    fun observeSettings(): Flow<Settings?> = settings.observe()
    fun observePendingWrites(): Flow<Int> = outbox.observeCount()
    fun observeQuotes(): Flow<List<Quote>> = quotes.observeAll()
    fun observeEnquiries(): Flow<List<Enquiry>> = enquiries.observeAll()
    fun observeInvoiceEmailEvents(): Flow<List<InvoiceEmailEvent>> = emailEvents.observeAll()
    fun observeTeam(): Flow<List<TeamMember>> = team.observeAll()
    fun observeJobTasks(jobId: String): Flow<List<JobTask>> = jobTasks.observeByJob(jobId)

    private suspend fun userId(): String =
        client.auth.currentUserOrNull()?.id ?: error("Not signed in")

    /**
     * Pull the server's current state into Room.
     *
     * Never runs while the outbox has work: a snapshot taken before a queued
     * edit has reached Supabase would overwrite that edit locally and it would
     * silently disappear from the screen.
     */
    suspend fun refreshAll(role: String = "owner") {
        // Signed out: nothing to pull, and no workspace to pull it for.
        if (client.auth.currentUserOrNull() == null) return
        if (outbox.pendingCount() > 0) return

        val uid = userId()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val from = today.minusDays(30).atStartOfDay(zone).toInstant()
        // A year ahead: enough to cover a repeating run being retimed, while
        // still bounded.
        val to = today.plusDays(365).atStartOfDay(zone).toInstant()

        if (role == "staff") {
            val staffSnapshot = coroutineScope {
                val staffClients = async {
                    client.postgrest.rpc("staff_clients").decodeList<StaffClientRow>()
                }
                val jobRows = async {
                    client.from("scheduled_jobs").select {
                        filter {
                            gte("starts_at", from.toString())
                            lte("starts_at", to.toString())
                        }
                        order("starts_at", order = Order.ASCENDING)
                    }.decodeList<ScheduledJob>()
                }
                staffClients.await() to jobRows.await()
            }
            val rows = staffSnapshot.first.map { row ->
                Client(
                    id = row.id,
                    userId = uid,
                    name = row.name,
                    address = row.address,
                    notes = row.notes,
                    billingMode = "hourly",
                    hourlyRate = 0.0,
                    fixedPrice = 0.0,
                    active = true,
                )
            }
            database.withTransaction {
                // Staff must not keep any owner-only data cached on the device.
                visits.clear()
                invoices.clear()
                invoiceLines.clear()
                settings.clear()
                quotes.clear()
                quoteLines.clear()
                enquiries.clear()
                emailEvents.clear()
                team.clear()

                clients.clear()
                clients.upsertAll(rows)
                jobs.clear()
                jobs.upsertAll(staffSnapshot.second)
            }
            return
        }

        val snapshot = coroutineScope {
            val clients = async {
                client.from("clients").select {
                    order("name", order = Order.ASCENDING)
                }.decodeList<Client>()
            }
            val visits = async {
                client.from("visits").select {
                    order("visit_date", order = Order.DESCENDING)
                }.decodeList<Visit>()
            }
            val invoices = async {
                client.from("invoices").select {
                    order("issue_date", order = Order.DESCENDING)
                    order("created_at", order = Order.DESCENDING)
                }.decodeList<Invoice>()
            }
            val invoiceLines = async {
                client.from("invoice_items").select {
                    order("item_date", order = Order.ASCENDING)
                }.decodeList<InvoiceItem>()
            }
            val quoteRows = async {
                client.from("quotes").select {
                    order("created_at", order = Order.DESCENDING)
                }.decodeList<Quote>()
            }
            val quoteItemRows = async {
                client.from("quote_items").select {
                    order("sort_order", order = Order.ASCENDING)
                }.decodeList<QuoteItem>()
            }
            val enquiryRows = async {
                client.from("enquiries").select {
                    order("created_at", order = Order.DESCENDING)
                }.decodeList<Enquiry>()
            }
            val emailEventRows = async {
                client.from("invoice_email_events").select {
                    order("created_at", order = Order.DESCENDING)
                }.decodeList<InvoiceEmailEvent>()
            }
            val teamRows = async {
                client.from("team_members").select {
                    order("created_at", order = Order.ASCENDING)
                }.decodeList<TeamMember>()
            }
            val jobs = async {
                client.from("scheduled_jobs").select {
                    filter {
                        gte("starts_at", from.toString())
                        lte("starts_at", to.toString())
                    }
                    order("starts_at", order = Order.ASCENDING)
                }.decodeList<ScheduledJob>()
            }
            val settingsRow = async {
                client.from("settings").select {
                    filter { eq("user_id", uid) }
                }.decodeList<Settings>().firstOrNull()
            }
            Snapshot(
                clients = clients.await(),
                visits = visits.await(),
                invoices = invoices.await(),
                invoiceItems = invoiceLines.await(),
                quotes = quoteRows.await(),
                quoteItems = quoteItemRows.await(),
                enquiries = enquiryRows.await(),
                invoiceEmailEvents = emailEventRows.await(),
                team = teamRows.await(),
                jobs = jobs.await(),
                settings = settingsRow.await(),
            )
        }

        database.withTransaction {
            // A write may have been queued while the server calls were in
            // flight. If so, leave the cache alone: applying the older snapshot
            // now would make that edit vanish from the screen until the next
            // sync round-trip.
            if (outbox.pendingCount() > 0) return@withTransaction

            clients.clear()
            clients.upsertAll(snapshot.clients)
            visits.clear()
            visits.upsertAll(snapshot.visits)
            invoices.clear()
            invoices.upsertAll(snapshot.invoices)
            invoiceLines.clear()
            invoiceLines.upsertAll(snapshot.invoiceItems)
            quotes.clear()
            quotes.upsertAll(snapshot.quotes)
            quoteLines.clear()
            quoteLines.upsertAll(snapshot.quoteItems)
            enquiries.clear()
            enquiries.upsertAll(snapshot.enquiries)
            emailEvents.clear()
            emailEvents.upsertAll(snapshot.invoiceEmailEvents)
            team.clear()
            team.upsertAll(snapshot.team)
            jobs.clear()
            jobs.upsertAll(snapshot.jobs)
            settings.clear()
            snapshot.settings?.let { settings.upsert(it) }
        }
    }

    /** Signing out must not leave the previous account's records on the device. */
    suspend fun clearLocalCache() {
        database.withTransaction {
            clients.clear()
            visits.clear()
            jobs.clear()
            invoices.clear()
            settings.clear()
            jobTasks.clear()
            outbox.clear()
        }
    }

    suspend fun createClient(draft: ClientDraft) {
        val row = draft.toClient(id = UUID.randomUUID().toString(), userId = userId())
        database.withTransaction {
            clients.upsert(row)
            enqueue(
                label = "New client \"${row.name}\"",
                ops = listOf(
                    QueuedOpDto(
                        kind = QueuedOpDto.INSERT,
                        table = "clients",
                        payload = OfflineJson.encodeToJsonElement(Client.serializer(), row).jsonObject,
                    ),
                ),
            )
        }
        onQueued()
    }

    suspend fun updateClient(existing: Client, draft: ClientDraft) {
        val row = existing.copy(
            userId = userId(),
            name = draft.name.trim(),
            address = draft.address.clean(),
            phone = draft.phone.clean(),
            email = draft.email.clean(),
            notes = draft.notes.clean(),
            billingMode = draft.billingMode,
            hourlyRate = draft.hourlyRate,
            fixedPrice = draft.fixedPrice,
            paymentReminders = draft.paymentReminders,
            updatedAt = null,
        )
        database.withTransaction {
            clients.upsert(row)
            val payload = OfflineJson.encodeToJsonElement(
                Client.serializer(),
                row.copy(createdAt = null, updatedAt = null),
            ).jsonObject
            enqueue(
                label = "Client \"${row.name}\" update",
                ops = listOf(
                    QueuedOpDto(
                        kind = QueuedOpDto.UPDATE,
                        table = "clients",
                        payload = payload,
                        match = idMatch(row.id),
                    ),
                ),
            )
        }
        onQueued()
    }

    suspend fun deleteClient(id: String) {
        database.withTransaction {
            clients.deleteById(id)
            // The server cascades these; do the same locally so the UI does not
            // keep showing rows for a client that has gone.
            visits.deleteByClient(id)
            jobs.deleteByClient(id)
            invoices.deleteByClient(id)
            enqueue(
                label = "Delete client",
                ops = listOf(
                    QueuedOpDto(
                        kind = QueuedOpDto.DELETE,
                        table = "clients",
                        match = idMatch(id),
                    ),
                ),
            )
        }
        onQueued()
    }

    suspend fun invoiceItems(invoiceId: String): List<InvoiceItem> =
        invoiceLines.forInvoice(invoiceId)

    suspend fun client(id: String): Client? = clients.findById(id)

    suspend fun getSettings(): Settings? = settings.get()

    /**
     * Invoices need the Edge Function for server-side numbering and line
     * generation, so this is the one write that genuinely needs signal. The
     * error is surfaced rather than queued: a draft with a guessed number
     * would have to be reconciled later.
     */
    suspend fun createInvoice(input: NewInvoiceInput): CreateInvoiceResult {
        val body = buildJsonObject {
            put("clientId", input.clientId)
            put("visitIds", buildJsonArray { input.visitIds.forEach { add(it) } })
            input.retainerDescription?.let { put("retainerDescription", it) }
            put("retainerAmount", input.retainerAmount)
            input.notes?.let { put("notes", it) }
            input.dueDate?.let { put("dueDate", it) }
            put("dueInDays", input.dueInDays)
        }
        val result = OfflineJson.decodeFromString<CreateInvoiceResult>(
            invokeFunction("create-invoice", body),
        )
        // The invoice exists on the server now even if this refresh cannot run.
        runCatching { refreshAll() }
        return result
    }

    suspend fun sendInvoice(
        invoiceId: String,
        pdfBase64: String,
        subject: String? = null,
        message: String? = null,
    ): SendInvoiceResult {
        val body = buildJsonObject {
            put("invoiceId", invoiceId)
            put("pdfBase64", pdfBase64)
            subject?.let { put("subject", it) }
            message?.let { put("message", it) }
        }
        val result = OfflineJson.decodeFromString<SendInvoiceResult>(
            invokeFunction("send-invoice-email", body),
        )
        runCatching { refreshAll() }
        return result
    }

    suspend fun updateInvoiceDraft(id: String, dueDate: String?, notes: String?) {
        val invoice = invoices.findById(id) ?: return
        val updated = invoice.copy(
            dueDate = dueDate?.clean(),
            notes = notes.clean(),
            updatedAt = null,
        )
        database.withTransaction {
            invoices.upsert(updated)
            enqueue(
                label = "Invoice ${invoice.number} updated",
                ops = listOf(
                    QueuedOpDto(
                        kind = QueuedOpDto.UPDATE,
                        table = "invoices",
                        payload = buildJsonObject {
                            putNullable("due_date", updated.dueDate)
                            putNullable("notes", updated.notes)
                        },
                        match = idMatch(id),
                    ),
                ),
            )
        }
        onQueued()
    }

    suspend fun rebuildInvoice(invoiceId: String): RebuildInvoiceResult {
        val result = OfflineJson.decodeFromString<RebuildInvoiceResult>(
            invokeFunction(
                "rebuild-invoice",
                buildJsonObject { put("invoiceId", invoiceId) },
            ),
        )
        runCatching { refreshAll() }
        return result
    }

    suspend fun markInvoicePaid(id: String) {
        val invoice = invoices.findById(id) ?: return
        val updated = invoice.copy(
            status = "paid",
            paidAt = Instant.now().toString(),
            updatedAt = null,
        )
        database.withTransaction {
            invoices.upsert(updated)
            enqueue(
                label = "Invoice ${invoice.number} marked paid",
                ops = listOf(
                    QueuedOpDto(
                        kind = QueuedOpDto.UPDATE,
                        table = "invoices",
                        payload = OfflineJson.encodeToJsonElement(
                            Invoice.serializer(),
                            updated.copy(createdAt = null, updatedAt = null),
                        ).jsonObject,
                        match = idMatch(id),
                    ),
                ),
            )
        }
        onQueued()
    }

    suspend fun deleteInvoice(id: String) {
        database.withTransaction {
            invoices.deleteById(id)
            invoiceLines.deleteByInvoice(id)
            emailEvents.deleteByInvoice(id)
            visits.unbillByInvoice(id)
            enqueue(
                label = "Delete invoice",
                ops = listOf(
                    QueuedOpDto(
                        kind = QueuedOpDto.UPDATE,
                        table = "visits",
                        payload = buildJsonObject {
                            put("billed", false)
                            put("invoice_id", JsonNull)
                        },
                        match = buildJsonObject { put("invoice_id", id) },
                    ),
                    QueuedOpDto(
                        kind = QueuedOpDto.UPDATE,
                        table = "bank_transactions",
                        payload = buildJsonObject {
                            put("invoice_id", JsonNull)
                            put("matched_at", JsonNull)
                        },
                        match = buildJsonObject { put("invoice_id", id) },
                    ),
                    QueuedOpDto(
                        kind = QueuedOpDto.DELETE,
                        table = "invoice_items",
                        match = buildJsonObject { put("invoice_id", id) },
                    ),
                    QueuedOpDto(
                        kind = QueuedOpDto.DELETE,
                        table = "invoice_email_events",
                        match = buildJsonObject { put("invoice_id", id) },
                    ),
                    QueuedOpDto(
                        kind = QueuedOpDto.DELETE,
                        table = "invoices",
                        match = idMatch(id),
                    ),
                ),
            )
        }
        onQueued()
    }

    private suspend fun invokeFunction(name: String, body: JsonObject): String {
        val response = try {
            client.functions.invoke(name, body)
        } catch (failure: IOException) {
            throw IllegalStateException(
                "This needs a connection. Try again when you have signal.",
                failure,
            )
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            val message = runCatching {
                OfflineJson.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.content
            }.getOrNull()
            throw IllegalStateException(
                message ?: "The server rejected the request (${response.status.value})",
            )
        }
        return text
    }

    suspend fun quoteItems(quoteId: String): List<QuoteItem> = quoteLines.forQuote(quoteId)

    suspend fun createQuote(draft: QuoteDraft): String {
        val uid = userId()
        val settingsRow = settings.get()
        val vatRegistered = settingsRow?.vatRegistered
        val totals = quoteTotals(
            draft.items.map { QuoteLine(it.quantity, it.unitPrice) },
            vatRegistered,
            settingsRow?.vatRate,
        )
        val today = LocalDate.now().toString()
        val validUntil = draft.validUntil?.takeIf { it.isNotBlank() }
            ?: LocalDate.now().plusDays((settingsRow?.quoteValidityDays ?: 30).toLong()).toString()
        val terms = draft.terms?.clean() ?: settingsRow?.quoteTerms
        val number = client.postgrest.rpc(
            "next_quote_number",
            buildJsonObject { put("_user_id", uid) },
        ).decodeAs<String>()

        val quoteId = UUID.randomUUID().toString()
        val quotePayload = buildJsonObject {
            put("id", quoteId)
            put("user_id", uid)
            putNullable("client_id", draft.clientId)
            putNullable("enquiry_id", draft.enquiryId)
            put("number", number)
            put("contact_name", draft.contactName.trim())
            putNullable("contact_email", draft.contactEmail.clean())
            putNullable("contact_phone", draft.contactPhone.clean())
            putNullable("contact_address", draft.contactAddress.clean())
            put("issue_date", today)
            put("valid_until", validUntil)
            put("subtotal", totals.subtotal)
            put("vat_amount", totals.vatAmount)
            put("total", totals.total)
            put("status", "draft")
            putNullable("notes", draft.notes.clean())
            putNullable("terms", terms)
        }

        try {
            client.from("quotes").insert(quotePayload)
            val lines = draft.items
                .filter { it.description.isNotBlank() || lineAmount(QuoteLine(it.quantity, it.unitPrice)) != 0.0 }
                .mapIndexed { index, item ->
                    buildJsonObject {
                        put("id", UUID.randomUUID().toString())
                        put("user_id", uid)
                        put("quote_id", quoteId)
                        put("description", item.description.trim().ifBlank { "Garden work" })
                        put("quantity", item.quantity)
                        put("unit_price", item.unitPrice)
                        put("amount", lineAmount(QuoteLine(item.quantity, item.unitPrice)))
                        put("sort_order", index)
                    }
                }
            if (lines.isNotEmpty()) client.from("quote_items").insert(lines)
        } catch (failure: IOException) {
            throw IllegalStateException(
                "Creating a quote needs a connection. Try again when you have signal.",
                failure,
            )
        }

        runCatching { refreshAll() }
        return quoteId
    }

    suspend fun updateQuote(id: String, draft: QuoteDraft) {
        val uid = userId()
        val settingsRow = settings.get()
        val totals = quoteTotals(
            draft.items.map { QuoteLine(it.quantity, it.unitPrice) },
            settingsRow?.vatRegistered,
            settingsRow?.vatRate,
        )
        val validUntil = draft.validUntil?.takeIf { it.isNotBlank() }
            ?: LocalDate.now().plusDays((settingsRow?.quoteValidityDays ?: 30).toLong()).toString()
        val terms = draft.terms?.clean() ?: settingsRow?.quoteTerms

        val quotePayload = buildJsonObject {
            putNullable("client_id", draft.clientId)
            put("contact_name", draft.contactName.trim())
            putNullable("contact_email", draft.contactEmail.clean())
            putNullable("contact_phone", draft.contactPhone.clean())
            putNullable("contact_address", draft.contactAddress.clean())
            put("valid_until", validUntil)
            put("subtotal", totals.subtotal)
            put("vat_amount", totals.vatAmount)
            put("total", totals.total)
            putNullable("notes", draft.notes.clean())
            putNullable("terms", terms)
        }

        try {
            client.from("quotes").update(quotePayload) {
                filter { eq("id", id) }
            }
            client.from("quote_items").delete {
                filter { eq("quote_id", id) }
            }
            val lines = draft.items
                .filter { it.description.isNotBlank() || lineAmount(QuoteLine(it.quantity, it.unitPrice)) != 0.0 }
                .mapIndexed { index, item ->
                    buildJsonObject {
                        put("id", UUID.randomUUID().toString())
                        put("user_id", uid)
                        put("quote_id", id)
                        put("description", item.description.trim().ifBlank { "Garden work" })
                        put("quantity", item.quantity)
                        put("unit_price", item.unitPrice)
                        put("amount", lineAmount(QuoteLine(item.quantity, item.unitPrice)))
                        put("sort_order", index)
                    }
                }
            if (lines.isNotEmpty()) client.from("quote_items").insert(lines)
        } catch (failure: IOException) {
            throw IllegalStateException(
                "Saving a quote needs a connection. Try again when you have signal.",
                failure,
            )
        }

        runCatching { refreshAll() }
    }

    suspend fun sendQuote(
        quoteId: String,
        origin: String,
        message: String? = null,
    ): SendQuoteResult {
        val body = buildJsonObject {
            put("quoteId", quoteId)
            put("origin", origin)
            message?.clean()?.let { put("message", it) }
        }
        val result = OfflineJson.decodeFromString<SendQuoteResult>(
            invokeFunction("send-quote-email", body),
        )
        runCatching { refreshAll() }
        return result
    }

    suspend fun setQuoteStatus(id: String, status: String) {
        val quote = quotes.findById(id) ?: return
        val decided = status == "accepted" || status == "declined"
        val decidedAt = if (decided) Instant.now().toString() else null
        val updated = quote.copy(status = status, decidedAt = decidedAt, updatedAt = null)
        database.withTransaction {
            quotes.upsert(updated)
            enqueue(
                label = "Quote ${quote.number} $status",
                ops = listOf(
                    QueuedOpDto(
                        kind = QueuedOpDto.UPDATE,
                        table = "quotes",
                        payload = buildJsonObject {
                            put("status", status)
                            putNullable("decided_at", decidedAt)
                        },
                        match = idMatch(id),
                    ),
                ),
            )
        }
        onQueued()
    }

    suspend fun deleteQuote(id: String) {
        database.withTransaction {
            quotes.deleteById(id)
            quoteLines.deleteByQuote(id)
            enqueue(
                label = "Delete quote",
                ops = listOf(
                    QueuedOpDto(
                        kind = QueuedOpDto.DELETE,
                        table = "quote_items",
                        match = buildJsonObject { put("quote_id", id) },
                    ),
                    QueuedOpDto(
                        kind = QueuedOpDto.DELETE,
                        table = "quotes",
                        match = idMatch(id),
                    ),
                ),
            )
        }
        onQueued()
    }

    private fun JsonObjectBuilder.putNullable(key: String, value: String?) {
        if (value == null) put(key, JsonNull) else put(key, value)
    }

    suspend fun updateEnquiryStatus(id: String, status: String) {
        val existing = enquiries.findById(id) ?: return
        database.withTransaction {
            enquiries.upsert(existing.copy(status = status, updatedAt = null))
            enqueue(
                label = "Enquiry marked $status",
                ops = listOf(
                    QueuedOpDto(
                        kind = QueuedOpDto.UPDATE,
                        table = "enquiries",
                        payload = buildJsonObject { put("status", status) },
                        match = idMatch(id),
                    ),
                ),
            )
        }
        onQueued()
    }

    suspend fun deleteEnquiry(id: String) {
        database.withTransaction {
            enquiries.deleteById(id)
            enqueue(
                label = "Delete enquiry",
                ops = listOf(
                    QueuedOpDto(
                        kind = QueuedOpDto.DELETE,
                        table = "enquiries",
                        match = idMatch(id),
                    ),
                ),
            )
        }
        onQueued()
    }

    /** Create the client and mark the enquiry converted in one queued batch. */
    suspend fun convertEnquiryToClient(enquiry: Enquiry): String {
        val uid = userId()
        val clientId = UUID.randomUUID().toString()
        val client = Client(
            id = clientId,
            userId = uid,
            name = enquiry.name,
            address = enquiry.address.clean(),
            phone = enquiry.phone.clean(),
            email = enquiry.email.clean(),
            notes = enquiry.message.clean(),
        )
        val updatedEnquiry = enquiry.copy(
            status = "converted",
            clientId = clientId,
            updatedAt = null,
        )
        database.withTransaction {
            clients.upsert(client)
            enquiries.upsert(updatedEnquiry)
            enqueue(
                label = "Convert enquiry",
                ops = listOf(
                    QueuedOpDto(
                        kind = QueuedOpDto.INSERT,
                        table = "clients",
                        payload = OfflineJson.encodeToJsonElement(Client.serializer(), client).jsonObject,
                    ),
                    QueuedOpDto(
                        kind = QueuedOpDto.UPDATE,
                        table = "enquiries",
                        payload = buildJsonObject {
                            put("status", "converted")
                            put("client_id", clientId)
                        },
                        match = idMatch(enquiry.id),
                    ),
                ),
            )
        }
        onQueued()
        return clientId
    }

    suspend fun saveSettings(draft: SettingsDraft) {
        val uid = userId()
        val existing = settings.get()
        val row = (existing ?: Settings(userId = uid)).copy(
            businessName = draft.businessName.trim().ifBlank { "JW Garden Services" },
            address = draft.address.clean(),
            phone = draft.phone.clean(),
            email = draft.email.clean(),
            replyToEmail = draft.replyToEmail.clean(),
            publicSiteUrl = draft.publicSiteUrl.clean()?.trimEnd('/'),
            vatRegistered = draft.vatRegistered,
            vatNumber = draft.vatNumber.clean(),
            vatRate = draft.vatRate,
            invoicePrefix = draft.invoicePrefix.trim().ifBlank { "JW-" },
            paymentTerms = draft.paymentTerms.clean(),
            bankDetails = draft.bankDetails.clean(),
            quoteValidityDays = draft.quoteValidityDays.coerceIn(1, 365),
            quoteTerms = draft.quoteTerms.clean(),
            emailSubjectTemplate = draft.emailSubjectTemplate.trim().ifBlank {
                "Invoice {{invoice_number}} from {{business_name}}"
            },
            emailBodyTemplate = draft.emailBodyTemplate.trim().ifBlank {
                "Hi {{client_name}},\n\nPlease find attached invoice {{invoice_number}} for recent garden work.\n\nTotal due: {{total}}{{due_clause}}.\n\nMany thanks,\n{{business_name}}"
            },
        )
        database.withTransaction {
            settings.upsert(row)
            enqueue(
                label = "Settings update",
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

    suspend fun inviteMember(email: String, name: String?) {
        val uid = userId()
        val member = TeamMember(
            id = UUID.randomUUID().toString(),
            ownerId = uid,
            email = email.trim().lowercase(),
            name = name.clean(),
            role = "staff",
        )
        database.withTransaction {
            team.upsert(member)
            enqueue(
                label = "Invite ${member.email}",
                ops = listOf(
                    QueuedOpDto(
                        kind = QueuedOpDto.INSERT,
                        table = "team_members",
                        payload = OfflineJson.encodeToJsonElement(
                            TeamMember.serializer(),
                            member,
                        ).jsonObject,
                    ),
                ),
            )
        }
        onQueued()
    }

    suspend fun removeMember(id: String) {
        database.withTransaction {
            team.deleteById(id)
            enqueue(
                label = "Remove team member",
                ops = listOf(
                    QueuedOpDto(
                        kind = QueuedOpDto.DELETE,
                        table = "team_members",
                        match = idMatch(id),
                    ),
                ),
            )
        }
        onQueued()
    }

    private suspend fun workspaceOwnerId(): String =
        client.postgrest.rpc("my_workspace").decodeList<WorkspaceRow>().firstOrNull()?.ownerId
            ?: userId()

    suspend fun refreshJobTasks(jobId: String) {
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

    suspend fun addJobTask(jobId: String, title: String, position: Int) {
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

    suspend fun setJobTaskDone(task: JobTask, done: Boolean) {
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

    suspend fun deleteJobTask(id: String) {
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

    suspend fun jobPhotos(jobId: String): List<JobPhotoWithUrl> {
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

    suspend fun uploadJobPhoto(
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

    suspend fun updateJobPhotoCaption(id: String, caption: String?) {
        val cleaned = caption.clean()
        client.from("job_photos").update(
            buildJsonObject {
                if (cleaned == null) put("caption", JsonNull) else put("caption", cleaned)
            },
        ) {
            filter { eq("id", id) }
        }
    }

    suspend fun deleteJobPhoto(id: String, path: String) {
        client.storage.from("job-photos").delete(listOf(path))
        client.from("job_photos").delete {
            filter { eq("id", id) }
        }
    }

    suspend fun saveVisit(draft: VisitDraft) {
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

    suspend fun deleteVisit(id: String) {
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

    suspend fun saveJob(draft: JobDraft) {
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
    suspend fun bookSeries(draft: SeriesDraft) {
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
    suspend fun editSeries(input: SeriesEditInput) {
        // Prefer the server's copy of the whole run: the local cache is a
        // bounded window and a long run may extend past it. Offline, fall back
        // to whatever is already cached.
        runCatching {
            client.from("scheduled_jobs").select {
                filter { eq("series_id", input.seriesId) }
                order("starts_at", order = Order.ASCENDING)
            }.decodeList<ScheduledJob>()
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

    suspend fun deleteJob(id: String, wholeSeriesId: String? = null) {
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
    suspend fun staffCompleteJob(jobId: String, hours: Double, description: String?) {
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

    suspend fun completeJobWithVisit(jobId: String, visitId: String, status: String = "done") {
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

    private suspend fun enqueue(label: String, ops: List<QueuedOpDto>) {
        outbox.insert(
            OutboxEntry(
                id = UUID.randomUUID().toString(),
                label = label,
                createdAt = System.currentTimeMillis(),
                opsJson = OfflineJson.encodeToString(ops),
            ),
        )
    }

    private fun ClientDraft.toClient(id: String, userId: String) = Client(
        id = id,
        userId = userId,
        name = name.trim(),
        address = address.clean(),
        phone = phone.clean(),
        email = email.clean(),
        notes = notes.clean(),
        billingMode = billingMode,
        hourlyRate = hourlyRate,
        fixedPrice = fixedPrice,
        paymentReminders = paymentReminders,
    )

    private fun idMatch(id: String) = buildJsonObject { put("id", id) }

    private data class Snapshot(
        val clients: List<Client>,
        val visits: List<Visit>,
        val invoices: List<Invoice>,
        val invoiceItems: List<InvoiceItem>,
        val quotes: List<Quote>,
        val quoteItems: List<QuoteItem>,
        val enquiries: List<Enquiry>,
        val invoiceEmailEvents: List<InvoiceEmailEvent>,
        val team: List<TeamMember>,
        val jobs: List<ScheduledJob>,
        val settings: Settings?,
    )
}

private fun String?.clean(): String? = this?.trim()?.ifBlank { null }
