package uk.co.jwgardenservices.crm.data.website

import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The free website scan: fetch a small business's public pages and pull the
 * facts Secretary needs out of them, without an API key or a language model.
 *
 * This is deliberately conservative. It keeps short lines that look like
 * services, areas, credentials or contact details, drops navigation and
 * cookie boilerplate, and never invents a section from nothing. A missing
 * section is better than a wrong one.
 */
object WebsiteProfileReader {

    private const val MAX_PAGES = 5
    private const val MAX_PAGE_CHARS = 220_000
    private const val MAX_PROFILE_CHARS = 2_400
    private const val MAX_LINE = 220

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    data class Result(
        val profile: String,
        val sourceUrl: String,
        val pages: List<String>,
    )

    suspend fun read(rawUrl: String): Result = withContext(Dispatchers.IO) {
        val start = normalise(rawUrl)
        val home = fetch(start)
            ?: throw IllegalStateException(
                "That website could not be read. Check the address and the signal, then try again.",
            )
        val base = home.first
        val pages = mutableListOf<Pair<HttpUrl, String>>()
        pages += base to home.second

        val candidates = internalLinks(home.second, base)
            .map { it to pageScore(it) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .map { it.first }
            .take(MAX_PAGES - 1)

        for (url in candidates) {
            val page = fetch(url) ?: continue
            pages += page
        }

        val texts = pages.map { (url, html) -> url.toString() to stripHtml(html) }
        val profile = buildProfile(texts)
            ?: throw IllegalStateException(
                "I could not find enough readable text on that website. Try again or edit " +
                    "the profile by hand.",
            )
        Result(
            profile = profile,
            sourceUrl = base.toString().trimEnd('/'),
            pages = texts.map { it.first },
        )
    }

    private fun normalise(raw: String): HttpUrl {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) throw IllegalArgumentException("Enter the website address first")
        val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }
        val url = withScheme.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("That does not look like a website address")
        if (url.scheme != "https") throw IllegalArgumentException("The website must use https")
        val host = url.host.lowercase(Locale.UK)
        if (
            host == "localhost" ||
            host.endsWith(".local") ||
            host.endsWith(".internal") ||
            host.startsWith("127.") ||
            host.startsWith("10.") ||
            host.startsWith("192.168.") ||
            host.matches(Regex("^172\\.(1[6-9]|2\\d|3[01])\\..*"))
        ) {
            throw IllegalArgumentException("That is not a public website address")
        }
        return url
    }

    private fun fetch(url: HttpUrl): Pair<HttpUrl, String>? = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "FormAndFoliageBusinessProfile/1.0 (on-phone scan)")
            .header("Accept", "text/html,application/xhtml+xml")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val finalUrl = response.request.url
            val type = response.header("Content-Type").orEmpty()
            if (!type.contains("text/html", ignoreCase = true)) return null
            val body = response.body ?: return null
            if (body.contentLength() > MAX_PAGE_CHARS) return null
            val text = body.string().take(MAX_PAGE_CHARS)
            finalUrl to text
        }
    }.getOrNull()

    private fun internalLinks(html: String, base: HttpUrl): List<HttpUrl> {
        val found = linkedMapOf<String, HttpUrl>()
        val pattern = Regex("<a\\b[^>]*href=[\"']([^\"'#]+)[\"'][^>]*>", RegexOption.IGNORE_CASE)
        for (match in pattern.findAll(html)) {
            val href = match.groupValues[1].trim()
            if (href.isBlank() || href.startsWith("mailto:", true) ||
                href.startsWith("tel:", true) || href.startsWith("javascript:", true)
            ) {
                continue
            }
            val url = base.resolve(href) ?: continue
            if (url.host != base.host) continue
            if (url.encodedPath == base.encodedPath && url.query == null) continue
            if (Regex("\\.(pdf|jpe?g|png|webp|gif|svg|zip|mp4|mp3|css|js)$", RegexOption.IGNORE_CASE)
                    .containsMatchIn(url.encodedPath)
            ) {
                continue
            }
            if (Regex("privacy|cookie|terms|login|cart|checkout|basket|account", RegexOption.IGNORE_CASE)
                    .containsMatchIn(url.encodedPath)
            ) {
                continue
            }
            found[url.toString()] = url
        }
        return found.values.toList()
    }

    private fun pageScore(url: HttpUrl): Int {
        val path = url.encodedPath.lowercase(Locale.UK)
        if (path == "/" || path.isBlank()) return 100
        val checks = listOf(
            Regex("about") to 90,
            Regex("service|what-we-do") to 85,
            Regex("commercial|grounds") to 80,
            Regex("area|cover|location") to 75,
            Regex("contact") to 70,
            Regex("our-work|portfolio|gallery|project") to 60,
            Regex("landscap|garden|maintenance") to 50,
        )
        return checks.maxOfOrNull { (pattern, score) -> if (pattern.containsMatchIn(path)) score else 0 }
            ?: 0
    }

    private fun stripHtml(html: String): String = decodeEntities(
        html
            .replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<noscript[\\s\\S]*?</noscript>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<svg[\\s\\S]*?</svg>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<!--[\\s\\S]*?-->"), " ")
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</(p|div|h[1-6]|li|section|header|footer)>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), " "),
    )
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex("\\n\\s*\\n\\s*\\n+"), "\n\n")
        .trim()

    private fun decodeEntities(value: String): String = value
        .replace("&nbsp;", " ", ignoreCase = true)
        .replace("&amp;", "&", ignoreCase = true)
        .replace("&#39;", "'")
        .replace("&apos;", "'", ignoreCase = true)
        .replace("&rsquo;", "'", ignoreCase = true)
        .replace("&quot;", "\"", ignoreCase = true)
        .replace("&ldquo;", "\"", ignoreCase = true)
        .replace("&rdquo;", "\"", ignoreCase = true)
        .replace("&pound;", "£", ignoreCase = true)
        .replace(Regex("&#(\\d+);")) { match ->
            match.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: " "
        }

    /**
     * Pure text → profile extraction, so the shape can be tested without a
     * network. [texts] is page URL → stripped page text.
     */
    internal fun buildProfile(texts: List<Pair<String, String>>): String? {
        if (texts.isEmpty()) return null
        val all = texts.joinToString("\n") { it.second }
        val lines = all.lines()
            .map { it.trim() }
            .filter { it.length in 18..MAX_LINE }
            .filterNot { line ->
                Regex(
                    "cookie|privacy|all rights|©|skip to|menu|sign in|log in|" +
                        "newsletter|follow us|instagram|facebook|twitter",
                    RegexOption.IGNORE_CASE,
                ).containsMatchIn(line)
            }
            .distinct()
        if (lines.isEmpty()) return null

        val business = lines.firstOrNull { line ->
            line.length in 40..MAX_LINE && Regex(
                "garden|landscap|maintenance|since \\d{4}|based in",
                RegexOption.IGNORE_CASE,
            ).containsMatchIn(line)
        } ?: lines.firstOrNull()

        val services = pick(
            lines = lines,
            keywords = listOf(
                "garden maintenance", "maintenance", "landscaping", "patio", "path",
                "fencing", "decking", "turf", "planting", "garden design", "hedge",
                "lawn", "weeding", "commercial", "grounds", "clearance",
            ),
            limit = 5,
        )
        val areas = pick(
            lines = lines,
            keywords = listOf(
                "cover", "covering", "areas we cover", "serving", "based in", "near",
                "postcode", "hp1", "hp2", "buckinghamshire", "bedfordshire",
                "hertfordshire",
            ),
            limit = 4,
        )
        val credentials = pick(
            lines = lines,
            keywords = listOf(
                "qualified", "insured", "insurance", "accredited", "gardeners guild",
                "city & guilds", "certified", "dbs", "public liability",
            ),
            limit = 3,
        )
        val contact = lines.filter { line ->
            Regex("(\\+44|0)\\d[\\d\\s]{8,}|[\\w.+-]+@[\\w.-]+\\.[a-z]{2,}", RegexOption.IGNORE_CASE)
                .containsMatchIn(line) ||
                Regex("opening hours|open\\s+(mon|tue|wed|thu|fri|sat|sun)|closed", RegexOption.IGNORE_CASE)
                    .containsMatchIn(line)
        }.take(3)

        val builder = StringBuilder()
        if (!business.isNullOrBlank()) builder.appendLine("BUSINESS: ${business.trim()}.")
        if (services.isNotEmpty()) builder.appendLine("SERVICES: ${services.joinToString("; ")}.")
        if (areas.isNotEmpty()) builder.appendLine("AREAS: ${areas.joinToString("; ")}.")
        if (credentials.isNotEmpty()) {
            builder.appendLine("CREDENTIALS: ${credentials.joinToString("; ")}.")
        }
        if (contact.isNotEmpty()) builder.appendLine("HOURS & CONTACT: ${contact.joinToString("; ")}.")
        if (builder.length < 80) return null
        return builder.toString().trim().take(MAX_PROFILE_CHARS)
    }

    private fun pick(lines: List<String>, keywords: List<String>, limit: Int): List<String> =
        lines
            .map { line -> line to keywords.count { line.contains(it, ignoreCase = true) } }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .map { it.first }
            .distinct()
            .take(limit)
}
