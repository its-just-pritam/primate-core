package com.platito.admin

import com.platito.config.AppProperties
import com.platito.garmin.GarminSyncService
import com.platito.sheets.SheetsSyncService
import jakarta.annotation.PreDestroy
import net.javacrumbs.shedlock.core.LockConfiguration
import net.javacrumbs.shedlock.core.LockProvider
import org.slf4j.LoggerFactory
import org.springframework.scheduling.support.CronExpression
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.Executors

enum class StartResult { STARTED, ALREADY_RUNNING }

data class JobInfo(
    val id: String,
    val name: String,
    val description: String,
    val scheduleEnabled: Boolean,
    val cron: String,
    val zone: String,
    val nextRunAt: Instant?,
    val running: Boolean,
    val lockedUntil: Instant?,
)

/**
 * Starts the scheduled jobs by hand from the admin page. A manual run takes the same ShedLock lock
 * as the scheduled run, so the two never overlap on any instance, and runs in the background
 * while the request returns. Manual runs work even when the schedule is turned off.
 */
@Component
class AdminJobs(
    private val lockProvider: LockProvider,
    private val sheetsSync: SheetsSyncService,
    private val garminSync: GarminSyncService,
    private val repository: AdminRepository,
    private val properties: AppProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val executor = Executors.newFixedThreadPool(2)

    private inner class Job(
        val id: String,
        val name: String,
        val description: String,
        val lockName: String,
        val lockAtMostFor: Duration,
        val scheduleEnabled: () -> Boolean,
        val cron: () -> String,
        val zone: () -> ZoneId,
        val task: () -> Unit,
    )

    private val jobs = listOf(
        Job(
            id = SHEETS_SYNC, name = "Google Sheets sync",
            description = "Copies the Google Sheet into Postgres (insert and update only).",
            lockName = "sheetsSync", lockAtMostFor = Duration.ofHours(1),
            scheduleEnabled = { properties.sheets.syncEnabled }, cron = { properties.sheets.syncCron }, zone = { properties.sheets.zone },
            task = { sheetsSync.run() },
        ),
        Job(
            id = GARMIN_SYNC, name = "Garmin sync",
            description = "Syncs today and yesterday from Garmin for every connected user.",
            lockName = "garminSync", lockAtMostFor = Duration.ofMinutes(25),
            scheduleEnabled = { properties.garmin.syncEnabled }, cron = { properties.garmin.syncCron }, zone = { ZoneId.of("UTC") },
            task = { garminSync.syncAllUsers() },
        ),
    ).associateBy { it.id }

    fun list(): List<JobInfo> {
        val locks = repository.heldLocks()
        val now = clock.instant()
        return jobs.values.map { job ->
            val lockedUntil = locks[job.lockName]
            JobInfo(
                id = job.id,
                name = job.name,
                description = job.description,
                scheduleEnabled = job.scheduleEnabled(),
                cron = job.cron(),
                zone = job.zone().id,
                nextRunAt = if (job.scheduleEnabled()) nextRun(job.cron(), job.zone(), now) else null,
                running = lockedUntil != null,
                lockedUntil = lockedUntil,
            )
        }
    }

    fun exists(id: String) = id in jobs

    fun start(id: String): StartResult {
        val job = jobs.getValue(id)
        val lock = lockProvider.lock(LockConfiguration(clock.instant(), job.lockName, job.lockAtMostFor, Duration.ZERO)).orElse(null)
            ?: return StartResult.ALREADY_RUNNING
        executor.execute {
            try {
                log.info("{} started from the admin page", job.name)
                job.task()
            } catch (e: Exception) {
                log.error("{} started from the admin page failed: {}", job.name, e.message, e)
            } finally {
                lock.unlock()
            }
        }
        return StartResult.STARTED
    }

    /** Syncs one user's Garmin data in the background; the outcome lands on garmin_integration. */
    fun startGarminSyncForUser(userId: Long) {
        executor.execute { garminSync.syncUser(userId) }
    }

    @PreDestroy
    fun shutdown() {
        executor.shutdown()
    }

    private fun nextRun(cron: String, zone: ZoneId, now: Instant): Instant? =
        runCatching { CronExpression.parse(cron).next(now.atZone(zone))?.toInstant() }.getOrNull()

    companion object {
        const val SHEETS_SYNC = "sheets-sync"
        const val GARMIN_SYNC = "garmin-sync"
    }
}
