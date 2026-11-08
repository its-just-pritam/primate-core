package com.platito.admin

import com.fasterxml.jackson.databind.ObjectMapper
import com.platito.common.badRequest
import com.platito.common.conflict
import com.platito.common.notFound
import com.platito.garmin.GarminRepository
import org.springframework.core.io.ClassPathResource
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

data class RunJobRequest(
    val date: String? = null,
    val timestamp: String? = null,
)

/** The admin page and the JSON it reads and posts to. Protected by [AdminAuthInterceptor]. */
@RestController
@RequestMapping("/admin")
class AdminController(
    private val repository: AdminRepository,
    private val jobs: AdminJobs,
    private val garmin: GarminRepository,
    private val objectMapper: ObjectMapper,
) {
    private val page by lazy { ClassPathResource("admin/index.html").inputStream.use { String(it.readAllBytes(), Charsets.UTF_8) } }

    @GetMapping("", "/", produces = [MediaType.TEXT_HTML_VALUE])
    fun page(): String = page

    @GetMapping("/api/overview")
    fun overview(): Map<String, Any?> = mapOf(
        "jobs" to jobs.list(),
        "sheetsRuns" to repository.recentSheetsRuns(RUN_LIMIT).map { run ->
            mapOf(
                "id" to run.id,
                "startedAt" to run.startedAt,
                "finishedAt" to run.finishedAt,
                "status" to (run.status ?: "RUNNING"),
                "counts" to run.countsJson?.let(objectMapper::readTree),
                "warnings" to run.warningsJson?.let(objectMapper::readTree),
                "error" to run.error,
            )
        },
        "users" to repository.users(USER_LIMIT),
    )

    @PostMapping("/api/jobs/{id}/run")
    fun runJob(
        @PathVariable id: String,
        @RequestParam(required = false) date: String? = null,
        @RequestParam(required = false) timestamp: String? = null,
        @RequestBody(required = false) body: RunJobRequest? = null,
    ): ResponseEntity<Map<String, String>> {
        if (!jobs.exists(id)) throw notFound("Unknown job.")
        val raw = body?.date ?: body?.timestamp ?: date ?: timestamp
        val parsedDate = parseDateOrTimestamp(raw)
        return when (jobs.start(id, parsedDate)) {
            StartResult.STARTED -> ResponseEntity.status(HttpStatus.ACCEPTED).body(mapOf("status" to "started"))
            StartResult.ALREADY_RUNNING -> throw conflict("This job is already running.")
        }
    }

    @PostMapping("/api/users/{id}/garmin-sync")
    fun syncUserGarmin(
        @PathVariable id: Long,
        @RequestParam(required = false) date: String? = null,
        @RequestParam(required = false) timestamp: String? = null,
        @RequestBody(required = false) body: RunJobRequest? = null,
    ): ResponseEntity<Map<String, String>> {
        if (!repository.userExists(id)) throw notFound("Unknown user.")
        if (garmin.findIntegration(id) == null) throw conflict("This user has not connected Garmin.")
        val raw = body?.date ?: body?.timestamp ?: date ?: timestamp
        val parsedDate = parseDateOrTimestamp(raw)
        jobs.startGarminSyncForUser(id, parsedDate)
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(mapOf("status" to "started"))
    }

    private fun parseDateOrTimestamp(input: String?): LocalDate? {
        if (input.isNullOrBlank()) return null
        val trimmed = input.trim()
        runCatching { return LocalDate.parse(trimmed.take(10)) }.getOrNull()
        trimmed.toLongOrNull()?.let { num ->
            return if (num > 1_000_000_000_000L) {
                Instant.ofEpochMilli(num).atZone(ZoneOffset.UTC).toLocalDate()
            } else {
                Instant.ofEpochSecond(num).atZone(ZoneOffset.UTC).toLocalDate()
            }
        }
        runCatching { return Instant.parse(trimmed).atZone(ZoneOffset.UTC).toLocalDate() }.getOrNull()
        runCatching { return OffsetDateTime.parse(trimmed).toLocalDate() }.getOrNull()
        runCatching { return LocalDateTime.parse(trimmed).toLocalDate() }.getOrNull()
        throw badRequest("Invalid date or timestamp: $input")
    }

    private companion object {
        const val RUN_LIMIT = 20
        const val USER_LIMIT = 1000
    }
}
