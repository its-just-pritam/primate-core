package com.platito.sheets

import com.platito.nutrition.Micronutrients
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.math.roundToLong

data class Nutrients(val calories: Double, val protein: Double, val carbs: Double, val fats: Double, val micros: Micronutrients)

data class SheetUser(
    val email: String,
    val name: String,
    val firstName: String,
    val lastName: String,
    val passwordHash: String?,
    val weightKg: Double,
    val heightCm: Double,
    val workoutsPerWeek: Double,
    val onboardingFinished: Boolean,
)

data class SheetIngredient(val name: String, val baseQuantity: Double, val unit: String, val nutrients: Nutrients)

data class SheetRecipeLine(val ingredient: String, val quantity: Double)

data class SheetRecipe(val email: String, val name: String, val lines: List<SheetRecipeLine>)

data class SheetPackagedFood(val email: String, val name: String, val quantity: Double, val nutrients: Nutrients)

data class SheetMeal(val email: String, val dish: String, val portion: Double, val loggedAt: Instant)

data class SheetWorkout(val email: String, val name: String, val caloriesBurnt: Double, val performedAt: Instant)

data class SheetPlan(
    val email: String,
    val name: String,
    val targetWeightKg: Double,
    val restingCalories: Double,
    val startAt: Instant,
    val comment: String,
    val deleted: Boolean,
)

/** Everything the sync takes from the Sheet, already validated, plus why rows were left out. */
data class SheetSnapshot(
    val users: List<SheetUser>,
    val ingredients: List<SheetIngredient>,
    val recipes: List<SheetRecipe>,
    val packagedFoods: List<SheetPackagedFood>,
    val meals: List<SheetMeal>,
    val workouts: List<SheetWorkout>,
    val plans: List<SheetPlan>,
    val skipped: Map<String, Int>,
    val warnings: List<String>,
)

/**
 * Turns the Sheet's tabs into records. Columns are found by header, compared lowercase with
 * everything but letters and digits removed, so "Calories (kcal)" matches `calorieskcal`.
 * Each record keeps the natural key the sync matches it on; a row repeating an earlier row's key
 * is left out with a warning, as is every row that cannot be stored.
 */
class SheetMapper(private val zone: ZoneId) {
    private val warnings = mutableListOf<String>()
    private val skipped = mutableMapOf<String, Int>()

    fun map(source: SheetSource): SheetSnapshot {
        val users = users(tab(source, "User", USER_FIELDS))
        val ingredients = ingredients(tab(source, "Ingredients", INGREDIENT_FIELDS))
        val recipes = recipes(tab(source, "Recipe", RECIPE_FIELDS))
        val packaged = packagedFoods(tab(source, "Packaged", PACKAGED_FIELDS, optional = true))
        val meals = meals(tab(source, "Food", FOOD_FIELDS))
        val workouts = workouts(tab(source, "Workouts", WORKOUT_FIELDS))
        val plans = plans(tab(source, "Plan", PLAN_FIELDS))
        return SheetSnapshot(users, ingredients, recipes, packaged, meals, workouts, plans, skipped.toMap(), warnings.toList())
    }

    private fun users(rows: List<Row>): List<SheetUser> {
        val names = mutableSetOf<String>()
        return rows.distinctByKey("app_user", { it.text("email").lowercase() }) { row ->
            val email = row.text("email").lowercase()
            val name = row.text("name")
            when {
                email.isEmpty() || name.isEmpty() -> row.skip("app_user", "needs both an email and a name")
                !names.add(name.lowercase()) -> row.skip("app_user", "name \"$name\" is already used by an earlier row")
                else -> SheetUser(
                    email = email,
                    name = name,
                    firstName = row.text("firstName"),
                    lastName = row.text("lastName"),
                    passwordHash = row.text("passwordHash").ifEmpty { null },
                    weightKg = row.number("weight").coerceAtLeast(0.0),
                    heightCm = row.number("height").coerceAtLeast(0.0),
                    workoutsPerWeek = row.number("workoutsPerWeek").coerceAtLeast(0.0),
                    onboardingFinished = row.text("onboardingStep") != "0",
                )
            }
        }
    }

    private fun ingredients(rows: List<Row>): List<SheetIngredient> =
        rows.distinctByKey("ingredient", { it.text("name") }) { row ->
            val name = row.text("name")
            if (name.isEmpty()) row.skip("ingredient", "has no name")
            else SheetIngredient(name, row.number("quantity", 1.0).takeIf { it > 0 } ?: 1.0, row.text("unit"), row.nutrients())
        }

    /** The Sheet has one row per ingredient line; lines of the same user and dish form one recipe. */
    private fun recipes(rows: List<Row>): List<SheetRecipe> {
        val recipes = linkedMapOf<Pair<String, String>, MutableList<SheetRecipeLine>>()
        for (row in rows) {
            val email = row.text("email").lowercase()
            val dish = row.text("dish")
            val ingredient = row.text("ingredient")
            val quantity = row.number("quantity")
            when {
                email.isEmpty() || dish.isEmpty() || ingredient.isEmpty() ->
                    row.skip("recipe_ingredient", "needs an email, a dish and an ingredient")
                quantity <= 0 -> row.skip("recipe_ingredient", "quantity must be greater than 0")
                else -> recipes.getOrPut(email to dish) { mutableListOf() }.add(SheetRecipeLine(ingredient, quantity))
            }
        }
        return recipes.map { (key, lines) -> SheetRecipe(key.first, key.second, lines) }
    }

