package uk.co.jwgardenservices.crm.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import uk.co.jwgardenservices.crm.domain.Frequency

/*
 * Wire models for the CRM tables. Dates and timestamps stay as ISO strings:
 * Postgres is the source of truth for them and the UI parses them with
 * java.time only where it needs to reason about a day.
 *
 * Numeric columns are Double here because PostgREST returns JSON numbers. All
 * money arithmetic is rounded to two decimals in the domain layer.
 */

@Serializable
@Entity(tableName = "clients")
data class Client(
    @PrimaryKey val id: String,
    @SerialName("user_id") val userId: String,
    val name: String,
    val address: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val notes: String? = null,
    @SerialName("billing_mode") val billingMode: String = "hourly",
    @SerialName("hourly_rate") val hourlyRate: Double = 0.0,
    @SerialName("fixed_price") val fixedPrice: Double = 0.0,
    val active: Boolean = true,
    @SerialName("payment_reminders") val paymentReminders: Boolean = false,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
@Entity(
    tableName = "visits",
    indices = [Index("clientId"), Index("visitDate")],
)
data class Visit(
    @PrimaryKey val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("client_id") val clientId: String,
    @SerialName("visit_date") val visitDate: String,
    val hours: Double = 0.0,
    val description: String? = null,
    @SerialName("materials_cost") val materialsCost: Double = 0.0,
    @SerialName("materials_note") val materialsNote: String? = null,
    val amount: Double = 0.0,
    val billed: Boolean = false,
    @SerialName("invoice_id") val invoiceId: String? = null,
    val missed: Boolean = false,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
@Entity(
    tableName = "scheduled_jobs",
    indices = [Index("clientId"), Index("startsAt")],
)
data class ScheduledJob(
    @PrimaryKey val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("client_id") val clientId: String,
    @SerialName("series_id") val seriesId: String? = null,
    @SerialName("starts_at") val startsAt: String,
    @SerialName("duration_minutes") val durationMinutes: Int = 60,
    val status: String = "scheduled",
    val notes: String? = null,
    @SerialName("visit_id") val visitId: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
@Entity(
    tableName = "invoices",
    indices = [Index("clientId"), Index("issueDate")],
)
data class Invoice(
    @PrimaryKey val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("client_id") val clientId: String,
    val number: String,
    @SerialName("issue_date") val issueDate: String,
    @SerialName("due_date") val dueDate: String? = null,
    @SerialName("period_start") val periodStart: String? = null,
    @SerialName("period_end") val periodEnd: String? = null,
    val subtotal: Double = 0.0,
    @SerialName("vat_amount") val vatAmount: Double = 0.0,
    val total: Double = 0.0,
    val status: String = "draft",
    @SerialName("sent_at") val sentAt: String? = null,
    @SerialName("paid_at") val paidAt: String? = null,
    @SerialName("pdf_path") val pdfPath: String? = null,
    val notes: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
@Entity(tableName = "invoice_items", indices = [Index("invoiceId")])
data class InvoiceItem(
    @PrimaryKey val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("invoice_id") val invoiceId: String,
    @SerialName("visit_id") val visitId: String? = null,
    @SerialName("item_date") val itemDate: String? = null,
    val description: String,
    val quantity: Double = 1.0,
    @SerialName("unit_price") val unitPrice: Double = 0.0,
    val amount: Double = 0.0,
    @SerialName("created_at") val createdAt: String? = null,
    val grouped: Boolean = false,
    val position: Int = 0,
)

@Serializable
@Entity(tableName = "settings")
data class Settings(
    @PrimaryKey @SerialName("user_id") val userId: String,
    @SerialName("business_name") val businessName: String = "JW Garden Services",
    val address: String? = null,
    val phone: String? = null,
    val email: String? = null,
    @SerialName("reply_to_email") val replyToEmail: String? = null,
    @SerialName("public_site_url") val publicSiteUrl: String? = null,
    @SerialName("vat_registered") val vatRegistered: Boolean = false,
    @SerialName("vat_number") val vatNumber: String? = null,
    @SerialName("vat_rate") val vatRate: Double = 20.0,
    @SerialName("invoice_prefix") val invoicePrefix: String = "JW-",
    @SerialName("invoice_counter") val invoiceCounter: Int = 0,
    @SerialName("payment_terms") val paymentTerms: String? = null,
    @SerialName("bank_details") val bankDetails: String? = null,
    @SerialName("quote_prefix") val quotePrefix: String = "Q-",
    @SerialName("quote_counter") val quoteCounter: Int = 0,
    @SerialName("quote_validity_days") val quoteValidityDays: Int = 30,
    @SerialName("quote_terms") val quoteTerms: String? = null,
    @SerialName("enquiry_key") val enquiryKey: String? = null,
    @SerialName("email_subject_template") val emailSubjectTemplate: String =
        "Invoice {{invoice_number}} from {{business_name}}",
    @SerialName("email_body_template") val emailBodyTemplate: String =
        "Hi {{client_name}},\n\nPlease find attached invoice {{invoice_number}} for recent garden work.\n\nTotal due: {{total}}{{due_clause}}.\n\nMany thanks,\n{{business_name}}",
)

/** The writable part of a client, as the editor collects it. */
data class ClientDraft(
    val name: String,
    val address: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val notes: String? = null,
    val billingMode: String = "hourly",
    val hourlyRate: Double = 0.0,
    val fixedPrice: Double = 0.0,
    val paymentReminders: Boolean = false,
)

/** The writable part of a visit, as the visit editor collects it. */
data class VisitDraft(
    val id: String? = null,
    val clientId: String,
    val visitDate: String,
    val hours: Double,
    val materialsCost: Double,
    val materialsNote: String? = null,
    val description: String? = null,
    val amount: Double,
    val missed: Boolean = false,
    /** A matching scheduled job this visit finishes off, if any. */
    val completesJobId: String? = null,
    /** What that job becomes: a missed visit closes it as missed. */
    val jobOutcome: String = "done",
)

/** The writable part of one scheduled job. */
data class JobDraft(
    val id: String? = null,
    val clientId: String,
    val date: String,
    val time: String,
    val durationMinutes: Int,
    val notes: String? = null,
)

/** A new repeating run, booked as real jobs up front. */
data class SeriesDraft(
    val clientId: String,
    val date: String,
    val time: String,
    val durationMinutes: Int,
    val notes: String? = null,
    val frequency: Frequency,
    val occurrences: Int,
)

/** The writable input for the create-invoice Edge Function. */
data class NewInvoiceInput(
    val clientId: String,
    val visitIds: List<String>,
    val retainerDescription: String? = null,
    val retainerAmount: Double = 0.0,
    val notes: String? = null,
    val dueDate: String? = null,
    val dueInDays: Int = 14,
)

@Serializable
data class CreateInvoiceResult(
    @SerialName("invoiceId") val invoiceId: String,
    val number: String,
    val total: Double,
)

@Serializable
data class SendInvoiceResult(
    val sent: Boolean,
    val to: String? = null,
    val reason: String? = null,
)

@Serializable
@Entity(tableName = "quotes", indices = [Index("clientId"), Index("issueDate")])
data class Quote(
    @PrimaryKey val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("client_id") val clientId: String? = null,
    @SerialName("enquiry_id") val enquiryId: String? = null,
    val number: String,
    @SerialName("contact_name") val contactName: String,
    @SerialName("contact_email") val contactEmail: String? = null,
    @SerialName("contact_phone") val contactPhone: String? = null,
    @SerialName("contact_address") val contactAddress: String? = null,
    @SerialName("issue_date") val issueDate: String,
    @SerialName("valid_until") val validUntil: String? = null,
    val subtotal: Double = 0.0,
    @SerialName("vat_amount") val vatAmount: Double = 0.0,
    val total: Double = 0.0,
    val status: String = "draft",
    val notes: String? = null,
    val terms: String? = null,
    @SerialName("public_key") val publicKey: String,
    @SerialName("sent_at") val sentAt: String? = null,
    @SerialName("decided_at") val decidedAt: String? = null,
    @SerialName("decline_reason") val declineReason: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
@Entity(tableName = "quote_items", indices = [Index("quoteId")])
data class QuoteItem(
    @PrimaryKey val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("quote_id") val quoteId: String,
    val description: String,
    val quantity: Double = 1.0,
    @SerialName("unit_price") val unitPrice: Double = 0.0,
    val amount: Double = 0.0,
    @SerialName("sort_order") val sortOrder: Int = 0,
    @SerialName("created_at") val createdAt: String? = null,
)

/** A line as the quote editor holds it before it is saved. */
data class QuoteItemDraft(
    val description: String,
    val quantity: Double,
    val unitPrice: Double,
)

data class QuoteDraft(
    val id: String? = null,
    val clientId: String? = null,
    val enquiryId: String? = null,
    val contactName: String,
    val contactEmail: String? = null,
    val contactPhone: String? = null,
    val contactAddress: String? = null,
    val validUntil: String? = null,
    val notes: String? = null,
    val terms: String? = null,
    val items: List<QuoteItemDraft>,
)

@Serializable
data class SendQuoteResult(
    val sent: Boolean,
    val to: String? = null,
    val reason: String? = null,
)

@Serializable
@Entity(tableName = "enquiries", indices = [Index("createdAt"), Index("status")])
data class Enquiry(
    @PrimaryKey val id: String,
    @SerialName("user_id") val userId: String,
    val name: String,
    val email: String? = null,
    val phone: String? = null,
    val address: String? = null,
    val message: String? = null,
    val source: String = "website",
    val status: String = "new",
    @SerialName("client_id") val clientId: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

/** The editable part of business settings. */
data class SettingsDraft(
    val businessName: String,
    val address: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val replyToEmail: String? = null,
    val publicSiteUrl: String? = null,
    val vatRegistered: Boolean = false,
    val vatNumber: String? = null,
    val vatRate: Double = 20.0,
    val invoicePrefix: String = "JW-",
    val paymentTerms: String? = null,
    val bankDetails: String? = null,
    val quoteValidityDays: Int = 30,
    val quoteTerms: String? = null,
    val emailSubjectTemplate: String = "Invoice {{invoice_number}} from {{business_name}}",
    val emailBodyTemplate: String =
        "Hi {{client_name}},\n\nPlease find attached invoice {{invoice_number}} for recent garden work.\n\nTotal due: {{total}}{{due_clause}}.\n\nMany thanks,\n{{business_name}}",
)

/** One change applied to every remaining visit in a repeating run. */
data class SeriesEditInput(
    val seriesId: String,
    val startTime: String,
    /** Null means keep each visit's existing day and only move the clock. */
    val frequency: Frequency?,
    val durationMinutes: Int,
    val notes: String?,
)

@Serializable
data class RebuildInvoiceResult(
    val number: String,
    val lines: Int,
    val released: Int = 0,
    val total: Double,
)

@Serializable
@Entity(tableName = "invoice_email_events", indices = [Index("invoiceId"), Index("createdAt")])
data class InvoiceEmailEvent(
    @PrimaryKey val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("invoice_id") val invoiceId: String,
    val status: String,
    val recipient: String? = null,
    val detail: String? = null,
    @SerialName("provider_message_id") val providerMessageId: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class WorkspaceRow(
    @SerialName("owner_id") val ownerId: String,
    val role: String,
)

/** The only client fields staff are allowed to see. */
@Serializable
data class StaffClientRow(
    val id: String,
    val name: String,
    val address: String? = null,
    val notes: String? = null,
)

@Serializable
@Entity(tableName = "team_members", indices = [Index("email")])
data class TeamMember(
    @PrimaryKey val id: String,
    @SerialName("owner_id") val ownerId: String,
    val email: String,
    val name: String? = null,
    val role: String = "staff",
    @SerialName("user_id") val userId: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
@Entity(tableName = "job_tasks", indices = [Index("jobId"), Index("position")])
data class JobTask(
    @PrimaryKey val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("job_id") val jobId: String,
    val title: String,
    val done: Boolean = false,
    val position: Int = 0,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class JobPhoto(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("job_id") val jobId: String,
    @SerialName("task_id") val taskId: String? = null,
    @SerialName("storage_path") val storagePath: String,
    val caption: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

data class JobPhotoWithUrl(val photo: JobPhoto, val url: String?)
