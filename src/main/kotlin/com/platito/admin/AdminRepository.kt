package com.platito.admin

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant
import java.time.ZoneOffset

data class SheetsRunRow(
    val id: Long,
    val startedAt: Instant,
    val finishedAt: Instant?,
    val status: String?,
    val countsJson: String?,
    val warningsJson: String?,
    val error: String?,
)

data class GarminStatus(
    val connectedAt: Instant,
    val deviceName: String?,
    val lastSyncStartedAt: Instant?,
    val lastSyncFinishedAt: Instant?,
    val lastSyncStatus: String?,
    val lastSyncError: String?,
)

data class AdminUserRow(
    val id: Long,
    val email: String,
    val name: String,
    val createdAt: Instant,
    val onboardingStep: Int,
    val timeZone: String,
    val meals: Long,
    val workouts: Long,
    val garmin: GarminStatus?,
)

/** Read-only queries for the admin page. Never selects password hashes or Garmin keys. */
@Repository
class AdminRepository(private val jdbc: JdbcClient) {
    fun recentSheetsRuns(limit: Int): List<SheetsRunRow> =
        jdbc.sql(
            "SELECT id, started_at, finished_at, status, counts::text AS counts, warnings::text AS warnings, error FROM sheets_sync_run ORDER BY id DESC LIMIT :limit",
        ).param("limit", limit).query { rs, _ ->
            SheetsRunRow(
                id = rs.getLong("id"),
                startedAt = rs.getTimestamp("started_at").toInstant(),
                finishedAt = rs.instant("finished_at"),
                status = rs.getString("status"),
                countsJson = rs.getString("counts"),
                warningsJson = rs.getString("warnings"),
                error = rs.getString("error"),
            )
        }.list()

    fun users(limit: Int): List<AdminUserRow> =
        jdbc.sql(
            """
            SELECT u.id, u.email, u.name, u.created_at, u.onboarding_step, u.time_zone,
                   (SELECT count(*) FROM meal_log m WHERE m.user_id = u.id) AS meals,
                   (SELECT count(*) FROM workout w WHERE w.user_id = u.id) AS workouts,
                   g.connected_at, g.device_name, g.last_sync_started_at, g.last_sync_finished_at, g.last_sync_status, g.last_sync_error
            FROM app_user u
            LEFT JOIN garmin_integration g ON g.user_id = u.id
            ORDER BY u.created_at DESC, u.id DESC
            LIMIT :limit
            """.trimIndent(),
        ).param("limit", limit).query { rs, _ ->
            AdminUserRow(
                id = rs.getLong("id"),
                email = rs.getString("email"),
                name = rs.getString("name"),
                createdAt = rs.getTimestamp("created_at").toInstant(),
                onboardingStep = rs.getInt("onboarding_step"),
                timeZone = rs.getString("time_zone"),
                meals = rs.getLong("meals"),
                workouts = rs.getLong("workouts"),
                garmin = rs.instant("connected_at")?.let { connectedAt ->
                    GarminStatus(
                        connectedAt = connectedAt,
                        deviceName = rs.getString("device_name"),
                        lastSyncStartedAt = rs.instant("last_sync_started_at"),
                        lastSyncFinishedAt = rs.instant("last_sync_finished_at"),
                        lastSyncStatus = rs.getString("last_sync_status"),
                        lastSyncError = rs.getString("last_sync_error"),
                    )
                },
            )
        }.list()

    fun userExists(id: Long): Boolean =
        jdbc.sql("SELECT count(*) FROM app_user WHERE id = :id").param("id", id).query(Long::class.java).single() > 0

    /** ShedLock lock names currently held by any instance, with when they expire. */
    fun heldLocks(): Map<String, Instant> =
        jdbc.sql("SELECT name, lock_until FROM shedlock WHERE lock_until > timezone('utc', now())")
            .query { rs, _ -> rs.getString("name") to rs.getTimestamp("lock_until").toLocalDateTime().toInstant(ZoneOffset.UTC) }
            .list().toMap()

    private fun ResultSet.instant(column: String): Instant? = getTimestamp(column)?.toInstant()
}
