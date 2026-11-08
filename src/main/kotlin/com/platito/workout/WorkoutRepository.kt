package com.platito.workout

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class WorkoutSource { DIRECT, GARMIN }

data class WorkoutRecord(
    val id: Long,
    val name: String,
    val caloriesBurnt: Double,
    val performedAt: Instant,
    val source: WorkoutSource,
    val externalId: String?,
)

data class GarminWorkout(val externalId: String, val name: String, val caloriesBurnt: Double, val performedAt: Instant)

@Repository
class WorkoutRepository(private val jdbc: JdbcClient) {
    fun findByUser(userId: Long): List<WorkoutRecord> =
        jdbc.sql("SELECT * FROM workout WHERE user_id = :userId ORDER BY performed_at, id")
            .param("userId", userId)
            .query { rs, _ -> map(rs) }.list()

    fun findById(userId: Long, id: Long): WorkoutRecord? =
        jdbc.sql("SELECT * FROM workout WHERE user_id = :userId AND id = :id")
            .param("userId", userId).param("id", id)
            .query { rs, _ -> map(rs) }.optional().orElse(null)

    fun insertDirect(userId: Long, name: String, caloriesBurnt: Double, performedAt: Instant): Long =
        jdbc.sql(
            """
            INSERT INTO workout (user_id, name, calories_burnt, performed_at, source)
            VALUES (:userId, :name, :calories, :performedAt, 'DIRECT')
            RETURNING id
            """.trimIndent(),
        )
            .param("userId", userId).param("name", name).param("calories", caloriesBurnt)
            .param("performedAt", Timestamp.from(performedAt))
            .query(Long::class.java).single()

    fun delete(userId: Long, id: Long): Int =
        jdbc.sql("DELETE FROM workout WHERE user_id = :userId AND id = :id")
            .param("userId", userId).param("id", id).update()

    /** Garmin workouts that started on [date] in [zone]. */
    fun findGarminOnLocalDate(userId: Long, date: LocalDate, zone: ZoneId): List<WorkoutRecord> =
        jdbc.sql(
            """
            SELECT * FROM workout
            WHERE user_id = :userId AND source = 'GARMIN'
              AND (performed_at AT TIME ZONE :zone)::date = :date
            """.trimIndent(),
        )
            .param("userId", userId).param("zone", zone.id).param("date", date)
            .query { rs, _ -> map(rs) }.list()

    fun findGarminExternalIds(userId: Long, externalIds: Collection<String>): Set<String> {
        if (externalIds.isEmpty()) return emptySet()
        return jdbc.sql("SELECT external_id FROM workout WHERE user_id = :userId AND source = 'GARMIN' AND external_id IN (:ids)")
            .param("userId", userId).param("ids", externalIds)
            .query(String::class.java).list().toSet()
    }

    fun upsertGarmin(userId: Long, workout: GarminWorkout) {
        jdbc.sql(
            """
            INSERT INTO workout (user_id, name, calories_burnt, performed_at, source, external_id)
            VALUES (:userId, :name, :calories, :performedAt, 'GARMIN', :externalId)
            ON CONFLICT (user_id, source, external_id) WHERE external_id IS NOT NULL
            DO UPDATE SET name = EXCLUDED.name, calories_burnt = EXCLUDED.calories_burnt, performed_at = EXCLUDED.performed_at
            """.trimIndent(),
        )
            .param("userId", userId).param("name", workout.name).param("calories", workout.caloriesBurnt)
            .param("performedAt", Timestamp.from(workout.performedAt)).param("externalId", workout.externalId)
            .update()
    }

    fun deleteByIds(userId: Long, ids: Collection<Long>): Int {
        if (ids.isEmpty()) return 0
        return jdbc.sql("DELETE FROM workout WHERE user_id = :userId AND id IN (:ids)")
            .param("userId", userId).param("ids", ids).update()
    }

    private fun map(rs: ResultSet) = WorkoutRecord(
        id = rs.getLong("id"),
        name = rs.getString("name"),
        caloriesBurnt = rs.getDouble("calories_burnt"),
        performedAt = rs.getTimestamp("performed_at").toInstant(),
        source = WorkoutSource.valueOf(rs.getString("source")),
        externalId = rs.getString("external_id"),
    )
}
