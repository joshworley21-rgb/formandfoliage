package uk.co.jwgardenservices.crm.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import uk.co.jwgardenservices.crm.data.model.BankTransaction
import uk.co.jwgardenservices.crm.data.model.Client
import uk.co.jwgardenservices.crm.data.model.ClientDraft
import uk.co.jwgardenservices.crm.data.model.CreateInvoiceResult
import uk.co.jwgardenservices.crm.data.model.Enquiry
import uk.co.jwgardenservices.crm.data.model.Invoice
import uk.co.jwgardenservices.crm.data.model.InvoiceEmailEvent
import uk.co.jwgardenservices.crm.data.model.MonzoAccount
import uk.co.jwgardenservices.crm.data.model.MonzoStatus
import uk.co.jwgardenservices.crm.data.model.MonzoSyncResult
import uk.co.jwgardenservices.crm.data.model.InvoiceItem
import uk.co.jwgardenservices.crm.data.model.JobDraft
import uk.co.jwgardenservices.crm.data.model.JobPhotoWithUrl
import uk.co.jwgardenservices.crm.data.model.JobTask
import uk.co.jwgardenservices.crm.data.model.NewInvoiceInput
import uk.co.jwgardenservices.crm.data.model.Quote
import uk.co.jwgardenservices.crm.data.model.RebuildInvoiceResult
import uk.co.jwgardenservices.crm.data.model.QuoteDraft
import uk.co.jwgardenservices.crm.data.model.QuoteItem
import uk.co.jwgardenservices.crm.data.model.ScheduledJob
import uk.co.jwgardenservices.crm.data.model.SendInvoiceResult
import uk.co.jwgardenservices.crm.data.model.SendQuoteResult
import uk.co.jwgardenservices.crm.data.model.SeriesDraft
import uk.co.jwgardenservices.crm.data.model.SeriesEditInput
import uk.co.jwgardenservices.crm.data.model.Settings
import uk.co.jwgardenservices.crm.data.model.SettingsDraft
import uk.co.jwgardenservices.crm.data.model.TeamMember
import uk.co.jwgardenservices.crm.data.model.Visit
import uk.co.jwgardenservices.crm.data.model.VisitDraft
import uk.co.jwgardenservices.crm.data.offline.SyncEngine
import uk.co.jwgardenservices.crm.data.offline.SyncResult
import uk.co.jwgardenservices.crm.data.repository.CrmRepository
import uk.co.jwgardenservices.crm.data.repository.ServiceLocator
import uk.co.jwgardenservices.crm.domain.localDate
import uk.co.jwgardenservices.crm.domain.parseTimestamp

data class CrmUiState(
    val clients: List<Client> = emptyList(),
    val visits: List<Visit> = emptyList(),
    val invoices: List<Invoice> = emptyList(),
    val invoiceEmailEvents: List<InvoiceEmailEvent> = emptyList(),
    val paymentsToReview: List<BankTransaction> = emptyList(),
    val team: List<TeamMember> = emptyList(),
    val quotes: List<Quote> = emptyList(),
    val enquiries: List<Enquiry> = emptyList(),
    val jobs: List<ScheduledJob> = emptyList(),
    val settings: Settings? = null,
    val loading: Boolean = true,
    val saving: Boolean = false,
    val pendingWrites: Int = 0,
    val offline: Boolean = false,
    val error: String? = null,
)

/**
 * The screens read one snapshot of the offline cache. Room emits whenever a
 * local write lands or a sync refreshes the tables, so there is no manual
 * refetch after an edit. A background sync started from [init] tops the cache
 * up as soon as the app has signal.
 */
