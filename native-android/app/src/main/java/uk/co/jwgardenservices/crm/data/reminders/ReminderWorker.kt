package uk.co.jwgardenservices.crm.data.reminders

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import uk.co.jwgardenservices.crm.MainActivity
import uk.co.jwgardenservices.crm.R
import uk.co.jwgardenservices.crm.data.local.DatabaseProvider
import uk.co.jwgardenservices.crm.domain.buildReminderSummary
import uk.co.jwgardenservices.crm.domain.formatTime
import uk.co.jwgardenservices.crm.domain.money
import uk.co.jwgardenservices.crm.domain.parseTimestamp

/**
 * Evening nudge built entirely from the offline cache, so it works without
 * signal and never touches the server. Signing out clears the cache, which
 * silences it for the next person.
 */
class ReminderWorker(
    private val context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!canNotify(context)) return Result.success()

        val database = DatabaseProvider.get(context)
        val summary = buildReminderSummary(
            jobs = database.scheduledJobDao().observeAll().first(),
            invoices = database.invoiceDao().observeAll().first(),
            visits = database.visitDao().observeAll().first(),
        )
        if (summary.isEmpty) return Result.success()

        val clients = database.clientDao().observeAll().first().associate { it.id to it.name }
        val lines = buildList {
            val jobs = summary.tomorrowJobs
            if (jobs.isNotEmpty()) {
                add("${jobs.size} ${if (jobs.size == 1) "job" else "jobs"} tomorrow")
                jobs.take(4).forEach { job ->
                    val time = parseTimestamp(job.startsAt)?.formatTime().orEmpty()
                    add("$time  ${clients[job.clientId] ?: "Client"}")
                }
                if (jobs.size > 4) add("…and ${jobs.size - 4} more")
            }
            if (summary.overdue.isNotEmpty()) {
                val total = summary.overdue.sumOf { it.total }
                add(
                    "${summary.overdue.size} overdue ${if (summary.overdue.size == 1) "invoice" else "invoices"} (${money(total)})",
                )
            }
            if (summary.unbilledVisits.isNotEmpty()) {
                val total = summary.unbilledVisits.sumOf { it.amount }
                val size = summary.unbilledVisits.size
                add(
                    "$size ${if (size == 1) "visit" else "visits"} to bill (${money(total)})",
                )
            }
        }

        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(lines.first())
            .setContentText(lines.drop(1).firstOrNull())
            .setStyle(NotificationCompat.InboxStyle().also { style -> lines.drop(1).forEach(style::addLine) })
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        return Result.success()
    }

    companion object {
        private const val CHANNEL = "daily-reminders"
        private const val NOTIFICATION_ID = 4101
        private const val UNIQUE = "crm-daily-reminder"
        private val SEND_AT = LocalTime.of(18, 0)

        fun canNotify(context: Context): Boolean =
            Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Daily reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "Tomorrow's jobs and overdue invoices"
                },
            )
        }

        /** Runs once a day at 18:00 local time. KEEP means reopening the app never resets the clock. */
        fun schedule(context: Context) {
            val now = LocalDateTime.now()
            var next = now.toLocalDate().atTime(SEND_AT)
            if (!next.isAfter(now)) next = next.plusDays(1)
            val delay = Duration.between(now, next)

            val request = PeriodicWorkRequestBuilder<ReminderWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(delay.toMinutes(), TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
