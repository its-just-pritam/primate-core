package com.platito.plan

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

data class PlanRecord(
    val id: Long,
    val name: String,
    val targetWeightKg: Double,
    val restingCalories: Double,
    val startAt: Instant,
    val comment: String,
    val deletedAt: Instant?,
)

data class PlanInput(
    val name: String,
    val targetWeightKg: Double,
    val restingCalories: Double,
    val startAt: Instant,
    val comment: String,
)

@Repository
class PlanRepository(private val jdbc: JdbcClient) {
    fun findByUser(userId: Long): List<PlanRecord> =
        jdbc.sql("SELECT * FROM plan WHERE user_id = :userId ORDER BY id")
            .param("userId", userId)
            .query { rs, _ -> map(rs) }.list()

    fun findById(userId: Long, id: Long): PlanRecord? =
        jdbc.sql("SELECT * FROM plan WHERE user_id = :userId AND id = :id")
            .param("userId", userId).param("id", id)
            .query { rs, _ -> map(rs) }.optional().orElse(null)

    fun insert(userId: Long, plan: PlanInput): Long =
        jdbc.sql(
            """
            INSERT INTO plan (user_id, name, target_weight_kg, resting_calories, start_at, comment)
            VALUES (:userId, :name, :target, :resting, :startAt, :comment)
            RETURNING id
            """.trimIndent(),
        ).param("userId", userId).planParams(plan).query(Long::class.java).single()

    fun update(userId: Long, id: Long, plan: PlanInput) {
        jdbc.sql(
            """
            UPDATE plan SET name = :name, target_weight_kg = :target, resting_calories = :resting,
                start_at = :startAt, comment = :comment
            WHERE user_id = :userId AND id = :id
            """.trimIndent(),
        ).param("userId", userId).param("id", id).planParams(plan).update()
    }

    fun softDelete(userId: Long, id: Long, comment: String?) {
        jdbc.sql(
            """
            UPDATE plan SET deleted_at = now(), comment = COALESCE(:comment, comment)
            WHERE user_id = :userId AND id = :id
            """.trimIndent(),
        ).param("userId", userId).param("id", id).param("comment", comment).update()
    }

    private fun JdbcClient.StatementSpec.planParams(plan: PlanInput) = this
        .param("name", plan.name).param("target", plan.targetWeightKg).param("resting", plan.restingCalories)
        .param("startAt", Timestamp.from(plan.startAt)).param("comment", plan.comment)

    private fun map(rs: ResultSet) = PlanRecord(
        id = rs.getLong("id"),
        name = rs.getString("name"),
        targetWeightKg = rs.getDouble("target_weight_kg"),
        restingCalories = rs.getDouble("resting_calories"),
        startAt = rs.getTimestamp("start_at").toInstant(),
        comment = rs.getString("comment"),
        deletedAt = rs.getTimestamp("deleted_at")?.toInstant(),
    )
}
