package uk.co.jwgardenservices.crm.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import uk.co.jwgardenservices.crm.data.model.BankTransaction
import uk.co.jwgardenservices.crm.data.model.Client
import uk.co.jwgardenservices.crm.data.model.Enquiry
import uk.co.jwgardenservices.crm.data.model.Invoice
import uk.co.jwgardenservices.crm.data.model.InvoiceEmailEvent
import uk.co.jwgardenservices.crm.data.model.JobTask
import uk.co.jwgardenservices.crm.data.model.InvoiceItem
import uk.co.jwgardenservices.crm.data.model.Quote
import uk.co.jwgardenservices.crm.data.model.QuoteItem
import uk.co.jwgardenservices.crm.data.model.ScheduledJob
import uk.co.jwgardenservices.crm.data.model.Settings
import uk.co.jwgardenservices.crm.data.model.TeamMember
import uk.co.jwgardenservices.crm.data.model.Visit

@Dao
interface ClientDao {
    @Query("SELECT * FROM clients ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<Client>>

    @Query("SELECT * FROM clients WHERE id = :id")
    suspend fun findById(id: String): Client?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<Client>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: Client)

    @Query("DELETE FROM clients WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM clients")
    suspend fun clear()
}

@Dao
interface VisitDao {
    @Query("SELECT * FROM visits ORDER BY visitDate DESC, createdAt DESC")
    fun observeAll(): Flow<List<Visit>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<Visit>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: Visit)

    @Query("SELECT * FROM visits WHERE id = :id")
    suspend fun findById(id: String): Visit?

    @Query("DELETE FROM visits WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM visits WHERE clientId = :clientId")
    suspend fun deleteByClient(clientId: String)

    @Query("UPDATE visits SET billed = 0, invoiceId = NULL WHERE invoiceId = :invoiceId")
    suspend fun unbillByInvoice(invoiceId: String)

    @Query("DELETE FROM visits")
    suspend fun clear()
}

@Dao
interface ScheduledJobDao {
    @Query("SELECT * FROM scheduled_jobs ORDER BY startsAt ASC")
    fun observeAll(): Flow<List<ScheduledJob>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<ScheduledJob>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: ScheduledJob)

    @Query("SELECT * FROM scheduled_jobs WHERE id = :id")
    suspend fun findById(id: String): ScheduledJob?

    @Query("SELECT * FROM scheduled_jobs WHERE seriesId = :seriesId ORDER BY startsAt ASC")
    suspend fun bySeries(seriesId: String): List<ScheduledJob>

    @Query("DELETE FROM scheduled_jobs WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM scheduled_jobs WHERE seriesId = :seriesId AND status = 'scheduled'")
    suspend fun deleteScheduledBySeries(seriesId: String)

    @Query("DELETE FROM scheduled_jobs WHERE clientId = :clientId")
    suspend fun deleteByClient(clientId: String)

    @Query("DELETE FROM scheduled_jobs")
    suspend fun clear()
}

@Dao
interface InvoiceDao {
    @Query("SELECT * FROM invoices ORDER BY issueDate DESC, createdAt DESC")
    fun observeAll(): Flow<List<Invoice>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<Invoice>)

    @Query("SELECT * FROM invoices WHERE id = :id")
    suspend fun findById(id: String): Invoice?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: Invoice)

    @Query("DELETE FROM invoices WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM invoices WHERE clientId = :clientId")
    suspend fun deleteByClient(clientId: String)

    @Query("DELETE FROM invoices")
    suspend fun clear()
}

@Dao
interface SettingsDao {
    @Query("SELECT * FROM settings LIMIT 1")
    fun observe(): Flow<Settings?>

    @Query("SELECT * FROM settings LIMIT 1")
    suspend fun get(): Settings?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: Settings)

    @Query("DELETE FROM settings")
    suspend fun clear()
}

@Dao
interface OutboxDao {
    @Insert
    suspend fun insert(entry: OutboxEntry)