class CrmViewModel(
    private val repository: CrmRepository = ServiceLocator.crmRepository,
    private val syncEngine: SyncEngine = ServiceLocator.syncEngine,
) : ViewModel() {
    private val saving = MutableStateFlow(false)
    private val offline = MutableStateFlow(false)
    private val syncError = MutableStateFlow<String?>(null)
    private val pendingQuote = MutableStateFlow<QuoteDraft?>(null)

    /** A quote started from an enquiry, waiting for the Quotes screen to open it. */
    val pendingQuoteDraft: StateFlow<QuoteDraft?> = pendingQuote.asStateFlow()

    private val data = combine(
        repository.observeClients(),
        repository.observeVisits(),
        repository.observeInvoices(),
        repository.observeJobs(),
        repository.observeSettings(),
    ) { clients, visits, invoices, jobs, settings ->
        Snapshot(clients, visits, invoices, jobs, settings)
    }

    private val dataWithQuotes = combine(
        data,
        repository.observeQuotes(),
        repository.observeEnquiries(),
        repository.observeInvoiceEmailEvents(),
        repository.observePaymentsToReview(),
    ) { snapshot, quotes, enquiries, emailEvents, payments ->
        snapshot.copy(
            quotes = quotes,
            enquiries = enquiries,
            invoiceEmailEvents = emailEvents,
            paymentsToReview = payments,
        )
    }

    private val dataWithTeam = combine(
        dataWithQuotes,
        repository.observeTeam(),
    ) { snapshot, team ->
        snapshot.copy(team = team)
    }

    private val syncStatus = combine(
        repository.observePendingWrites(),
        saving,
        offline,
        syncError,
    ) { pending, isSaving, isOffline, error ->
        SyncStatus(pending, isSaving, isOffline, error)
    }

    val state: StateFlow<CrmUiState> = combine(dataWithTeam, syncStatus) { snapshot, sync ->
        CrmUiState(
            clients = snapshot.clients,
            visits = snapshot.visits,
            invoices = snapshot.invoices,
            invoiceEmailEvents = snapshot.invoiceEmailEvents,
            paymentsToReview = snapshot.paymentsToReview,
            team = snapshot.team,
            quotes = snapshot.quotes,
            enquiries = snapshot.enquiries,
            jobs = snapshot.jobs,
            settings = snapshot.settings,
            loading = false,
            saving = sync.saving,
            pendingWrites = sync.pending,
            offline = sync.offline,
            error = sync.error,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = CrmUiState(),
    )

    init {
        viewModelScope.launch { syncNow() }
    }

    fun refresh() {
        viewModelScope.launch { syncNow() }
    }

    suspend fun saveClient(draft: ClientDraft, existing: Client?) {
        withSaving {
            if (existing == null) {
                repository.createClient(draft)
            } else {
                repository.updateClient(existing, draft)
            }
        }
    }

    suspend fun deleteClient(id: String) {
        withSaving { repository.deleteClient(id) }
    }

    suspend fun saveVisit(draft: VisitDraft) {
        withSaving { repository.saveVisit(draft) }
    }

    suspend fun deleteVisit(id: String) {
        withSaving { repository.deleteVisit(id) }
    }

    suspend fun saveJob(draft: JobDraft) {
        withSaving { repository.saveJob(draft) }
    }

    suspend fun bookSeries(draft: SeriesDraft) {
        withSaving { repository.bookSeries(draft) }
    }

    suspend fun deleteJob(id: String, wholeSeriesId: String? = null) {
        withSaving { repository.deleteJob(id, wholeSeriesId) }
    }

    suspend fun editSeries(input: SeriesEditInput) {
        withSaving {
            // Get any pending local changes onto the server before reading the
            // run back, so the retime is based on the latest times.
            runCatching { syncEngine.syncNow() }
            repository.editSeries(input)
        }
    }

    suspend fun completeJobWithVisit(jobId: String, visitId: String) {
        withSaving { repository.completeJobWithVisit(jobId, visitId) }
    }

    suspend fun staffCompleteJob(jobId: String, hours: Double, description: String?) {
        withSaving { repository.staffCompleteJob(jobId, hours, description) }
    }

    fun observeJobTasks(jobId: String): kotlinx.coroutines.flow.Flow<List<JobTask>> =
        repository.observeJobTasks(jobId)

    suspend fun refreshJobTasks(jobId: String) = repository.refreshJobTasks(jobId)

    suspend fun addJobTask(jobId: String, title: String, position: Int) =
        repository.addJobTask(jobId, title, position)

    suspend fun setJobTaskDone(task: JobTask, done: Boolean) =
        repository.setJobTaskDone(task, done)

    suspend fun deleteJobTask(id: String) = repository.deleteJobTask(id)

    suspend fun jobPhotos(jobId: String): List<JobPhotoWithUrl> =
        repository.jobPhotos(jobId)

    suspend fun uploadJobPhoto(
        jobId: String,
        bytes: ByteArray,
        mimeType: String,
        extension: String,
    ) = repository.uploadJobPhoto(jobId, bytes, mimeType, extension)

    suspend fun updateJobPhotoCaption(id: String, caption: String?) =
        repository.updateJobPhotoCaption(id, caption)

    suspend fun deleteJobPhoto(id: String, path: String) =
        repository.deleteJobPhoto(id, path)

    suspend fun invoiceItems(invoiceId: String): List<InvoiceItem> =
        repository.invoiceItems(invoiceId)

    suspend fun createInvoice(input: NewInvoiceInput): CreateInvoiceResult =
        withSavingResult {
            // Flush anything waiting first: the refresh after creation must be
            // able to see the server's new invoice.
            runCatching { syncEngine.syncNow() }
            repository.createInvoice(input)
        }

    suspend fun sendInvoice(
        invoiceId: String,
        pdfBase64: String,
    ): SendInvoiceResult = withSavingResult {
        runCatching { syncEngine.syncNow() }
        repository.sendInvoice(invoiceId, pdfBase64)
    }

    suspend fun markInvoicePaid(id: String) {
        withSaving { repository.markInvoicePaid(id) }
    }

    suspend fun confirmPayment(transactionId: String, invoiceId: String) {
        withSaving { repository.confirmPayment(transactionId, invoiceId) }
    }

    suspend fun ignorePayment(transactionId: String) {
        withSaving { repository.ignorePayment(transactionId) }
    }

    suspend fun updateInvoiceDraft(id: String, dueDate: String?, notes: String?) {
        withSaving { repository.updateInvoiceDraft(id, dueDate, notes) }
    }

    suspend fun rebuildInvoice(id: String): RebuildInvoiceResult = withSavingResult {
        runCatching { syncEngine.syncNow() }
        repository.rebuildInvoice(id)
    }

    suspend fun deleteInvoice(id: String) {
        withSaving { repository.deleteInvoice(id) }
    }

    suspend fun quoteItems(quoteId: String): List<QuoteItem> = repository.quoteItems(quoteId)

    suspend fun createQuote(draft: QuoteDraft): String = withSavingResult {
        runCatching { syncEngine.syncNow() }
        repository.createQuote(draft)
    }

    suspend fun updateQuote(id: String, draft: QuoteDraft) {
        withSaving {
            runCatching { syncEngine.syncNow() }
            repository.updateQuote(id, draft)
        }
    }

    suspend fun sendQuote(
        quoteId: String,
        origin: String,
        message: String? = null,
    ): SendQuoteResult = withSavingResult {
        runCatching { syncEngine.syncNow() }
        repository.sendQuote(quoteId, origin, message)
    }

    suspend fun setQuoteStatus(id: String, status: String) {
        withSaving { repository.setQuoteStatus(id, status) }
    }

    suspend fun deleteQuote(id: String) {
        withSaving { repository.deleteQuote(id) }
    }

    suspend fun updateEnquiryStatus(id: String, status: String) {
        withSaving { repository.updateEnquiryStatus(id, status) }
    }

    suspend fun deleteEnquiry(id: String) {
        withSaving { repository.deleteEnquiry(id) }
    }

    suspend fun convertEnquiry(enquiry: Enquiry): String = withSavingResult {
        repository.convertEnquiryToClient(enquiry)
    }

    fun startQuoteFromEnquiry(enquiry: Enquiry) {
        pendingQuote.value = QuoteDraft(
            clientId = enquiry.clientId,
            enquiryId = enquiry.id,
            contactName = enquiry.name,
            contactEmail = enquiry.email,
            contactPhone = enquiry.phone,
            contactAddress = enquiry.address,
            notes = enquiry.message,
            items = emptyList(),
        )
    }

    fun consumePendingQuote() {
        pendingQuote.value = null
    }

    suspend fun inviteMember(email: String, name: String?) {
        withSaving { repository.inviteMember(email, name) }
    }

    suspend fun removeMember(id: String) {
        withSaving { repository.removeMember(id) }
    }

    suspend fun monzoStatus(): MonzoStatus = repository.monzoStatus()

    suspend fun monzoStart(): String = repository.monzoStart()

    suspend fun monzoAccounts(): List<MonzoAccount> = repository.monzoAccounts()

    suspend fun monzoSelect(accountId: String) {
        withSaving { repository.monzoSelect(accountId) }
    }

    suspend fun monzoDisconnect() {
        withSaving { repository.monzoDisconnect() }
    }

    suspend fun syncMonzo(): MonzoSyncResult = withSavingResult {
        repository.syncMonzo()
    }

    suspend fun saveSettings(draft: SettingsDraft) {
        withSaving {
            repository.saveSettings(draft)
            // Flush and refresh so a newly-created settings row brings back its
            // server-generated enquiry key, which the link depends on.
            runCatching { syncEngine.syncNow() }
        }
    }

    private suspend fun syncNow() {
        val result = runCatching { syncEngine.syncNow() }
            .getOrElse { SyncResult(error = it.message ?: "Could not sync") }
        offline.value = result.offline
        syncError.value = result.error
    }

    private suspend fun withSaving(block: suspend () -> Unit) {
        withSavingResult { block() }
    }

    private suspend fun <T> withSavingResult(block: suspend () -> T): T {
        saving.value = true
        return try {
            block()
        } finally {
            saving.value = false
        }
    }

    private data class Snapshot(
        val clients: List<Client>,
        val visits: List<Visit>,
        val invoices: List<Invoice>,
        val jobs: List<ScheduledJob>,
        val settings: Settings?,
        val quotes: List<Quote> = emptyList(),
        val enquiries: List<Enquiry> = emptyList(),
        val invoiceEmailEvents: List<InvoiceEmailEvent> = emptyList(),
        val paymentsToReview: List<BankTransaction> = emptyList(),
        val team: List<TeamMember> = emptyList(),
    )

    private data class SyncStatus(
        val pending: Int,
        val saving: Boolean,
        val offline: Boolean,
        val error: String?,
    )
}

/** Jobs on a given local day, earliest first. */
fun List<ScheduledJob>.onDay(day: LocalDate): List<ScheduledJob> = filter {
    parseTimestamp(it.startsAt)?.localDate() == day
}.sortedBy { it.startsAt }

/** Whole days between two dates, for "x days over" labels. */
fun daysBetween(from: LocalDate, to: LocalDate): Long = ChronoUnit.DAYS.between(from, to)
