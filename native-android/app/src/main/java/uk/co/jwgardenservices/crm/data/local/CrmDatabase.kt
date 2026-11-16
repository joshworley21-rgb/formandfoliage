package uk.co.jwgardenservices.crm.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.RoomDatabase
import uk.co.jwgardenservices.crm.data.model.Client
import uk.co.jwgardenservices.crm.data.model.Enquiry
import uk.co.jwgardenservices.crm.data.model.Invoice
import uk.co.jwgardenservices.crm.data.model.InvoiceEmailEvent
import uk.co.jwgardenservices.crm.data.model.InvoiceItem
import uk.co.jwgardenservices.crm.data.model.JobTask
import uk.co.jwgardenservices.crm.data.model.PendingVoiceCommand
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
        PendingVoiceCommand::class,
    ],
    version = 12,
    exportSchema = true,
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
    abstract fun pendingVoiceDao(): PendingVoiceDao
    abstract fun outboxDao(): OutboxDao
}

private val MIGRATION_10_11 = object : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE outbox ADD COLUMN failedReason TEXT")
    }
}

/** Additive: a queue of spoken words captured when the phone had no signal. */
private val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `pending_voice` (" +
                "`id` TEXT NOT NULL, " +
                "`transcript` TEXT NOT NULL, " +
                "`createdAt` TEXT NOT NULL, " +
                "`clientId` TEXT, " +
                "`clientName` TEXT, " +
                "`processed` INTEGER NOT NULL, " +
                "`outcome` TEXT, " +
                "PRIMARY KEY(`id`))",
        )
    }
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
                .addMigrations(MIGRATION_10_11, MIGRATION_11_12)
                // Only reached for versions older than 10, which never held
                // unsynced work from a shipped build. From 11 onward every
                // schema change needs a real migration so the outbox survives.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
                .also { instance = it }
        }
}
