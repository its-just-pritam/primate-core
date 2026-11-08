package com.platito.sheets

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.Timestamp

enum class Outcome { INSERTED, UPDATED, UNCHANGED }

/**
 * Writes Sheet records onto their natural keys. Rows are inserted or brought in line with the
 * Sheet, never deleted; an update only happens when a value actually differs.
 */
@Repository
class SheetsSyncRepository(private val jdbc: JdbcClient) {
    fun startRun(): Long =
        jdbc.sql("INSERT INTO sheets_sync_run DEFAULT VALUES RETURNING id").query(Long::class.java).single()

    fun finishRun(id: Long, success: Boolean, countsJson: String?, warningsJson: String?, error: String?) {
        jdbc.sql(
            """
            UPDATE sheets_sync_run
            SET finished_at = now(), status = :status, counts = CAST(:counts AS jsonb),
                warnings = CAST(:warnings AS jsonb), error = :error
            WHERE id = :id
            """.trimIndent(),
        )
            .param("status", if (success) "SUCCESS" else "FAILED").param("counts", countsJson)
            .param("warnings", warningsJson).param("error", error).param("id", id)
            .update()
    }

    /** The email of the user who already has [name], if any. */
    fun emailOfUserNamed(name: String): String? =
        jdbc.sql("SELECT lower(email) FROM app_user WHERE lower(name) = lower(:name)")
            .param("name", name).query(String::class.java).optional().orElse(null)

    fun userIdsByEmail(): Map<String, Long> =
        jdbc.sql("SELECT id, lower(email) AS email FROM app_user")
            .query { rs, _ -> rs.getString("email") to rs.getLong("id") }.list().toMap()

    fun ingredientIdsByName(): Map<String, Long> =
        jdbc.sql("SELECT id, name FROM ingredient")
            .query { rs, _ -> rs.getString("name") to rs.getLong("id") }.list().toMap()

    /**
     * Matched on email. The password hash and onboarding progress are only set on insert: both
     * change in the app afterwards and the Sheet's copies are stale.
     */
    fun upsertUser(user: SheetUser): Outcome =
        upsertReturning(
            """
            INSERT INTO app_user (email, name, first_name, last_name, password_hash, weight_kg, height_cm, workouts_per_week, onboarding_step)
            VALUES (:email, :name, :firstName, :lastName, :passwordHash, :weight, :height, :wpw, :onboardingStep)
            ON CONFLICT ((lower(email))) DO UPDATE SET
                name = EXCLUDED.name, first_name = EXCLUDED.first_name, last_name = EXCLUDED.last_name,
                weight_kg = EXCLUDED.weight_kg, height_cm = EXCLUDED.height_cm,
                workouts_per_week = EXCLUDED.workouts_per_week, updated_at = now()
            WHERE (app_user.name, app_user.first_name, app_user.last_name, app_user.weight_kg, app_user.height_cm, app_user.workouts_per_week)
                IS DISTINCT FROM (EXCLUDED.name, EXCLUDED.first_name, EXCLUDED.last_name, EXCLUDED.weight_kg, EXCLUDED.height_cm, EXCLUDED.workouts_per_week)
            RETURNING (xmax = 0) AS inserted
            """.trimIndent(),
        ) {
            it.param("email", user.email).param("name", user.name).param("firstName", user.firstName)
                .param("lastName", user.lastName).param("passwordHash", user.passwordHash)
                .param("weight", user.weightKg).param("height", user.heightCm).param("wpw", user.workoutsPerWeek)
                .param("onboardingStep", if (user.onboardingFinished) FINISHED_ONBOARDING_STEP else 0)
        }

    /** Matched on name. */
    fun upsertIngredient(ingredient: SheetIngredient): Outcome =
        upsertReturning(
            """
            INSERT INTO ingredient (name, base_quantity, unit, $NUTRIENT_COLUMNS)
            VALUES (:name, :baseQuantity, :unit, $NUTRIENT_PARAMS)
            ON CONFLICT (name) DO UPDATE SET base_quantity = EXCLUDED.base_quantity, unit = EXCLUDED.unit, ${nutrientAssignments()}
            WHERE (ingredient.base_quantity, ingredient.unit, ${qualified("ingredient")})
                IS DISTINCT FROM (EXCLUDED.base_quantity, EXCLUDED.unit, ${qualified("EXCLUDED")})
            RETURNING (xmax = 0) AS inserted
            """.trimIndent(),
        ) {
            it.param("name", ingredient.name).param("baseQuantity", ingredient.baseQuantity).param("unit", ingredient.unit)
                .nutrientParams(ingredient.nutrients)
        }

