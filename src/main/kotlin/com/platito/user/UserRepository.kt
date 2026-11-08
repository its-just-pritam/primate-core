package com.platito.user

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet

data class UserRecord(
    val id: Long,
    val email: String,
    val name: String,
    val firstName: String,
    val lastName: String,
    val passwordHash: String?,
    val weightKg: Double,
    val heightCm: Double,
    val workoutsPerWeek: Double,
    val onboardingStep: Int,
    val timeZone: String,
    val phoneNumber: String?,
)

@Repository
class UserRepository(private val jdbc: JdbcClient) {
    fun findById(id: Long): UserRecord? =
        jdbc.sql("SELECT * FROM app_user WHERE id = :id")
            .param("id", id)
            .query { rs, _ -> map(rs) }
            .optional().orElse(null)

    fun findByEmail(email: String): UserRecord? =
        jdbc.sql("SELECT * FROM app_user WHERE lower(email) = lower(:email)")
            .param("email", email.trim())
            .query { rs, _ -> map(rs) }
            .optional().orElse(null)

    fun existsByEmail(email: String): Boolean =
        jdbc.sql("SELECT count(*) FROM app_user WHERE lower(email) = lower(:email)")
            .param("email", email.trim())
            .query(Long::class.java).single() > 0

    fun existsByName(name: String): Boolean =
        jdbc.sql("SELECT count(*) FROM app_user WHERE lower(name) = lower(:name)")
            .param("name", name.trim())
            .query(Long::class.java).single() > 0

    fun create(email: String, firstName: String, lastName: String, passwordHash: String): UserRecord =
        jdbc.sql(
            """
            INSERT INTO app_user (email, name, first_name, last_name, password_hash)
            VALUES (:email, :name, :firstName, :lastName, :passwordHash)
            RETURNING *
            """.trimIndent(),
        )
            .param("email", email)
            .param("name", "$firstName $lastName".trim())
            .param("firstName", firstName)
            .param("lastName", lastName)
            .param("passwordHash", passwordHash)
            .query { rs, _ -> map(rs) }
            .single()

    fun updatePasswordHash(id: Long, passwordHash: String) {
        jdbc.sql("UPDATE app_user SET password_hash = :hash, updated_at = now() WHERE id = :id")
            .param("hash", passwordHash).param("id", id).update()
    }

    fun updateBodyMetrics(id: Long, weightKg: Double, heightCm: Double, workoutsPerWeek: Double) {
        jdbc.sql(
            """
            UPDATE app_user
            SET weight_kg = :weight, height_cm = :height, workouts_per_week = :wpw, updated_at = now()
            WHERE id = :id
            """.trimIndent(),
        )
            .param("weight", weightKg).param("height", heightCm).param("wpw", workoutsPerWeek).param("id", id)
            .update()
    }

    fun updatePhoneNumber(id: Long, phoneNumber: String?) {
        jdbc.sql("UPDATE app_user SET phone_number = :phone, updated_at = now() WHERE id = :id")
            .param("phone", phoneNumber).param("id", id).update()
    }

    /** Raises the completed-step count to [step] (never lowers it) and returns the stored value. */
    fun advanceOnboardingStep(id: Long, step: Int): Int =
        jdbc.sql(
            """
            UPDATE app_user SET onboarding_step = GREATEST(onboarding_step, :step), updated_at = now()
            WHERE id = :id
            RETURNING onboarding_step
            """.trimIndent(),
        )
            .param("step", step).param("id", id)
            .query(Int::class.java).single()

    fun updateTimeZone(id: Long, timeZone: String) {
        jdbc.sql("UPDATE app_user SET time_zone = :tz, updated_at = now() WHERE id = :id AND time_zone <> :tz")
            .param("tz", timeZone).param("id", id).update()
    }

    private fun map(rs: ResultSet) = UserRecord(
        id = rs.getLong("id"),
        email = rs.getString("email"),
        name = rs.getString("name"),
        firstName = rs.getString("first_name"),
        lastName = rs.getString("last_name"),
        passwordHash = rs.getString("password_hash"),
        weightKg = rs.getDouble("weight_kg"),
        heightCm = rs.getDouble("height_cm"),
        workoutsPerWeek = rs.getDouble("workouts_per_week"),
        onboardingStep = rs.getInt("onboarding_step"),
        timeZone = rs.getString("time_zone"),
        phoneNumber = rs.getString("phone_number"),
    )
}
