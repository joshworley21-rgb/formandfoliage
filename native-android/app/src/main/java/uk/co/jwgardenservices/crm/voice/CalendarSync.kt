package uk.co.jwgardenservices.crm.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/** How a whole device calendar is treated by the assistant. */
enum class CalendarTag(val storage: String, val label: String) {
    Business("business", "Business"),
    Personal("personal", "Personal (busy only)"),
    Mixed("mixed", "Mixed (Personal: prefix)"),
    Ignore("ignore", "Ignore"),
}

/** One calendar exposed by Android, including Google and local accounts. */
data class DeviceCalendar(
    val id: Long,
    val name: String,
    val account: String,
    val colour: Int,
    val primary: Boolean,
)

/**
 * One event read live from the phone's calendar provider.
 *
 * Personal events deliberately lose their title and location: the schedule can
 * show that the time is blocked without importing what the meeting is about.
 */
data class CalendarEvent(
    val eventId: Long,
    val calendarId: Long,
    val calendarName: String,
    val title: String,
    val start: Instant,
    val end: Instant,
    val allDay: Boolean,
    val business: Boolean,
    val location: String?,
)

private val Context.calendarDataStore by preferencesDataStore(name = "calendar_tags")
private val TAGS_KEY = stringPreferencesKey("calendar_tags")

/**
 * Read-only Google/device calendar access.
 *
 * Classification is per calendar, never per event: a "Business" calendar is
 * read with titles, a "Personal" calendar becomes anonymous busy blocks, and
 * anything else is ignored. That makes the distinction unambiguous and means
 * one setting covers every future event.
 */
object CalendarSync {

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    fun tagsFlow(context: Context): Flow<Map<Long, CalendarTag>> =
        context.calendarDataStore.data.map { preferences ->
            preferences[TAGS_KEY]
                ?.lineSequence()
                ?.mapNotNull { line ->
                    val id = line.substringBefore('=', "").toLongOrNull() ?: return@mapNotNull null
                    val tag = CalendarTag.entries.firstOrNull {
                        it.storage == line.substringAfter('=', "")
                    } ?: return@mapNotNull null
                    id to tag
                }
                ?.toMap()
                ?: emptyMap()
        }

    suspend fun tagOf(context: Context, calendarId: Long): CalendarTag =
        tagsFlow(context).first()[calendarId] ?: CalendarTag.Ignore

    suspend fun setTag(context: Context, calendarId: Long, tag: CalendarTag) {
        context.calendarDataStore.edit { preferences ->
            val current = preferences[TAGS_KEY]
                ?.lineSequence()
                ?.filterNot { it.substringBefore('=') == calendarId.toString() }
                ?.toMutableList()
                ?: mutableListOf()
            if (tag != CalendarTag.Ignore) {
                current.add("$calendarId=${tag.storage}")
            }
            preferences[TAGS_KEY] = current.joinToString("\n")
        }
    }

    /**
     * In a Mixed calendar, personal events are the ones prefixed "Personal"
     * or "Private". Everything else is business, so a single calendar can
     * hold both without leaking personal titles.
     */
    private fun isPersonalTitle(title: String?): Boolean {
        val lower = title?.trim()?.lowercase() ?: return false
        return lower.startsWith("personal") || lower.startsWith("private")
    }

    /** Calendars the user can tag. Empty without permission. */
    suspend fun calendars(context: Context): List<DeviceCalendar> = withContext(Dispatchers.IO) {
        if (!hasPermission(context)) return@withContext emptyList()
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.CALENDAR_COLOR,
            CalendarContract.Calendars.IS_PRIMARY,
        )
        runCatching {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                null,
                null,
                "${CalendarContract.Calendars.ACCOUNT_NAME} ASC," +
                    "${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} ASC",
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
                val nameIndex =
                    cursor.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
                val accountIndex =
                    cursor.getColumnIndexOrThrow(CalendarContract.Calendars.ACCOUNT_NAME)
                val colourIndex =
                    cursor.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_COLOR)
                val primaryIndex =
                    cursor.getColumnIndexOrThrow(CalendarContract.Calendars.IS_PRIMARY)
                buildList {
                    while (cursor.moveToNext()) {
                        add(
                            DeviceCalendar(
                                id = cursor.getLong(idIndex),
                                name = cursor.getString(nameIndex) ?: "Calendar",
                                account = cursor.getString(accountIndex) ?: "",
                                colour = cursor.getInt(colourIndex),
                                primary = cursor.getInt(primaryIndex) == 1,
                            ),
                        )
                    }
                }
            } ?: emptyList()
        }.onFailure {
            android.util.Log.w("CalendarSync", "calendar list failed", it)
        }.getOrDefault(emptyList())
    }

    /**
     * Business events with their titles, personal events as anonymous busy
     * blocks. Live read, so Google edits appear without a sync job.
     */
    suspend fun events(
        context: Context,
        from: LocalDate,
        to: LocalDate,
    ): List<CalendarEvent> = withContext(Dispatchers.IO) {
        if (!hasPermission(context)) return@withContext emptyList()
        val tags = tagsFlow(context).first()
        if (tags.values.none { it != CalendarTag.Ignore }) return@withContext emptyList()

        val zone = ZoneId.systemDefault()
        val startMillis = from.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMillis = to.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(startMillis.toString())
            .appendPath(endMillis.toString())
            .build()
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Instances.ALL_DAY,
            CalendarContract.Instances.EVENT_LOCATION,
            CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
        )

        runCatching {
            context.contentResolver.query(
                uri,
                projection,
                null,
                null,
                "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { cursor ->
                val eventIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_ID)
                val calendarIndex =
                    cursor.getColumnIndexOrThrow(CalendarContract.Instances.CALENDAR_ID)
                val titleIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
                val beginIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
                val endIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.END)
                val allDayIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY)
                val locationIndex =
                    cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_LOCATION)
                val calendarNameIndex =
                    cursor.getColumnIndexOrThrow(CalendarContract.Instances.CALENDAR_DISPLAY_NAME)
                buildList {
                    while (cursor.moveToNext()) {
                        val calendarId = cursor.getLong(calendarIndex)
                        val tag = tags[calendarId]
                        if (tag != null && tag != CalendarTag.Ignore) {
                            val rawTitle = cursor.getString(titleIndex)
                            val business = when (tag) {
                                CalendarTag.Business -> true
                                CalendarTag.Personal -> false
                                CalendarTag.Mixed -> !isPersonalTitle(rawTitle)
                                CalendarTag.Ignore -> false
                            }
                            add(
                                CalendarEvent(
                                    eventId = cursor.getLong(eventIndex),
                                    calendarId = calendarId,
                                    calendarName = cursor.getString(calendarNameIndex) ?: "Calendar",
                                    title = if (business) {
                                        rawTitle?.takeIf { it.isNotBlank() }
                                            ?: "Business meeting"
                                    } else {
                                        "Personal"
                                    },
                                    start = Instant.ofEpochMilli(cursor.getLong(beginIndex)),
                                    end = Instant.ofEpochMilli(cursor.getLong(endIndex)),
                                    allDay = cursor.getInt(allDayIndex) == 1,
                                    business = business,
                                    location = if (business) {
                                        cursor.getString(locationIndex)?.takeIf { it.isNotBlank() }
                                    } else {
                                        null
                                    },
                                ),
                            )
                        }
                    }
                }
            } ?: emptyList()
        }.onFailure {
            android.util.Log.w("CalendarSync", "calendar events failed", it)
        }.getOrDefault(emptyList())
    }
}
