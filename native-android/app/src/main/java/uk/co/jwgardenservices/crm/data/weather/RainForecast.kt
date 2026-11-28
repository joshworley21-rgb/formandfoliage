package uk.co.jwgardenservices.crm.data.weather

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import uk.co.jwgardenservices.crm.core.CrashLog
import uk.co.jwgardenservices.crm.data.repository.ServiceLocator
import uk.co.jwgardenservices.crm.domain.DayRain
import java.net.URLEncoder
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * The next week's rain for the business's town, from Open-Meteo (free, no
 * key). Kept for three hours, and the last copy is saved so the Rota still
 * shows it with no signal.
 */
object RainForecast {
    private const val TAG = "RainForecast"
    private const val PREFS = "rain-forecast"
    private const val KEY = "days"
    private const val KEY_AT = "fetched_at"
    private const val FRESH_MS = 3 * 60 * 60 * 1000L

    private val _days = MutableStateFlow<Map<LocalDate, DayRain>>(emptyMap())
    val days: StateFlow<Map<LocalDate, DayRain>> = _days.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    /** The forecast, refreshed if it is more than a few hours old. Never throws. */
    suspend fun refresh(context: Context): Map<LocalDate, DayRain> = withContext(Dispatchers.IO) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = decode(prefs.getString(KEY, null))
        if (_days.value.isEmpty() && saved.isNotEmpty()) _days.value = saved
        val age = System.currentTimeMillis() - prefs.getLong(KEY_AT, 0L)
        if (age in 0 until FRESH_MS && saved.isNotEmpty()) return@withContext saved
        val fetched = runCatching { fetch(context) }
            .onFailure { CrashLog.note(context, TAG, "forecast failed: ${it.message}") }
            .getOrNull()
        if (fetched.isNullOrEmpty()) return@withContext _days.value
        prefs.edit().putString(KEY, encode(fetched)).putLong(KEY_AT, System.currentTimeMillis()).apply()
        _days.value = fetched
        fetched
    }

    private suspend fun fetch(context: Context): Map<LocalDate, DayRain>? {
        val town = runCatching {
            ServiceLocator.crmRepository.observeSettings().first()?.address
                ?.split(',', '\n')?.map { it.trim() }
                ?.firstOrNull { part -> part.length > 2 && part.any { it.isLetter() } && part.none { it.isDigit() } }
        }.getOrNull()
        var place: JsonObject? = null
        for (candidate in listOfNotNull(town, "Aylesbury").distinct()) {
            place = get(
                "https://geocoding-api.open-meteo.com/v1/search?name=" +
                    "${URLEncoder.encode(candidate, "UTF-8")}&count=1&language=en&format=json",
            )?.get("results")?.jsonArray?.firstOrNull()?.jsonObject
            if (place != null) break
        }
        val found = place ?: return null
        val lat = found["latitude"]?.jsonPrimitive?.content ?: return null
        val lon = found["longitude"]?.jsonPrimitive?.content ?: return null
        val daily = get(
            "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                "&daily=precipitation_probability_max,precipitation_sum&timezone=auto&forecast_days=8",
        )?.get("daily")?.jsonObject ?: return null
        val dates = daily["time"]?.jsonArray ?: return null
        val chances = daily["precipitation_probability_max"]?.jsonArray
        val amounts = daily["precipitation_sum"]?.jsonArray
        return dates.mapIndexedNotNull { i, d ->
            val date = runCatching { LocalDate.parse(d.jsonPrimitive.content) }.getOrNull() ?: return@mapIndexedNotNull null
            val chance = chances?.getOrNull(i)?.jsonPrimitive?.content?.toDoubleOrNull()?.toInt() ?: 0
            val mm = amounts?.getOrNull(i)?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
            date to DayRain(chance, mm)
        }.toMap()
    }

    private fun get(url: String): JsonObject? {
        val request = Request.Builder().url(url).header("User-Agent", "FormAndFoliage/2.0 (garden CRM)").build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            runCatching { json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject }.getOrNull()
        }
    }

    /** Screenshot tests show a forecast without the network. */
    internal fun showForTest(days: Map<LocalDate, DayRain>) {
        _days.value = days
    }

    internal fun encode(days: Map<LocalDate, DayRain>): String =
        days.entries.joinToString(";") { (d, r) -> "$d,${r.chancePercent},${r.millimetres}" }

    internal fun decode(text: String?): Map<LocalDate, DayRain> =
        text.orEmpty().split(';').mapNotNull { row ->
            val parts = row.split(',')
            if (parts.size != 3) return@mapNotNull null
            val date = runCatching { LocalDate.parse(parts[0]) }.getOrNull() ?: return@mapNotNull null
            date to DayRain(parts[1].toIntOrNull() ?: 0, parts[2].toDoubleOrNull() ?: 0.0)
        }.toMap()
}
