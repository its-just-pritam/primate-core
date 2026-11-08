package com.platito.garmin

import com.platito.common.badRequest
import com.platito.common.parseDate
import com.platito.common.parseZone
import com.platito.user.UserRecord
import com.platito.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.security.MessageDigest

data class ConnectRequest(val apiKey: String? = null)
data class DateRequest(val date: String? = null, val timeZone: String? = null)

@RestController
@RequestMapping("/api/garmin")
class GarminController(
    private val sync: GarminSyncService,
    private val users: UserRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @GetMapping("/integration")
    fun status(user: UserRecord): ResponseEntity<Map<String, Any?>> {
        val integration = sync.integration(user)
        val masked = integration?.let { mask(sync.apiKey(user)) }.orEmpty()
        return noStore(
            mapOf(
                "integration" to if (integration != null) INTEGRATION_NAME else "",
                "connected" to (integration != null),
                "isLive" to true,
                "apiKeyMasked" to masked,
                "cacheScope" to cacheScope(user),
                "lastSyncAt" to integration?.lastSyncFinishedAt?.toString(),
                "lastSyncStatus" to integration?.lastSyncStatus,
                "lastSyncError" to integration?.lastSyncError,
            ),
        )
    }

    @PostMapping("/integration")
    fun connect(user: UserRecord, @RequestBody body: ConnectRequest): ResponseEntity<Map<String, Any?>> {
        val apiKey = body.apiKey.orEmpty().trim()
            .replace(Regex("^Authorization\\s*:\\s*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("^ApiKey\\s+", RegexOption.IGNORE_CASE), "")
            .trim()
        if (apiKey.isEmpty()) throw badRequest("Paste your Garmin Personal API key.")
        sync.connect(user, apiKey)
        return noStore(mapOf("success" to true, "connected" to true, "cacheScope" to cacheScope(user)))
    }

    @DeleteMapping("/integration")
    fun disconnect(user: UserRecord): ResponseEntity<Map<String, Any?>> {
        sync.disconnect(user)
        return noStore(mapOf("success" to true, "connected" to false))
    }

    /** Syncs one local day's activities now; the browser calls this when the app opens. */
    @PostMapping("/activities")
    fun syncActivities(user: UserRecord, @RequestBody body: DateRequest): ResponseEntity<Map<String, Any?>> {
        val date = parseDate(body.date) ?: throw badRequest("Choose a valid date.")
        if (body.timeZone.isNullOrBlank()) throw badRequest("A time zone is required.")
        val zone = parseZone(body.timeZone) ?: throw badRequest("Choose a valid time zone.")
        // The scheduled job resolves "today" in this zone.
        users.updateTimeZone(user.id, zone.id)
        val result = sync.syncActivities(user, date, zone)
        log.info("Garmin activities synced for user {} ({}): imported {}, removed {}", user.id, date, result.imported, result.deleted)
        return noStore(mapOf("success" to true, "date" to date.toString(), "imported" to result.imported, "deleted" to result.deleted))
    }

    /** One day's summary and the user's device name. */
    @PostMapping("/test")
    fun dailySummary(user: UserRecord, @RequestBody body: DateRequest): ResponseEntity<Map<String, Any?>> {
        val date = parseDate(body.date) ?: throw badRequest("Choose a valid date.")
        sync.apiKey(user)
        val data = sync.dailySummaries(user, date, date)
        val deviceName = sync.integration(user)?.deviceName
        return noStore(mapOf("success" to true, "date" to date.toString(), "data" to data, "deviceName" to deviceName))
    }

    @PostMapping("/weekly")
    fun weekly(user: UserRecord, @RequestBody body: DateRequest): ResponseEntity<Map<String, Any?>> {
        val end = parseDate(body.date) ?: throw badRequest("Choose a valid date.")
        val start = end.minusDays(6)
        val data = sync.dailySummaries(user, start, end)
        return noStore(mapOf("success" to true, "startDate" to start.toString(), "endDate" to end.toString(), "data" to data))
    }

    @PostMapping("/sleep")
    fun sleep(user: UserRecord, @RequestBody body: DateRequest): ResponseEntity<Map<String, Any?>> {
        val end = parseDate(body.date) ?: throw badRequest("Choose a valid date.")
        val start = end.minusDays(6)
        val data = sync.sleepSummaries(user, start, end)
        return noStore(mapOf("success" to true, "startDate" to start.toString(), "endDate" to end.toString(), "data" to data))
    }

    private fun noStore(body: Map<String, Any?>) = ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body)

    private fun mask(apiKey: String) =
        if (apiKey.length <= 8) "••••${apiKey.takeLast(4)}" else "${apiKey.take(4)}••••••${apiKey.takeLast(4)}"

    /** Lets the browser partition its in-memory Garmin caches per account. */
    @OptIn(ExperimentalStdlibApi::class)
    private fun cacheScope(user: UserRecord) =
        MessageDigest.getInstance("SHA-256").digest(user.email.lowercase().toByteArray(Charsets.UTF_8)).toHexString().take(16)

    private companion object {
        const val INTEGRATION_NAME = "Garmin Connect"
    }
}
