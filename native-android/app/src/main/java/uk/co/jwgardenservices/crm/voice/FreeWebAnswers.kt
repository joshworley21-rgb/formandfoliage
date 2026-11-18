package uk.co.jwgardenservices.crm.voice

import android.content.Context
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
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

/**
 * Free web answers, no API key and no account: Open-Meteo for weather and
 * Wikipedia for facts. DuckDuckGo's instant answers fill in simple
 * definitions. Anything they cannot answer falls through to the optional
 * OpenAI router, so this never makes the paid path worse.
 */
class FreeWebAnswers(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun answer(question: String, intent: SpokenIntent): String? =
        withContext(Dispatchers.IO) {
            val result = runCatching {
                when (intent) {
                    SpokenIntent.Weather -> weather(question)
                    SpokenIntent.Lookup -> lookup(question)
                    else -> null
                }
            }.onFailure {
                CrashLog.note(context, TAG, "free web failed: ${it.message}")
            }.getOrNull()
            CrashLog.note(
                context,
                TAG,
                "free web ${intent.wire}: ${if (result == null) "no answer" else result.take(80)}",
            )
            result
        }

    // --- weather ---------------------------------------------------------

    private suspend fun weather(question: String): String? {
        // Try the named place, then the business address, then a known town.
        // A postcode-only address otherwise makes geocoding return nothing.
        val candidates = listOfNotNull(placeFrom(question), defaultPlace(), "Aylesbury")
            .distinct()
        var hit: JsonObject? = null
        var fallback = "Aylesbury"
        for (candidate in candidates) {
            val geo = get(
                "https://geocoding-api.open-meteo.com/v1/search?name=" +
                    "${URLEncoder.encode(candidate, "UTF-8")}&count=1&language=en&format=json",
            )
            val found = geo?.jsonObject?.get("results")?.jsonArray?.firstOrNull()?.jsonObject
            if (found != null) {
                hit = found
                fallback = candidate
                break
            }
            CrashLog.note(context, TAG, "geocoding found nothing for \"$candidate\"")
        }
        val resolved = hit ?: return null
        val name = resolved["name"]?.jsonPrimitive?.content ?: fallback
        val latitude = resolved["latitude"]?.jsonPrimitive?.content ?: return null
        val longitude = resolved["longitude"]?.jsonPrimitive?.content ?: return null

        val forecast = get(
            "https://api.open-meteo.com/v1/forecast?latitude=$latitude&longitude=$longitude" +
                "&current=temperature_2m,weather_code,wind_speed_10m" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min," +
                "precipitation_probability_max&timezone=auto&forecast_days=2",
        ) ?: return null
        val current = forecast.jsonObject["current"]?.jsonObject
        val daily = forecast.jsonObject["daily"]?.jsonObject
        if (current == null || daily == null) {
            CrashLog.note(context, TAG, "weather payload missing current/daily")
            return null
        }
        val temp = current["temperature_2m"]?.jsonPrimitive?.content?.round() ?: return null
        val code = current["weather_code"]?.jsonPrimitive?.content?.toIntOrNull() ?: -1
        val wind = current["wind_speed_10m"]?.jsonPrimitive?.content?.toDoubleOrNull()
        val high = daily["temperature_2m_max"]?.jsonArray?.firstOrNull()
            ?.jsonPrimitive?.content?.round()
        val low = daily["temperature_2m_min"]?.jsonArray?.firstOrNull()
            ?.jsonPrimitive?.content?.round()
        val rain = daily["precipitation_probability_max"]?.jsonArray?.firstOrNull()
            ?.jsonPrimitive?.content?.toIntOrNull()

        val condition = conditionFor(code)
        val opening = "It's $temp degrees and $condition in $name."
        val detail = buildString {
            if (high != null && low != null) append(" Today's top is $high, down to $low.")
            if (rain != null && rain >= 35) append(" About a $rain per cent chance of rain.")
            if (wind != null && wind >= 25) {
                append(" Wind's up around ${"%.0f".format(wind)} kilometres an hour.")
            }
        }
        return opening + detail
    }

    private fun placeFrom(question: String): String? {
        val match = Regex(
            "\\b(?:in|at|for)\\s+([a-z][a-z\\s'’-]{2,40}?)(?:\\s+today|\\s+tomorrow|\\s+this|\\s*[?.!]|$)",
            RegexOption.IGNORE_CASE,
        ).find(question) ?: return null
        val place = match.groupValues[1].trim()
        return place.takeIf {
            it.isNotBlank() &&
                it.lowercase() !in setOf("here", "the morning", "the afternoon", "today", "tomorrow")
        }
    }

    private suspend fun defaultPlace(): String? = runCatching {
        val address = ServiceLocator.crmRepository.observeSettings().first()?.address
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return@runCatching null
        // "12 High Street, Aylesbury, HP20" -> Aylesbury: the town is the
        // useful geocoding term, not the house number or postcode.
        address.split(',')
            .map { it.trim() }
            .firstOrNull { part ->
                part.length > 2 && part.any { it.isLetter() } && part.none { it.isDigit() }
            }
            ?: address
    }.getOrNull()

    // --- facts -----------------------------------------------------------

    private suspend fun lookup(question: String): String? {
        val topic = topicFrom(question)
        if (topic.isBlank()) return null

        // Wikipedia first: a stable, citable summary rather than a search page.
        val search = get(
            "https://en.wikipedia.org/w/api.php?action=query&list=search&format=json" +
                "&srlimit=1&srsearch=${URLEncoder.encode(topic, "UTF-8")}",
        )
        val title = search?.jsonObject?.get("query")?.jsonObject
            ?.get("search")?.jsonArray?.firstOrNull()?.jsonObject
            ?.get("title")?.jsonPrimitive?.content
        if (title != null) {
            val summary = get(
                "https://en.wikipedia.org/api/rest_v1/page/summary/" +
                    URLEncoder.encode(title.replace(' ', '_'), "UTF-8"),
            )
            val extract = summary?.jsonObject?.get("extract")?.jsonPrimitive?.content
            if (!extract.isNullOrBlank()) {
                return spokenSummary(extract)
            }
        }

        // Instant answers cover sums, conversions and short definitions.
        val instant = get(
            "https://api.duckduckgo.com/?format=json&no_html=1&skip_disambig=1&q=" +
                URLEncoder.encode(topic, "UTF-8"),
        )
        val abstract = instant?.jsonObject?.get("AbstractText")?.jsonPrimitive?.content
        if (!abstract.isNullOrBlank()) return spokenSummary(abstract)
        return null
    }

    private fun topicFrom(question: String): String {
        val trimmed = question.trim().trimEnd('.', '!', '?')
        val prefixes = listOf(
            "look up ", "search for ", "search the web for ", "search ", "google ",
            "tell me about ", "find out about ", "what is ", "what's ",
            "who is ", "who's ", "where is ",
        )
        val lower = trimmed.lowercase(Locale.UK)
        prefixes.forEach { prefix ->
            if (lower.startsWith(prefix)) return trimmed.substring(prefix.length)
        }
        return trimmed
    }

    /** Two spoken sentences, not a Wikipedia paragraph. */
    private fun spokenSummary(text: String): String {
        val sentences = text.replace("\n", " ")
            .split(Regex("(?<=[.!?])\\s+"))
            .filter { it.isNotBlank() }
        val picked = sentences.take(2).joinToString(" ")
        return if (picked.length > 320) picked.take(317).trimEnd() + "…" else picked
    }

    // --- plumbing --------------------------------------------------------

    private fun get(url: String): JsonObject? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "FormAndFoliage/2.0 (garden CRM assistant)")
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                CrashLog.note(context, TAG, "HTTP ${response.code} for ${url.take(60)}")
                return null
            }
            val body = response.body?.string() ?: return null
            runCatching { json.parseToJsonElement(body).jsonObject }
                .onFailure { CrashLog.note(context, TAG, "bad JSON: ${it.message}") }
                .getOrNull()
        }
    }

    private fun String.round(): String = toDoubleOrNull()
        ?.let { if (it % 1.0 == 0.0) it.toInt().toString() else "%.0f".format(it) }
        ?: this

    private fun conditionFor(code: Int): String = when (code) {
        0 -> "clear"
        1, 2 -> "mostly sunny"
        3 -> "cloudy"
        45, 48 -> "foggy"
        51, 53, 55, 56, 57 -> "drizzling"
        61, 63, 65, 66, 67 -> "raining"
        71, 73, 75, 77 -> "snowing"
        80, 81, 82 -> "showery"
        85, 86 -> "snow showers"
        95, 96, 99 -> "thundery"
        else -> "grey"
    }

    private companion object {
        const val TAG = "FreeWeb"
    }
}
