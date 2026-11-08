package com.platito.nutrition

import com.platito.user.UserRecord
import org.springframework.stereotype.Service
import java.text.Collator
import java.util.Locale

data class IngredientUnit(val baseQuantity: Double, val unit: String)

data class DishCatalog(
    val dishes: Map<String, DishNutrition>,
    val recipes: List<Recipe>,
    val packaged: List<Recipe>,
    val ingredientNames: List<String>,
    val ingredientUnits: Map<String, IngredientUnit>,
)

/** Computes per-serving nutrition for a user's recipes and packaged foods from the ingredient catalog. */
@Service
class CatalogService(private val repository: NutritionRepository) {
    fun catalog(userId: Long): DishCatalog {
        val ingredients = repository.findAllIngredients().associateBy { it.name }
        val dishes = linkedMapOf<String, DishNutrition>()

        for (line in repository.findRecipeLines(userId)) {
            val ingredient = ingredients.getValue(line.ingredientName)
            val qty = line.quantity
            val current = dishes[line.dish] ?: DishNutrition.EMPTY
            dishes[line.dish] = current.copy(
                calories = current.calories + ingredient.calories * qty,
                protein = current.protein + ingredient.protein * qty,
                carbs = current.carbs + ingredient.carbs * qty,
                fats = current.fats + ingredient.fats * qty,
                micros = current.micros + ingredient.micros * qty,
                ingredients = current.ingredients + DishIngredient(
                    name = ingredient.name,
                    quantity = qty,
                    amount = round2(qty * ingredient.baseQuantity),
                    unit = ingredient.unit,
                    calories = jsRound(ingredient.calories * qty),
                    protein = round1(ingredient.protein * qty),
                    carbs = round1(ingredient.carbs * qty),
                    fats = round1(ingredient.fats * qty),
                    micros = ingredient.micros.scaled(qty),
                ),
            )
        }

        val recipes = dishes.entries.mapIndexed { index, (name, n) ->
            Recipe(
                id = "recipe-${index + 1}",
                name = name,
                servingUnit = "1 serving",
                calories = jsRound(n.calories),
                protein = round1(n.protein),
                carbs = round1(n.carbs),
                fats = round1(n.fats),
                micros = n.micros.scaled(1.0),
                ingredients = n.ingredients,
            )
        }

        // Packaged values are stored for `quantity` servings; a recipe with the same name wins.
        val packaged = mutableListOf<Recipe>()
        for (food in repository.findPackagedFoods(userId)) {
            if (food.name in dishes) continue
            val qty = if (food.quantity > 0) food.quantity else 1.0
            val nutrition = DishNutrition(
                calories = food.calories / qty,
                protein = food.protein / qty,
                carbs = food.carbs / qty,
                fats = food.fats / qty,
                micros = food.micros.scaled(1 / qty),
            )
            dishes[food.name] = nutrition
            packaged += Recipe(
                id = "packaged-${packaged.size + 1}",
                name = food.name,
                servingUnit = "1 serving",
                calories = jsRound(nutrition.calories),
                protein = round1(nutrition.protein),
                carbs = round1(nutrition.carbs),
                fats = round1(nutrition.fats),
                isPackaged = true,
                micros = nutrition.micros,
                ingredients = emptyList(),
            )
        }

        val collator = Collator.getInstance(Locale.ENGLISH)
        return DishCatalog(
            dishes = dishes,
            recipes = recipes,
            packaged = packaged,
            ingredientNames = ingredients.keys.sortedWith(collator),
            ingredientUnits = ingredients.mapValues { (_, i) -> IngredientUnit(i.baseQuantity, i.unit) },
        )
    }

    fun mealLogs(user: UserRecord): List<MealLogEntry> {
        val dishes = catalog(user.id).dishes
        return repository.findMealLogs(user.id).map { toEntry(it, dishes, user) }
    }

    fun toEntry(log: MealLogRecord, dishes: Map<String, DishNutrition>, user: UserRecord): MealLogEntry {
        val n = dishes[log.dish] ?: DishNutrition.EMPTY
        val portion = log.portion
        return MealLogEntry(
            id = log.id.toString(),
            dish = log.dish,
            portion = portion,
            calories = jsRound(n.calories * portion),
            protein = round1(n.protein * portion),
            carbs = round1(n.carbs * portion),
            fats = round1(n.fats * portion),
            timestamp = log.loggedAt.toString(),
            user = user.name,
            micros = n.micros.scaled(portion),
            ingredients = n.ingredients.map { it.scaled(portion) },
        )
    }
}
