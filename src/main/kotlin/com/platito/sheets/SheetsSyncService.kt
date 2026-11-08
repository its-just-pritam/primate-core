package com.platito.sheets

import com.fasterxml.jackson.databind.ObjectMapper
import com.platito.config.AppProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

data class TableCounts(var inserted: Int = 0, var updated: Int = 0, var unchanged: Int = 0, var skipped: Int = 0) {
    fun record(outcome: Outcome) = when (outcome) {
        Outcome.INSERTED -> inserted++
        Outcome.UPDATED -> updated++
        Outcome.UNCHANGED -> unchanged++
    }
}

data class SheetsSyncReport(val counts: Map<String, TableCounts>, val warnings: List<String>)

/**
 * One-way sync from the Google Sheet into Postgres. Every Sheet row is matched to a database row
 * on its natural key (see [SheetsSyncRepository]) and inserted or updated so the database matches
 * the Sheet. Nothing is deleted: rows missing from the Sheet, and rows created in the app, are left
 * alone. A run applies all of its changes in one transaction, or none of them.
 */
@Service
class SheetsSyncService(
    private val source: SheetSource,
    private val repository: SheetsSyncRepository,
    private val properties: AppProperties,
    private val objectMapper: ObjectMapper,
    private val transactions: TransactionTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Runs a sync and records its outcome in `sheets_sync_run`. Never throws. */
    fun run(): SheetsSyncReport? {
        val runId = repository.startRun()
        return try {
            val snapshot = SheetMapper(properties.sheets.zone).map(source)
            val report = transactions.execute { apply(snapshot) }!!
            repository.finishRun(runId, success = true, json(report.counts), json(report.warnings.take(MAX_STORED_WARNINGS)), error = null)
            log.info("Sheets sync finished: {} ({} warning(s))", report.counts, report.warnings.size)
            report.warnings.take(MAX_LOGGED_WARNINGS).forEach { log.warn("Sheets sync: {}", it) }
            report
        } catch (e: Exception) {
            log.error("Sheets sync failed: {}", e.message, e)
            repository.finishRun(runId, success = false, countsJson = null, warningsJson = null, error = e.message?.take(1000))
            null
        }
    }

    private fun apply(snapshot: SheetSnapshot): SheetsSyncReport {
        val counts = TABLES.associateWith { TableCounts(skipped = snapshot.skipped[it] ?: 0) }
        val warnings = snapshot.warnings.toMutableList()
        fun skip(table: String, warning: String) {
            counts.getValue(table).skipped++
            warnings += warning
        }

        for (user in snapshot.users) {
            val holder = repository.emailOfUserNamed(user.name)
            if (holder != null && holder != user.email) {
                skip("app_user", "User ${user.email}: the name \"${user.name}\" belongs to $holder in the database; skipped.")
                continue
            }
            counts.getValue("app_user").record(repository.upsertUser(user))
        }
        val userIds = repository.userIdsByEmail()
        fun userId(table: String, email: String, what: String): Long? =
            userIds[email] ?: null.also { skip(table, "$what ($email): no such user; skipped.") }

        snapshot.ingredients.forEach { counts.getValue("ingredient").record(repository.upsertIngredient(it)) }
        val ingredientIds = repository.ingredientIdsByName()

        for (recipe in snapshot.recipes) {
            val userId = userId("recipe", recipe.email, "Recipe \"${recipe.name}\"") ?: continue
            val lines = recipe.lines.mapNotNull { line ->
                ingredientIds[line.ingredient]?.let { it to line.quantity }
                    ?: null.also { warnings += "Recipe \"${recipe.name}\" (${recipe.email}) uses unknown ingredient \"${line.ingredient}\"; line skipped." }
            }
            if (lines.isEmpty()) {
                skip("recipe", "Recipe \"${recipe.name}\" (${recipe.email}) has no known ingredients; skipped.")
                continue
            }
            counts.getValue("recipe").record(repository.upsertRecipe(userId, recipe.name, lines))
        }
        for (food in snapshot.packagedFoods) {
            val userId = userId("packaged_food", food.email, "Packaged food \"${food.name}\"") ?: continue
            counts.getValue("packaged_food").record(repository.upsertPackagedFood(userId, food))
        }
        for (meal in snapshot.meals) {
            val userId = userId("meal_log", meal.email, "Meal \"${meal.dish}\" at ${meal.loggedAt}") ?: continue
            counts.getValue("meal_log").record(repository.upsertMeal(userId, meal))
        }
        for (workout in snapshot.workouts) {
            val userId = userId("workout", workout.email, "Workout \"${workout.name}\" at ${workout.performedAt}") ?: continue
            counts.getValue("workout").record(repository.upsertWorkout(userId, workout))
        }
        for (plan in snapshot.plans) {
            val userId = userId("plan", plan.email, "Plan \"${plan.name}\"") ?: continue
            counts.getValue("plan").record(repository.upsertPlan(userId, plan))
        }
        return SheetsSyncReport(counts, warnings)
    }

    private fun json(value: Any): String = objectMapper.writeValueAsString(value)

    private companion object {
        val TABLES = listOf("app_user", "ingredient", "recipe", "recipe_ingredient", "packaged_food", "meal_log", "workout", "plan")
        const val MAX_STORED_WARNINGS = 1000
        const val MAX_LOGGED_WARNINGS = 50
    }
}