    private fun packagedFoods(rows: List<Row>): List<SheetPackagedFood> =
        rows.distinctByKey("packaged_food", { it.text("email").lowercase() to it.text("dish") }) { row ->
            val email = row.text("email").lowercase()
            val name = row.text("dish")
            if (email.isEmpty() || name.isEmpty()) row.skip("packaged_food", "needs an email and a name")
            else SheetPackagedFood(email, name, row.number("quantity").takeIf { it > 0 } ?: 1.0, row.nutrients())
        }

    private fun meals(rows: List<Row>): List<SheetMeal> =
        rows.distinctByKey("meal_log", { Triple(it.text("email").lowercase(), it.text("dish"), it.instant("timestamp")) }) { row ->
            val email = row.text("email").lowercase()
            val dish = row.text("dish")
            val loggedAt = row.instant("timestamp")
            when {
                email.isEmpty() || dish.isEmpty() -> row.skip("meal_log", "needs an email and a dish")
                loggedAt == null -> row.skip("meal_log", "has an invalid timestamp")
                else -> SheetMeal(email, dish, row.number("portion", 1.0).takeIf { it > 0 } ?: 1.0, loggedAt)
            }
        }

    /** Garmin workouts are left to the Garmin sync, which keys them by activity id. */
    private fun workouts(rows: List<Row>): List<SheetWorkout> =
        rows.filter { it.text("source") != "Garmin Connect" }
            .distinctByKey("workout", { Triple(it.text("email").lowercase(), it.text("name"), it.instant("timestamp")) }) { row ->
                val email = row.text("email").lowercase()
                val name = row.text("name")
                val performedAt = row.instant("timestamp")
                when {
                    email.isEmpty() || name.isEmpty() -> row.skip("workout", "needs an email and a name")
                    performedAt == null -> row.skip("workout", "has an invalid timestamp")
                    else -> SheetWorkout(email, name, row.number("calories").coerceAtLeast(0.0), performedAt)
                }
            }

    private fun plans(rows: List<Row>): List<SheetPlan> =
        rows.distinctByKey("plan", { Triple(it.text("email").lowercase(), it.text("name"), it.instant("startDate")) }) { row ->
            val email = row.text("email").lowercase()
            val name = row.text("name")
            val startAt = row.instant("startDate")
            when {
                email.isEmpty() || name.isEmpty() -> row.skip("plan", "needs an email and a name")
                startAt == null -> row.skip("plan", "has an invalid start date")
                row.number("targetWeight") <= 0 || row.number("restingCalories") <= 0 ->
                    row.skip("plan", "target weight and resting calories must be greater than 0")
                else -> SheetPlan(
                    email = email,
                    name = name,
                    targetWeightKg = row.number("targetWeight"),
                    restingCalories = row.number("restingCalories"),
                    startAt = startAt,
                    comment = row.text("comment"),
                    deleted = row.text("status").uppercase() == "DELETED",
                )
            }
        }

    /** Maps rows in order, leaving out (with a warning) any row whose key an earlier row already used. */
    private fun <K, T : Any> List<Row>.distinctByKey(table: String, key: (Row) -> K, map: (Row) -> T?): List<T> {
        val firstRowByKey = mutableMapOf<K, Int>()
        return mapNotNull { row ->
            val record = map(row) ?: return@mapNotNull null
            val first = firstRowByKey.putIfAbsent(key(row), row.number)
            if (first != null) row.skip(table, "repeats row ${first}") else record
        }
    }

    private fun Row.skip(table: String, reason: String): Nothing? {
        skipped.merge(table, 1, Int::plus)
        warnings += "$tab row $number $reason; skipped."
        return null
    }

    private fun Row.nutrients() = Nutrients(
        calories = number("calories"),
        protein = number("protein"),
        carbs = number("carbs"),
        fats = number("fats"),
        micros = Micronutrients(
            calcium = number("calcium"),
            iron = number("iron"),
            potassium = number("potassium"),
            sodium = number("sodium"),
            vitaminC = number("vitaminC"),
            vitaminB12 = number("vitaminB12"),
            vitaminD = number("vitaminD"),
        ).coerceNonNegative(),
    )

    private fun tab(source: SheetSource, name: String, fields: Map<String, List<String>>, optional: Boolean = false): List<Row> {
        val values = source.readTab(name)
            ?: if (optional) return emptyList() else throw SheetsException("The Sheet has no $name tab.")
        val headers = values.firstOrNull().orEmpty().map { normalize(it?.toString()) }
        val columns = fields.mapValues { (_, aliases) -> headers.indexOfFirst { it in aliases } }
        return values.drop(1).mapIndexedNotNull { index, cells ->
            val row = Row(name, index + 2, columns.mapValues { (_, column) -> cells.getOrNull(column) }, zone)
            row.takeUnless { it.isBlank() }
        }
    }