    /** Matched on user and name; the lines are rewritten when they differ from the Sheet's. */
    fun upsertRecipe(userId: Long, name: String, lines: List<Pair<Long, Double>>): Outcome {
        val existingId = jdbc.sql("SELECT id FROM recipe WHERE user_id = :userId AND name = :name")
            .param("userId", userId).param("name", name).query(Long::class.java).optional().orElse(null)
        val recipeId = existingId
            ?: jdbc.sql("INSERT INTO recipe (user_id, name) VALUES (:userId, :name) RETURNING id")
                .param("userId", userId).param("name", name).query(Long::class.java).single()
        if (existingId != null) {
            val current = jdbc.sql("SELECT ingredient_id, quantity FROM recipe_ingredient WHERE recipe_id = :id ORDER BY position")
                .param("id", recipeId).query { rs, _ -> rs.getLong("ingredient_id") to rs.getDouble("quantity") }.list()
            if (current == lines) return Outcome.UNCHANGED
            jdbc.sql("DELETE FROM recipe_ingredient WHERE recipe_id = :id").param("id", recipeId).update()
        }
        lines.forEachIndexed { position, (ingredientId, quantity) ->
            jdbc.sql(
                "INSERT INTO recipe_ingredient (recipe_id, ingredient_id, quantity, position) VALUES (:recipeId, :ingredientId, :quantity, :position)",
            ).param("recipeId", recipeId).param("ingredientId", ingredientId).param("quantity", quantity).param("position", position).update()
        }
        return if (existingId == null) Outcome.INSERTED else Outcome.UPDATED
    }

    /** Matched on user and name. */
    fun upsertPackagedFood(userId: Long, food: SheetPackagedFood): Outcome =
        upsertReturning(
            """
            INSERT INTO packaged_food (user_id, name, quantity, $NUTRIENT_COLUMNS)
            VALUES (:userId, :name, :quantity, $NUTRIENT_PARAMS)
            ON CONFLICT (user_id, name) DO UPDATE SET quantity = EXCLUDED.quantity, ${nutrientAssignments()}
            WHERE (packaged_food.quantity, ${qualified("packaged_food")}) IS DISTINCT FROM (EXCLUDED.quantity, ${qualified("EXCLUDED")})
            RETURNING (xmax = 0) AS inserted
            """.trimIndent(),
        ) {
            it.param("userId", userId).param("name", food.name).param("quantity", food.quantity).nutrientParams(food.nutrients)
        }

    /** Matched on user, dish and time. */
    fun upsertMeal(userId: Long, meal: SheetMeal): Outcome =
        upsertByKey(
            update = """
                UPDATE meal_log SET portion = :portion
                WHERE user_id = :userId AND dish = :dish AND logged_at = :loggedAt AND portion IS DISTINCT FROM :portion
            """,
            exists = "SELECT count(*) FROM meal_log WHERE user_id = :userId AND dish = :dish AND logged_at = :loggedAt",
            insert = "INSERT INTO meal_log (user_id, dish, portion, logged_at) VALUES (:userId, :dish, :portion, :loggedAt)",
        ) {
            it.param("userId", userId).param("dish", meal.dish).param("portion", meal.portion)
                .param("loggedAt", Timestamp.from(meal.loggedAt))
        }

    /** Matched on user, name and time among workouts logged directly. */
    fun upsertWorkout(userId: Long, workout: SheetWorkout): Outcome =
        upsertByKey(
            update = """
                UPDATE workout SET calories_burnt = :calories
                WHERE user_id = :userId AND source = 'DIRECT' AND name = :name AND performed_at = :performedAt
                  AND calories_burnt IS DISTINCT FROM :calories
            """,
            exists = "SELECT count(*) FROM workout WHERE user_id = :userId AND source = 'DIRECT' AND name = :name AND performed_at = :performedAt",
            insert = """
                INSERT INTO workout (user_id, name, calories_burnt, performed_at, source)
                VALUES (:userId, :name, :calories, :performedAt, 'DIRECT')
            """,
        ) {
            it.param("userId", userId).param("name", workout.name).param("calories", workout.caloriesBurnt)
                .param("performedAt", Timestamp.from(workout.performedAt))
        }

