package com.platito.garmin

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.platito.config.AppProperties
import org.springframework.stereotype.Component
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.time.LocalDate

/** A Garmin failure whose message is safe to show; always starts with "Garmin ". */
class GarminException(override val message: String) : RuntimeException(message)

/** The subset of the Garmin Connect personal API the app uses. */
interface GarminApi {
    fun exchangeToken(apiKey: String): String
    fun dailySummary(accessToken: String, date: LocalDate): JsonNode
    fun sleepSummary(accessToken: String, date: LocalDate): JsonNode
    fun activitiesForDate(accessToken: String, date: LocalDate): List<JsonNode>
    fun deviceName(accessToken: String): String?
}

@Component
class HttpGarminApi(
    properties: AppProperties,
    private val objectMapper: ObjectMapper,
) : GarminApi {
    private val baseUrl = properties.garmin.baseUrl.trimEnd('/')
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    override fun exchangeToken(apiKey: String): String {
        val request = HttpRequest.newBuilder(URI.create("$baseUrl$TOKEN_PATH"))
            .timeout(REQUEST_TIMEOUT)
            .header("Authorization", "ApiKey $apiKey")
            .header("Accept", "*/*")
            .POST(HttpRequest.BodyPublishers.noBody())
            .build()
        val response = send(request, "Garmin token exchange")
        if (response.statusCode() !in 200..299) {
            val upstream = response.body().replace(apiKey, "[redacted]").take(400)
            throw GarminException("Garmin token exchange failed (${response.statusCode()})${if (upstream.isNotBlank()) ": $upstream" else ""}")
        }
        return response.body().trim().ifEmpty { throw GarminException("Garmin returned an empty access token.") }
    }

    override fun dailySummary(accessToken: String, date: LocalDate): JsonNode =
        getJson(accessToken, "/usersummary-service/v2/dailysummaries", dateRange(date), "Garmin daily summary request")

    override fun sleepSummary(accessToken: String, date: LocalDate): JsonNode =
        getJson(accessToken, "/sleep-service/sleep/dailySleepSummariesByDate", dateRange(date), "Garmin sleep summary request")

    /**
     * Activities whose local start falls on [date]. Garmin lists newest first, so paging stops
     * at the first page that reaches an earlier day.
     */
    override fun activitiesForDate(accessToken: String, date: LocalDate): List<JsonNode> {
        val day = date.toString()
        val matches = mutableListOf<JsonNode>()
        for (page in 0 until MAX_ACTIVITY_PAGES) {
            val payload = getJson(
                accessToken,
                ACTIVITY_SEARCH_PATH,
                mapOf("start" to (page * ACTIVITY_PAGE_SIZE).toString(), "limit" to ACTIVITY_PAGE_SIZE.toString()),
                "Garmin activities request",
            )
            val candidates = activityList(payload) ?: throw GarminException("Garmin returned an unrecognized activity-list response.")
            var reachedOlderActivity = false
            for (activity in candidates) {
                if (!activity.isObject) continue
                val start = listOf("startTimeLocal", "startTimeGMT", "startTime")
                    .firstNotNullOfOrNull { activity.path(it).textValue()?.takeIf(String::isNotEmpty) }.orEmpty()
                val activityDate = start.take(10)
                if (activityDate == day) matches.add(activity)
                else if (activityDate.isNotEmpty() && activityDate < day) reachedOlderActivity = true
            }
            if (reachedOlderActivity || candidates.size < ACTIVITY_PAGE_SIZE) return matches
        }
        throw GarminException("Garmin activity list exceeded the sync limit; skipped reconciliation to avoid removing valid workouts.")
    }

    override fun deviceName(accessToken: String): String? = try {
        val recent = getJson(accessToken, ACTIVITY_SEARCH_PATH, mapOf("start" to "0", "limit" to "5"), "Garmin activities request")
        findText(recent, "deviceName") ?: run {
            val activityIds = mutableSetOf<String>()
            collectActivityIds(recent, activityIds)
            activityIds.take(5).firstNotNullOfOrNull { id ->
                runCatching {
                    val detail = getJson(accessToken, "/activity-service/activity/${encode(id)}", emptyMap(), "Garmin activity request")
                    findText(detail, "deviceName")
                }.getOrNull()
            }
        }
    } catch (e: GarminException) {
        null
    }

    private fun getJson(accessToken: String, path: String, params: Map<String, String>, label: String): JsonNode {
        val query = params.entries.joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" }
        val request = HttpRequest.newBuilder(URI.create(baseUrl + path + if (query.isEmpty()) "" else "?$query"))
            .timeout(REQUEST_TIMEOUT)
            .header("Authorization", "Bearer $accessToken")
            .header("Accept", "application/json")
            .GET()
            .build()
        val response = send(request, label)
        if (response.statusCode() !in 200..299) throw GarminException("$label failed (${response.statusCode()}).")
        return try {
            objectMapper.readTree(response.body().ifBlank { "null" })
        } catch (e: IOException) {
            throw GarminException("$label returned invalid JSON.")
        }
    }

    private fun send(request: HttpRequest, label: String): HttpResponse<String> = try {
        http.send(request, HttpResponse.BodyHandlers.ofString())
    } catch (e: HttpTimeoutException) {
        throw GarminException("$label timed out.")
    } catch (e: IOException) {
        throw GarminException("$label could not reach Garmin.")
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw GarminException("$label was interrupted.")
    }

    private fun activityList(payload: JsonNode): List<JsonNode>? = when {
        payload.isArray -> payload.toList()
        else -> listOf("activityList", "data", "activities").firstNotNullOfOrNull { key ->
            payload.path(key).takeIf { it.isArray }?.toList()
        }
    }

    private fun findText(node: JsonNode, field: String): String? {
        if (node.isObject) node.path(field).textValue()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        return node.firstNotNullOfOrNull { findText(it, field) }
    }

    private fun collectActivityIds(node: JsonNode, into: MutableSet<String>) {
        if (node.isObject) node.get("activityId")?.takeIf { it.isNumber || it.isTextual }?.let { into += it.asText() }
        node.forEach { collectActivityIds(it, into) }
    }

    private fun dateRange(date: LocalDate) = mapOf("startDate" to date.toString(), "endDate" to date.toString())
    private fun encode(value: String) = URLEncoder.encode(value, Charsets.UTF_8)

    private companion object {
        const val TOKEN_PATH = "/personalapitoken-service/personal-api/personal-api-key/token"
        const val ACTIVITY_SEARCH_PATH = "/activitylist-service/activities/search/activities"
        const val ACTIVITY_PAGE_SIZE = 100
        const val MAX_ACTIVITY_PAGES = 10
        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(20)
    }
}
