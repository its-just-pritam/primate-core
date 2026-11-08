package com.platito.garmin

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.LocalDate

enum class GarminMetricKind { DAILY_SUMMARY, SLEEP }

data class GarminIntegrationRecord(
    val userId: Long,
    val apiKeyCiphertext: String,
    val deviceName: String?,
    val connectedAt: Instant,
    val lastSyncFinishedAt: Instant?,
    val lastSyncStatus: String?,
    val lastSyncError: String?,
)

data class StoredMetric(val date: LocalDate, val payload: String, val fetchedAt: Instant)

@Repository
class GarminRepository(private val jdbc: JdbcClient) {
    fun findIntegration(userId: Long): GarminIntegrationRecord? =
        jdbc.sql("SELECT * FROM garmin_integration WHERE user_id = :userId")
            .param("userId", userId)
            .query { rs, _ ->
                GarminIntegrationRecord(
                    userId = rs.getLong("user_id"),
                    apiKeyCiphertext = rs.getString("api_key_ciphertext"),
                    deviceName = rs.getString("device_name"),
                    connectedAt = rs.getTimestamp("connected_at").toInstant(),
                    lastSyncFinishedAt = rs.getTimestamp("last_sync_finished_at")?.toInstant(),
                    lastSyncStatus = rs.getString("last_sync_status"),
                    lastSyncError = rs.getString("last_sync_error"),
                )
            }.optional().orElse(null)

    fun connectedUserIds(): List<Long> =
        jdbc.sql("SELECT user_id FROM garmin_integration ORDER BY user_id").query(Long::class.java).list()

    /** Stores a (new) key and resets sync state, since it may belong to a different Garmin account. */
    fun saveIntegration(userId: Long, apiKeyCiphertext: String) {
        jdbc.sql(
            """
            INSERT INTO garmin_integration (user_id, api_key_ciphertext) VALUES (:userId, :ciphertext)
            ON CONFLICT (user_id) DO UPDATE SET api_key_ciphertext = EXCLUDED.api_key_ciphertext,
                connected_at = now(), device_name = NULL, last_sync_started_at = NULL,
                last_sync_finished_at = NULL, last_sync_status = NULL, last_sync_error = NULL
            """.trimIndent(),
        ).param("userId", userId).param("ciphertext", apiKeyCiphertext).update()
        deleteMetrics(userId)
    }

    fun deleteIntegration(userId: Long) {
        jdbc.sql("DELETE FROM garmin_integration WHERE user_id = :userId").param("userId", userId).update()
        deleteMetrics(userId)
    }

    fun markSyncStarted(userId: Long) {
        jdbc.sql("UPDATE garmin_integration SET last_sync_started_at = now() WHERE user_id = :userId")
            .param("userId", userId).update()
    }

    fun markSyncFinished(userId: Long, success: Boolean, error: String?) {
        jdbc.sql(
            """
            UPDATE garmin_integration
            SET last_sync_finished_at = now(), last_sync_status = :status, last_sync_error = :error
            WHERE user_id = :userId
            """.trimIndent(),
        )
            .param("userId", userId).param("status", if (success) "SUCCESS" else "FAILED").param("error", error)
            .update()
    }

    fun updateDeviceName(userId: Long, deviceName: String) {
        jdbc.sql("UPDATE garmin_integration SET device_name = :name WHERE user_id = :userId")
            .param("name", deviceName).param("userId", userId).update()
    }

    fun findMetrics(userId: Long, kind: GarminMetricKind, from: LocalDate, to: LocalDate): List<StoredMetric> =
        jdbc.sql(
            """
            SELECT calendar_date, payload::text AS payload, fetched_at FROM garmin_daily_metric
            WHERE user_id = :userId AND kind = :kind AND calendar_date BETWEEN :from AND :to
            ORDER BY calendar_date
            """.trimIndent(),
        )
            .param("userId", userId).param("kind", kind.name).param("from", from).param("to", to)
            .query { rs, _ ->
                StoredMetric(rs.getObject("calendar_date", LocalDate::class.java), rs.getString("payload"), rs.getTimestamp("fetched_at").toInstant())
            }.list()

    fun upsertMetric(userId: Long, kind: GarminMetricKind, date: LocalDate, payloadJson: String) {
        jdbc.sql(
            """
            INSERT INTO garmin_daily_metric (user_id, kind, calendar_date, payload, fetched_at)
            VALUES (:userId, :kind, :date, CAST(:payload AS jsonb), now())
            ON CONFLICT (user_id, kind, calendar_date) DO UPDATE SET payload = EXCLUDED.payload, fetched_at = now()
            """.trimIndent(),
        )
            .param("userId", userId).param("kind", kind.name).param("date", date).param("payload", payloadJson)
            .update()
    }

    private fun deleteMetrics(userId: Long) {
        jdbc.sql("DELETE FROM garmin_daily_metric WHERE user_id = :userId").param("userId", userId).update()
    }
}
