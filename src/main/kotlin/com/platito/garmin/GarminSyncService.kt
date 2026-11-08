package com.platito.garmin

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.platito.common.conflict
import com.platito.common.parseZone
import com.platito.config.AppProperties
import com.platito.user.UserRecord
import com.platito.user.UserRepository
import com.platito.workout.GarminWorkout
import com.platito.workout.WorkoutRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

data class ActivitySyncResult(val imported: Int, val deleted: Int)

/**
 * Pulls Garmin data into Postgres. Activities become GARMIN workouts; daily summaries and sleep
 * are stored per calendar day and served from the database, refreshed when recent days go stale.
 */
@Service
class GarminSyncService(
    private val repository: GarminRepository,
    private val users: UserRepository,
    private val workouts: WorkoutRepository,
    private val api: GarminApi,
    private val tokens: GarminTokenProvider,
    private val cipher: ApiKeyCipher,
    private val properties: AppProperties,
    private val objectMapper: ObjectMapper,
    private val transactions: TransactionTemplate,
    private val backfillExecutor: ExecutorService,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val userLocks = ConcurrentHashMap<Long, ReentrantLock>()

    fun integration(user: UserRecord): GarminIntegrationRecord? = repository.findIntegration(user.id)

    fun apiKey(user: UserRecord): String =
        repository.findIntegration(user.id)?.let { cipher.decrypt(it.apiKeyCiphertext) } ?: throw conflict("Connect Garmin first.")

    /** Validates the key with Garmin, stores it encrypted, and backfills recent days in the background. */
    fun connect(user: UserRecord, apiKey: String) {
        val token = api.exchangeToken(apiKey)
        repository.saveIntegration(user.id, cipher.encrypt(apiKey))
        tokens.remember(user.id, apiKey, token)
        backfillExecutor.execute { syncUser(user.id, properties.garmin.backfillDays) }
    }

    fun disconnect(user: UserRecord) {
        repository.deleteIntegration(user.id)
        tokens.evict(user.id)
    }

    /**
     * Makes the user's GARMIN workouts on [date] (in [zone]) match Garmin: new activities are
     * added, changed ones updated, and ones no longer in Garmin removed. Direct workouts are untouched.
     */
    fun syncActivities(user: UserRecord, date: LocalDate, zone: ZoneId): ActivitySyncResult {
        val apiKey = apiKey(user)
        return userLocks.computeIfAbsent(user.id) { ReentrantLock() }.withLock {
            val activities = api.activitiesForDate(tokens.token(user.id, apiKey), date)
            val desired = activities.mapNotNull { toWorkout(it, zone) }.associateBy { it.externalId }.values
            val desiredIds = desired.map { it.externalId }.toSet()
            transactions.execute {
                val existing = workouts.findGarminExternalIds(user.id, desiredIds)
                desired.forEach { workouts.upsertGarmin(user.id, it) }
                val stale = workouts.findGarminOnLocalDate(user.id, date, zone).filter { it.externalId !in desiredIds }
                workouts.deleteByIds(user.id, stale.map { it.id })
                ActivitySyncResult(imported = desiredIds.count { it !in existing }, deleted = stale.size)
            }!!
        }
    }

    /** Daily summary records for [from]..[to], flattened into one list ordered by day. */
    fun dailySummaries(user: UserRecord, from: LocalDate, to: LocalDate): List<JsonNode> =
        metrics(user, GarminMetricKind.DAILY_SUMMARY, from, to).flatMap(::summaryRows)

    /** Raw sleep payloads for [from]..[to], one per day. */
    fun sleepSummaries(user: UserRecord, from: LocalDate, to: LocalDate): List<JsonNode> =
        metrics(user, GarminMetricKind.SLEEP, from, to)

    /** Syncs today and yesterday for every connected user. */
    fun syncAllUsers() {
        val userIds = repository.connectedUserIds()
        log.info("Garmin sync starting for {} connected user(s)", userIds.size)
        userIds.forEach { syncUser(it) }
    }

    /** Syncs the last [days] days for one user, recording the outcome. Never throws. */
    fun syncUser(userId: Long, days: Int = 2) {
        val user = users.findById(userId) ?: return
        if (repository.findIntegration(userId) == null) return
        repository.markSyncStarted(userId)
        try {
            val zone = zoneOf(user)
            val today = LocalDate.now(clock.withZone(zone))
            val dates = (days - 1 downTo 0).map { today.minusDays(it.toLong()) }
            dates.forEach { syncActivities(user, it, zone) }
            metrics(user, GarminMetricKind.DAILY_SUMMARY, dates.first(), dates.last())
            metrics(user, GarminMetricKind.SLEEP, dates.first(), dates.last())
            if (repository.findIntegration(userId)?.deviceName == null) {
                api.deviceName(tokens.token(userId, apiKey(user)))?.let { repository.updateDeviceName(userId, it) }
            }
            repository.markSyncFinished(userId, success = true, error = null)
            log.info("Garmin sync completed for user {} ({} day(s))", userId, days)
        } catch (e: Exception) {
            log.warn("Garmin sync failed for user {}: {}", userId, e.message)
            repository.markSyncFinished(userId, success = false, error = e.message?.take(500))
        }
    }

    /**
     * Stored payloads for each day, fetching days that are missing and recent days (today and
     * yesterday, still changing on Garmin's side) older than the freshness window. A failed
     * refresh falls back to the stored payload.
     */
    private fun metrics(user: UserRecord, kind: GarminMetricKind, from: LocalDate, to: LocalDate): List<JsonNode> {
        val stored = repository.findMetrics(user.id, kind, from, to).associateBy { it.date }
        val dates = from.datesUntil(to.plusDays(1)).toList()
        val recentFrom = LocalDate.now(clock.withZone(zoneOf(user))).minusDays(1)
        val staleBefore = clock.instant().minus(properties.garmin.summaryFreshness)
        val toFetch = dates.filter { date ->
            val existing = stored[date]
            existing == null || (!date.isBefore(recentFrom) && existing.fetchedAt.isBefore(staleBefore))
        }

        val fetched = mutableMapOf<LocalDate, JsonNode>()
        if (toFetch.isNotEmpty()) {
            val token = tokens.token(user.id, apiKey(user))
            for (date in toFetch) {
                try {
                    val payload = when (kind) {
                        GarminMetricKind.DAILY_SUMMARY -> api.dailySummary(token, date)
                        GarminMetricKind.SLEEP -> api.sleepSummary(token, date)
                    }
                    repository.upsertMetric(user.id, kind, date, objectMapper.writeValueAsString(payload))
                    fetched[date] = payload
                } catch (e: GarminException) {
                    if (stored[date] == null) throw e
                    log.warn("Garmin {} refresh failed for user {} on {}; serving stored data: {}", kind, user.id, date, e.message)
                }
            }
        }
        return dates.mapNotNull { date -> fetched[date] ?: stored[date]?.let { objectMapper.readTree(it.payload) } }
    }

    private fun zoneOf(user: UserRecord): ZoneId = parseZone(user.timeZone) ?: ZoneOffset.UTC

    private fun summaryRows(payload: JsonNode): List<JsonNode> = when {
        payload.isArray -> payload.toList()
        payload.isObject -> listOf("data", "dailySummaries", "summaries")
            .firstNotNullOfOrNull { key -> payload.path(key).takeIf { it.isArray }?.toList() }
            ?: if (payload.isEmpty) emptyList() else listOf(payload)
        else -> emptyList()
    }

    private fun toWorkout(activity: JsonNode, zone: ZoneId): GarminWorkout? {
        val name = listOf("activityName", "name").firstNotNullOfOrNull { activity.path(it).textValue()?.trim()?.takeIf(String::isNotEmpty) }
            ?: return null
        val calories = listOf("calories", "caloriesBurned", "caloriesBurnt")
            .map { activity.path(it) }.firstOrNull { !it.isMissingNode && !it.isNull }
            ?.let { if (it.isNumber) it.asDouble() else it.asText().toDoubleOrNull() }
            ?.takeIf { it.isFinite() && it >= 0 } ?: return null
        val performedAt = startInstant(activity, zone) ?: return null
        val externalId = activity.get("activityId")?.takeIf { it.isNumber || it.isTextual }?.asText()?.takeIf(String::isNotEmpty)
            ?: fallbackId(name, performedAt, calories)
        return GarminWorkout(externalId, name, calories, performedAt)
    }

    /** startTimeGMT is UTC and startTimeLocal is in the user's zone; both come without an offset. */
    private fun startInstant(activity: JsonNode, zone: ZoneId): Instant? {
        fun parse(field: String, fieldZone: ZoneId): Instant? {
            val text = activity.path(field).textValue()?.trim()?.takeIf(String::isNotEmpty) ?: return null
            return runCatching { OffsetDateTime.parse(text).toInstant() }.getOrNull()
                ?: runCatching { LocalDateTime.parse(text.replace(' ', 'T'), DateTimeFormatter.ISO_LOCAL_DATE_TIME).atZone(fieldZone).toInstant() }.getOrNull()
        }
        return parse("startTimeGMT", ZoneOffset.UTC) ?: parse("startTime", ZoneOffset.UTC) ?: parse("startTimeLocal", zone)
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun fallbackId(name: String, performedAt: Instant, calories: Double) =
        "sig:" + MessageDigest.getInstance("SHA-256")
            .digest("$name\u0000$performedAt\u0000$calories".toByteArray(Charsets.UTF_8)).toHexString().take(32)
}
