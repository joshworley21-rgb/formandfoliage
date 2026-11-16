package uk.co.jwgardenservices.crm.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.RoomDatabase
import uk.co.jwgardenservices.crm.data.model.Client
import uk.co.jwgardenservices.crm.data.model.ClientVisitNote
import uk.co.jwgardenservices.crm.data.model.Enquiry
import uk.co.jwgardenservices.crm.data.model.Invoice
import uk.co.jwgardenservices.crm.data.model.InvoiceEmailEvent
import uk.co.jwgardenservices.crm.data.model.InvoiceItem
import uk.co.jwgardenservices.crm.data.model.JobTask
import uk.co.jwgardenservices.crm.data.model.PendingVoiceCommand
import uk.co.jwgardenservices.crm.data.model.Quote
import uk.co.jwgardenservices.crm.data.model.VoiceAlias
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
        VoiceAlias::class,
        ClientVisitNote::class,
    ],
    version = 14,
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
    abstract fun voiceAliasDao(): VoiceAliasDao
    abstract fun clientVisitNoteDao(): ClientVisitNoteDao
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

/**
 * Additive: nicknames the owner says for clients ("the rectory"). Local
 * only — never synced — so the outbox payloads stay unchanged.
 */
private val MIGRATION_12_13 = object : Migration(12, 13) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `voice_aliases` (" +
                "`id` TEXT NOT NULL, " +
                "`clientId` TEXT NOT NULL, " +
                "`alias` TEXT NOT NULL, " +
                "PRIMARY KEY(`id`))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_voice_aliases_clientId` ON `voice_aliases` (`clientId`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_voice_aliases_alias` ON `voice_aliases` (`alias`)")
    }
}

/** Additive: the reminder the owner wants raised at a client's next visit. */
private val MIGRATION_13_14 = object : Migration(13, 14) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `client_visit_notes` (" +
                "`clientId` TEXT NOT NULL, " +
                "`note` TEXT NOT NULL, " +
                "`updatedAt` TEXT NOT NULL, " +
                "PRIMARY KEY(`clientId`))",
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
                .addMigrations(MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14)
                // Only reached for versions older than 10, which never held
                // unsynced work from a shipped build. From 11 onward every
                // schema change needs a real migration so the outbox survives.
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
                .also { instance = it }
        }
}
