package uk.co.jwgardenservices.crm.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import uk.co.jwgardenservices.crm.data.model.Client
import uk.co.jwgardenservices.crm.data.model.Enquiry
import uk.co.jwgardenservices.crm.data.model.Invoice
import uk.co.jwgardenservices.crm.data.model.InvoiceEmailEvent
import uk.co.jwgardenservices.crm.data.model.InvoiceItem
import uk.co.jwgardenservices.crm.data.model.JobTask
import uk.co.jwgardenservices.crm.data.model.Quote
import uk.co.jwgardenservices.crm.data.model.QuoteItem
import uk.co.jwgardenservices.crm.data.model.ScheduledJob
import uk.co.jwgardenservices.crm.data.model.Settings
import uk.co.jwgardenservices.crm.data.model.TeamMember
import uk.co.jwgardenservices.crm.data.model.Visit

@Database(
    entities = [
        Client::class,
        Enquiry::class,
        Visit::class,
        ScheduledJob::class,
        Invoice::class,
        InvoiceItem::class,
        InvoiceEmailEvent::class,
        JobTask::class,
        Quote::class,
        QuoteItem::class,
        Settings::class,
        TeamMember::class,
        OutboxEntry::class,
    ],
    version = 10,
    exportSchema = false,
)
abstract class CrmDatabase : RoomDatabase() {
    abstract fun clientDao(): ClientDao
    abstract fun enquiryDao(): EnquiryDao
    abstract fun visitDao(): VisitDao
    abstract fun scheduledJobDao(): ScheduledJobDao
    abstract fun invoiceDao(): InvoiceDao
    abstract fun invoiceItemDao(): InvoiceItemDao
    abstract fun invoiceEmailEventDao(): InvoiceEmailEventDao
    abstract fun jobTaskDao(): JobTaskDao
    abstract fun quoteDao(): QuoteDao
    abstract fun quoteItemDao(): QuoteItemDao
    abstract fun settingsDao(): SettingsDao
    abstract fun teamMemberDao(): TeamMemberDao
    abstract fun outboxDao(): OutboxDao
}

object DatabaseProvider {
    @Volatile
    private var instance: CrmDatabase? = null

    fun get(context: Context): CrmDatabase =
        instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                CrmDatabase::class.java,
                "jw-crm.db",
            )
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
                .also { instance = it }
        }
}
