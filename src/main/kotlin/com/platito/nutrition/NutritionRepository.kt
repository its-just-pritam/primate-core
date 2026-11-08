package com.platito.nutrition

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

data class IngredientRecord(
    val id: Long,
    val name: String,
    val baseQuantity: Double,
    val unit: String,
    val calories: Double,
    val protein: Double,
    val carbs: Double,
    val fats: Double,
    val micros: Micronutrients,
)

data class RecipeLine(val recipeId: Long, val dish: String, val ingredientName: String, val quantity: Double)

data class PackagedFoodRecord(
    val id: Long,
    val name: String,
    val quantity: Double,
    val calories: Double,
    val protein: Double,
    val carbs: Double,
    val fats: Double,
    val micros: Micronutrients,
)

data class MealLogRecord(val id: Long, val dish: String, val portion: Double, val loggedAt: Instant)

data class PackagedFoodInput(
    val name: String,
    val quantity: Double,
    val calories: Double,
    val protein: Double,
    val carbs: Double,
    val fats: Double,
    val micros: Micronutrients,
)

@Repository
class NutritionRepository(private val jdbc: JdbcClient) {
    fun findAllIngredients(): List<IngredientRecord> =
        jdbc.sql("SELECT * FROM ingredient ORDER BY name").query { rs, _ ->
            IngredientRecord(
                id = rs.getLong("id"),
                name = rs.getString("name"),
                baseQuantity = rs.getDouble("base_quantity"),
                unit = rs.getString("unit"),
                calories = rs.getDouble("calories"),
                protein = rs.getDouble("protein"),
                carbs = rs.getDouble("carbs"),
                fats = rs.getDouble("fats"),
                micros = rs.micros(),
            )
        }.list()

    fun findIngredientIdsByName(names: Collection<String>): Map<String, Long> {
        if (names.isEmpty()) return emptyMap()
        return jdbc.sql("SELECT id, name FROM ingredient WHERE name IN (:names)")
            .param("names", names)
            .query { rs, _ -> rs.getString("name") to rs.getLong("id") }
            .list().toMap()
    }

    /** Recipe lines for a user in creation order, so dishes keep a stable order. */
    fun findRecipeLines(userId: Long): List<RecipeLine> =
        jdbc.sql(
            """
            SELECT r.id AS recipe_id, r.name AS dish, i.name AS ingredient, ri.quantity
            FROM recipe r
            JOIN recipe_ingredient ri ON ri.recipe_id = r.id
            JOIN ingredient i ON i.id = ri.ingredient_id
            WHERE r.user_id = :userId
            ORDER BY r.id, ri.position
            """.trimIndent(),
        ).param("userId", userId).query { rs, _ ->
            RecipeLine(rs.getLong("recipe_id"), rs.getString("dish"), rs.getString("ingredient"), rs.getDouble("quantity"))
        }.list()

    fun findRecipeId(userId: Long, name: String): Long? =
        jdbc.sql("SELECT id FROM recipe WHERE user_id = :userId AND name = :name")
            .param("userId", userId).param("name", name)
            .query(Long::class.java).optional().orElse(null)

    fun insertRecipe(userId: Long, name: String): Long =
        jdbc.sql("INSERT INTO recipe (user_id, name) VALUES (:userId, :name) RETURNING id")
            .param("userId", userId).param("name", name)
            .query(Long::class.java).single()

    fun renameRecipe(recipeId: Long, name: String) {
        jdbc.sql("UPDATE recipe SET name = :name WHERE id = :id").param("name", name).param("id", recipeId).update()
    }

    fun replaceRecipeIngredients(recipeId: Long, lines: List<Pair<Long, Double>>) {
        jdbc.sql("DELETE FROM recipe_ingredient WHERE recipe_id = :id").param("id", recipeId).update()
        lines.forEachIndexed { position, (ingredientId, quantity) ->
            jdbc.sql(
                """
                INSERT INTO recipe_ingredient (recipe_id, ingredient_id, quantity, position)
                VALUES (:recipeId, :ingredientId, :quantity, :position)
                """.trimIndent(),
            )
                .param("recipeId", recipeId).param("ingredientId", ingredientId)
                .param("quantity", quantity).param("position", position)
                .update()
        }
    }

    fun findPackagedFoods(userId: Long): List<PackagedFoodRecord> =
        jdbc.sql("SELECT * FROM packaged_food WHERE user_id = :userId ORDER BY id")
            .param("userId", userId)
            .query { rs, _ ->
                PackagedFoodRecord(
                    id = rs.getLong("id"),
                    name = rs.getString("name"),
                    quantity = rs.getDouble("quantity"),
                    calories = rs.getDouble("calories"),
                    protein = rs.getDouble("protein"),
                    carbs = rs.getDouble("carbs"),
                    fats = rs.getDouble("fats"),
                    micros = rs.micros(),
                )
            }.list()