    @Query("SELECT * FROM outbox ORDER BY createdAt ASC, id ASC LIMIT 1")
    suspend fun next(): OutboxEntry?

    @Query("SELECT COUNT(*) FROM outbox")
    suspend fun pendingCount(): Int

    @Query("SELECT COUNT(*) FROM outbox")
    fun observeCount(): Flow<Int>

    @Query("DELETE FROM outbox WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE outbox SET attempts = attempts + 1 WHERE id = :id")
    suspend fun bumpAttempts(id: String)

    @Query("DELETE FROM outbox")
    suspend fun clear()
}

@Dao
interface InvoiceItemDao {
    @Query("SELECT * FROM invoice_items WHERE invoiceId = :invoiceId ORDER BY itemDate ASC")
    suspend fun forInvoice(invoiceId: String): List<InvoiceItem>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<InvoiceItem>)

    @Query("DELETE FROM invoice_items WHERE invoiceId = :invoiceId")
    suspend fun deleteByInvoice(invoiceId: String)

    @Query("DELETE FROM invoice_items")
    suspend fun clear()
}

@Dao
interface QuoteDao {
    @Query("SELECT * FROM quotes ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<Quote>>

    @Query("SELECT * FROM quotes WHERE id = :id")
    suspend fun findById(id: String): Quote?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<Quote>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: Quote)

    @Query("DELETE FROM quotes WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM quotes")
    suspend fun clear()
}

@Dao
interface QuoteItemDao {
    @Query("SELECT * FROM quote_items WHERE quoteId = :quoteId ORDER BY sortOrder ASC")
    suspend fun forQuote(quoteId: String): List<QuoteItem>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<QuoteItem>)

    @Query("DELETE FROM quote_items WHERE quoteId = :quoteId")
    suspend fun deleteByQuote(quoteId: String)

    @Query("DELETE FROM quote_items")
    suspend fun clear()
}

@Dao
interface EnquiryDao {
    @Query("SELECT * FROM enquiries ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<Enquiry>>

    @Query("SELECT * FROM enquiries WHERE id = :id")
    suspend fun findById(id: String): Enquiry?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<Enquiry>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: Enquiry)

    @Query("DELETE FROM enquiries WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM enquiries")
    suspend fun clear()
}

@Dao
interface InvoiceEmailEventDao {
    @Query("SELECT * FROM invoice_email_events ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<InvoiceEmailEvent>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<InvoiceEmailEvent>)

    @Query("DELETE FROM invoice_email_events WHERE invoiceId = :invoiceId")
    suspend fun deleteByInvoice(invoiceId: String)

    @Query("DELETE FROM invoice_email_events")
    suspend fun clear()
}

@Dao
interface BankTransactionDao {
    @Query("SELECT * FROM bank_transactions WHERE reviewedAt IS NULL AND ignored = 0 ORDER BY postedAt DESC")
    fun observeReview(): Flow<List<BankTransaction>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<BankTransaction>)

    @Query("DELETE FROM bank_transactions WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM bank_transactions")
    suspend fun clear()
}

@Dao
interface TeamMemberDao {
    @Query("SELECT * FROM team_members ORDER BY createdAt ASC")
    fun observeAll(): Flow<List<TeamMember>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<TeamMember>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: TeamMember)

    @Query("DELETE FROM team_members WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM team_members")
    suspend fun clear()
}

@Dao
interface JobTaskDao {
    @Query("SELECT * FROM job_tasks WHERE jobId = :jobId ORDER BY position ASC, createdAt ASC")
    fun observeByJob(jobId: String): Flow<List<JobTask>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<JobTask>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: JobTask)

    @Query("DELETE FROM job_tasks WHERE jobId = :jobId")
    suspend fun deleteByJob(jobId: String)

    @Query("DELETE FROM job_tasks WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM job_tasks")
    suspend fun clear()
}
