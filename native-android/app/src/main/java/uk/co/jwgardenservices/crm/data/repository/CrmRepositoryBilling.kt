package uk.co.jwgardenservices.crm.data.repository


// Invoices, quotes, enquiries, settings and team management, split out of
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
import uk.co.jwgardenservices.crm.data.model.QuoteItemDraft
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

suspend fun CrmRepository.invoiceItems(invoiceId: String): List<InvoiceItem> =
        invoiceLines.forInvoice(invoiceId)


suspend fun CrmRepository.createInvoice(input: NewInvoiceInput): CreateInvoiceResult {
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

suspend fun CrmRepository.sendInvoice(
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

suspend fun CrmRepository.updateInvoiceDraft(id: String, dueDate: String?, notes: String?) {
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

suspend fun CrmRepository.rebuildInvoice(invoiceId: String): RebuildInvoiceResult {
        val result = OfflineJson.decodeFromString<RebuildInvoiceResult>(
            invokeFunction(
                "rebuild-invoice",
                buildJsonObject { put("invoiceId", invoiceId) },
            ),
        )
        runCatching { refreshAll() }
        return result
    }

suspend fun CrmRepository.markInvoicePaid(id: String) {
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

suspend fun CrmRepository.deleteInvoice(id: String) {
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

internal suspend fun CrmRepository.invokeFunction(name: String, body: JsonObject): String {
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

suspend fun CrmRepository.quoteItems(quoteId: String): List<QuoteItem> = quoteLines.forQuote(quoteId)

suspend fun CrmRepository.createQuote(draft: QuoteDraft): String {
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

    /**
     * Copy a quote into a fresh draft with its own number, keeping the contact
     * details, terms and every line. Useful for repeat work: duplicate, tweak
     * the price, send.
     */
    suspend fun CrmRepository.duplicateQuote(quoteId: String): String {
        val quote = quotes.findById(quoteId) ?: error("This quote no longer exists")
        val items = quoteLines.forQuote(quoteId)
        if (items.isEmpty()) error("This quote has no lines to copy")
        return createQuote(
            QuoteDraft(
                clientId = quote.clientId,
                contactName = quote.contactName,
                contactEmail = quote.contactEmail,
                contactPhone = quote.contactPhone,
                contactAddress = quote.contactAddress,
                notes = quote.notes,
                terms = quote.terms,
                items = items.map { QuoteItemDraft(it.description, it.quantity, it.unitPrice) },
            ),
        )
    }

suspend fun CrmRepository.updateQuote(id: String, draft: QuoteDraft) {
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

suspend fun CrmRepository.sendQuote(
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

suspend fun CrmRepository.setQuoteStatus(id: String, status: String) {
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

suspend fun CrmRepository.deleteQuote(id: String) {
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

internal fun JsonObjectBuilder.putNullable(key: String, value: String?) {
        if (value == null) put(key, JsonNull) else put(key, value)
    }

suspend fun CrmRepository.updateEnquiryStatus(id: String, status: String) {
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

suspend fun CrmRepository.deleteEnquiry(id: String) {
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
suspend fun CrmRepository.convertEnquiryToClient(enquiry: Enquiry): String {
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

suspend fun CrmRepository.saveSettings(draft: SettingsDraft) {
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

suspend fun CrmRepository.inviteMember(email: String, name: String?) {
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

suspend fun CrmRepository.removeMember(id: String) {
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

internal suspend fun CrmRepository.workspaceOwnerId(): String =
        client.postgrest.rpc("my_workspace").decodeList<WorkspaceRow>().firstOrNull()?.ownerId
            ?: userId()