    private class Row(val tab: String, val number: Int, private val cells: Map<String, Any?>, private val zone: ZoneId) {
        fun isBlank() = cells.keys.all { text(it).isEmpty() }

        fun text(field: String): String = when (val value = cells[field]) {
            null -> ""
            is Number -> BigDecimal(value.toString()).stripTrailingZeros().toPlainString()
            else -> value.toString().trim()
        }

        fun number(field: String, fallback: Double = 0.0): Double = when (val value = cells[field]) {
            is Number -> value.toDouble()
            else -> text(field).toDoubleOrNull()
        }?.takeIf { it.isFinite() } ?: fallback

        /** A date cell (a serial number of days since 1899-12-30) or ISO text; values without an offset are in [zone]. */
        fun instant(field: String): Instant? = when (val value = cells[field]) {
            is Number -> SERIAL_EPOCH.plusNanos((value.toDouble() * MILLIS_PER_DAY).roundToLong() * 1_000_000).atZone(zone).toInstant()
            else -> text(field).takeIf { it.isNotEmpty() }?.let(::parseInstant)
        }

        private fun parseInstant(text: String): Instant? = listOf<(String) -> Instant>(
            { Instant.parse(it) },
            { OffsetDateTime.parse(it).toInstant() },
            { LocalDateTime.parse(it.replace(' ', 'T')).atZone(zone).toInstant() },
            { LocalDate.parse(it).atStartOfDay(zone).toInstant() },
        ).firstNotNullOfOrNull { parser -> runCatching { parser(text) }.getOrNull() }
    }

    private companion object {
        val SERIAL_EPOCH: LocalDateTime = LocalDateTime.of(1899, 12, 30, 0, 0)
        const val MILLIS_PER_DAY = 86_400_000.0

        fun normalize(value: String?) = value.orEmpty().lowercase().replace(Regex("[^a-z0-9]"), "")

        val NUTRIENT_FIELDS = mapOf(
            "calories" to listOf("calories", "calorieskcal"),
            "protein" to listOf("protein", "proteing"),
            "carbs" to listOf("carbs", "carbsg", "carbohydrates", "carbohydratesg"),
            "fats" to listOf("fat", "fatg", "fats"),
            "calcium" to listOf("calcium", "calciummg"),
            "iron" to listOf("iron", "ironmg"),
            "potassium" to listOf("potassium", "potassiummg"),
            "sodium" to listOf("sodium", "sodiummg"),
            "vitaminC" to listOf("vitaminc", "vitamincmg"),
            "vitaminB12" to listOf("vitaminb12", "vitaminb12mcg"),
            "vitaminD" to listOf("vitamind", "vitamindmcg", "vitamindiu"),
        )
        val TIMESTAMP = listOf("timestamp", "timestamputc", "createdat", "createdatutc", "date", "startdate")

        val USER_FIELDS = mapOf(
            "name" to listOf("name"), "email" to listOf("email"), "passwordHash" to listOf("passwordhash"),
            "firstName" to listOf("firstname"), "lastName" to listOf("lastname"), "onboardingStep" to listOf("onboardingstep"),
            "weight" to listOf("weightkg", "weight"), "height" to listOf("heightcm", "heightcms", "height"),
            "workoutsPerWeek" to listOf("workoutsperweek", "workoutperweek"),
        )
        val INGREDIENT_FIELDS = mapOf(
            "name" to listOf("name", "ingredient"), "quantity" to listOf("quantity"), "unit" to listOf("unit"),
        ) + NUTRIENT_FIELDS
        val RECIPE_FIELDS = mapOf(
            "email" to listOf("email"), "dish" to listOf("dish", "name", "food"),
            "ingredient" to listOf("ingredient"), "quantity" to listOf("quantity", "amount"),
        )
        val PACKAGED_FIELDS = mapOf(
            "email" to listOf("email"), "dish" to listOf("dish", "name", "food"), "quantity" to listOf("quantity", "amount", "portion"),
        ) + NUTRIENT_FIELDS
        val FOOD_FIELDS = mapOf(
            "email" to listOf("email"), "dish" to listOf("dish", "name", "food"),
            "portion" to listOf("portion", "quantity"), "timestamp" to TIMESTAMP,
        )
        val WORKOUT_FIELDS = mapOf(
            "email" to listOf("email"), "name" to listOf("name", "workout", "workoutname"), "timestamp" to TIMESTAMP,
            "calories" to listOf("calories", "calorieskcal", "caloriesburnt", "caloriesburntkcal"), "source" to listOf("source"),
        )
        val PLAN_FIELDS = mapOf(
            "email" to listOf("email"), "name" to listOf("name", "planname"),
            "targetWeight" to listOf("targetweight", "targetweightkg"),
            "restingCalories" to listOf("restingcalories", "restingcalorieskcal"),
            "startDate" to listOf("createdat", "createdatutc", "startdate"),
            "comment" to listOf("comment"), "status" to listOf("status"),
        )
    }
}