    fun findPackagedFoodId(userId: Long, name: String): Long? =
        jdbc.sql("SELECT id FROM packaged_food WHERE user_id = :userId AND name = :name")
            .param("userId", userId).param("name", name)
            .query(Long::class.java).optional().orElse(null)

    fun insertPackagedFood(userId: Long, food: PackagedFoodInput) {
        jdbc.sql(
            """
            INSERT INTO packaged_food (user_id, name, quantity, calories, protein, carbs, fats,
                calcium, iron, potassium, sodium, vitamin_c, vitamin_b12, vitamin_d)
            VALUES (:userId, :name, :quantity, :calories, :protein, :carbs, :fats,
                :calcium, :iron, :potassium, :sodium, :vitaminC, :vitaminB12, :vitaminD)
            """.trimIndent(),
        ).param("userId", userId).packagedParams(food).update()
    }

    fun updatePackagedFood(id: Long, food: PackagedFoodInput) {
        jdbc.sql(
            """
            UPDATE packaged_food SET name = :name, quantity = :quantity, calories = :calories,
                protein = :protein, carbs = :carbs, fats = :fats, calcium = :calcium, iron = :iron,
                potassium = :potassium, sodium = :sodium, vitamin_c = :vitaminC,
                vitamin_b12 = :vitaminB12, vitamin_d = :vitaminD
            WHERE id = :id
            """.trimIndent(),
        ).param("id", id).packagedParams(food).update()
    }

    fun findMealLogs(userId: Long): List<MealLogRecord> =
        jdbc.sql("SELECT id, dish, portion, logged_at FROM meal_log WHERE user_id = :userId ORDER BY logged_at, id")
            .param("userId", userId)
            .query { rs, _ ->
                MealLogRecord(rs.getLong("id"), rs.getString("dish"), rs.getDouble("portion"), rs.getTimestamp("logged_at").toInstant())
            }.list()

    fun findMealLog(userId: Long, id: Long): MealLogRecord? =
        jdbc.sql("SELECT id, dish, portion, logged_at FROM meal_log WHERE user_id = :userId AND id = :id")
            .param("userId", userId).param("id", id)
            .query { rs, _ ->
                MealLogRecord(rs.getLong("id"), rs.getString("dish"), rs.getDouble("portion"), rs.getTimestamp("logged_at").toInstant())
            }.optional().orElse(null)

    fun insertMealLog(userId: Long, dish: String, portion: Double, loggedAt: Instant): Long =
        jdbc.sql("INSERT INTO meal_log (user_id, dish, portion, logged_at) VALUES (:userId, :dish, :portion, :loggedAt) RETURNING id")
            .param("userId", userId).param("dish", dish).param("portion", portion)
            .param("loggedAt", Timestamp.from(loggedAt))
            .query(Long::class.java).single()

    fun deleteMealLog(userId: Long, id: Long): Int =
        jdbc.sql("DELETE FROM meal_log WHERE user_id = :userId AND id = :id")
            .param("userId", userId).param("id", id).update()

    /** Keeps a user's meal history linked to a dish after it is renamed. */
    fun renameMealLogDish(userId: Long, from: String, to: String) {
        jdbc.sql("UPDATE meal_log SET dish = :to WHERE user_id = :userId AND dish = :from")
            .param("to", to).param("userId", userId).param("from", from).update()
    }

    private fun JdbcClient.StatementSpec.packagedParams(food: PackagedFoodInput) = this
        .param("name", food.name).param("quantity", food.quantity)
        .param("calories", food.calories).param("protein", food.protein)
        .param("carbs", food.carbs).param("fats", food.fats)
        .param("calcium", food.micros.calcium).param("iron", food.micros.iron)
        .param("potassium", food.micros.potassium).param("sodium", food.micros.sodium)
        .param("vitaminC", food.micros.vitaminC).param("vitaminB12", food.micros.vitaminB12)
        .param("vitaminD", food.micros.vitaminD)

    private fun ResultSet.micros() = Micronutrients(
        calcium = getDouble("calcium"),
        iron = getDouble("iron"),
        potassium = getDouble("potassium"),
        sodium = getDouble("sodium"),
        vitaminC = getDouble("vitamin_c"),
        vitaminB12 = getDouble("vitamin_b12"),
        vitaminD = getDouble("vitamin_d"),
    )
}