    /** Matched on user, name and start. A plan deleted in the Sheet is soft-deleted, keeping an earlier deletion time. */
    fun upsertPlan(userId: Long, plan: SheetPlan): Outcome =
        upsertByKey(
            update = """
                UPDATE plan SET target_weight_kg = :target, resting_calories = :resting, comment = :comment,
                    deleted_at = CASE WHEN :deleted THEN COALESCE(deleted_at, now()) END
                WHERE user_id = :userId AND name = :name AND start_at = :startAt
                  AND (target_weight_kg, resting_calories, comment, deleted_at IS NOT NULL)
                      IS DISTINCT FROM (CAST(:target AS DOUBLE PRECISION), CAST(:resting AS DOUBLE PRECISION), CAST(:comment AS TEXT), CAST(:deleted AS BOOLEAN))
            """,
            exists = "SELECT count(*) FROM plan WHERE user_id = :userId AND name = :name AND start_at = :startAt",
            insert = """
                INSERT INTO plan (user_id, name, target_weight_kg, resting_calories, start_at, comment, deleted_at)
                VALUES (:userId, :name, :target, :resting, :startAt, :comment, CASE WHEN :deleted THEN now() END)
            """,
        ) {
            it.param("userId", userId).param("name", plan.name).param("target", plan.targetWeightKg)
                .param("resting", plan.restingCalories).param("startAt", Timestamp.from(plan.startAt))
                .param("comment", plan.comment).param("deleted", plan.deleted)
        }

    /** Runs an `INSERT … ON CONFLICT DO UPDATE … WHERE changed RETURNING (xmax = 0)`; no row back means unchanged. */
    private fun upsertReturning(sql: String, params: (JdbcClient.StatementSpec) -> JdbcClient.StatementSpec): Outcome =
        params(jdbc.sql(sql)).query(Boolean::class.java).optional()
            .map { inserted -> if (inserted) Outcome.INSERTED else Outcome.UPDATED }
            .orElse(Outcome.UNCHANGED)

    /** For tables whose natural key has no unique index: update matching rows that differ, else insert if none match. */
    private fun upsertByKey(update: String, exists: String, insert: String, params: (JdbcClient.StatementSpec) -> JdbcClient.StatementSpec): Outcome {
        if (params(jdbc.sql(update.trimIndent())).update() > 0) return Outcome.UPDATED
        if (params(jdbc.sql(exists)).query(Long::class.java).single() > 0) return Outcome.UNCHANGED
        params(jdbc.sql(insert.trimIndent())).update()
        return Outcome.INSERTED
    }

    private fun JdbcClient.StatementSpec.nutrientParams(n: Nutrients) = this
        .param("calories", n.calories).param("protein", n.protein).param("carbs", n.carbs).param("fats", n.fats)
        .param("calcium", n.micros.calcium).param("iron", n.micros.iron)
        .param("potassium", n.micros.potassium).param("sodium", n.micros.sodium)
        .param("vitaminC", n.micros.vitaminC).param("vitaminB12", n.micros.vitaminB12)
        .param("vitaminD", n.micros.vitaminD)

    private companion object {
        /** onboarding_step counts completed steps; the Sheet only knew finished or not. */
        const val FINISHED_ONBOARDING_STEP = 5

        val NUTRIENTS = listOf("calories", "protein", "carbs", "fats", "calcium", "iron", "potassium", "sodium", "vitamin_c", "vitamin_b12", "vitamin_d")
        val NUTRIENT_COLUMNS = NUTRIENTS.joinToString()
        const val NUTRIENT_PARAMS = ":calories, :protein, :carbs, :fats, :calcium, :iron, :potassium, :sodium, :vitaminC, :vitaminB12, :vitaminD"

        fun nutrientAssignments() = NUTRIENTS.joinToString { "$it = EXCLUDED.$it" }
        fun qualified(table: String) = NUTRIENTS.joinToString { "$table.$it" }
    }
}
