package com.platito.nutrition

import com.platito.common.badRequest
import com.platito.common.conflict
import com.platito.common.notFound
import com.platito.user.UserRecord
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class RecipeItemRequest(val ingredient: String? = null, val quantity: Double? = null)

data class RecipeRequest(
    /** The desired name. */
    val dish: String? = null,
    /** The current name, when renaming. */
    val originalDish: String? = null,
    val items: List<RecipeItemRequest?>? = null,
)

data class CatalogResponse(
    val recipes: List<Recipe>,
    val packaged: List<Recipe>,
    val ingredientNames: List<String>,
    val ingredientUnits: Map<String, IngredientUnit>,
    val isLive: Boolean = true,
)

@RestController
@RequestMapping("/api/recipes")
class RecipeController(
    private val catalog: CatalogService,
    private val repository: NutritionRepository,
) {
    @GetMapping
    fun list(user: UserRecord): CatalogResponse {
        val c = catalog.catalog(user.id)
        return CatalogResponse(c.recipes, c.packaged, c.ingredientNames, c.ingredientUnits)
    }

    @PostMapping
    @Transactional
    fun create(user: UserRecord, @RequestBody body: RecipeRequest): Map<String, Boolean> {
        val (dish, lines) = validate(body)
        if (repository.findRecipeId(user.id, dish) != null) throw conflict("A recipe named \"$dish\" already exists")
        repository.replaceRecipeIngredients(repository.insertRecipe(user.id, dish), lines)
        return SUCCESS
    }

    @PutMapping
    @Transactional
    fun update(user: UserRecord, @RequestBody body: RecipeRequest): Map<String, Boolean> {
        val (dish, lines) = validate(body)
        val original = body.originalDish?.trim()?.takeIf { it.isNotEmpty() } ?: dish
        val recipeId = repository.findRecipeId(user.id, original) ?: throw notFound("Recipe \"$original\" was not found")
        if (dish != original) {
            if (repository.findRecipeId(user.id, dish) != null) throw conflict("A recipe named \"$dish\" already exists")
            repository.renameRecipe(recipeId, dish)
            repository.renameMealLogDish(user.id, original, dish)
        }
        repository.replaceRecipeIngredients(recipeId, lines)
        return SUCCESS
    }

    /** Returns the trimmed dish name and (ingredient id, quantity) lines. */
    private fun validate(body: RecipeRequest): Pair<String, List<Pair<Long, Double>>> {
        val dish = body.dish?.trim().orEmpty()
        val items = body.items.orEmpty().filterNotNull()
            .map { (it.ingredient?.trim().orEmpty()) to (it.quantity ?: 0.0) }
            .filter { (name, qty) -> name.isNotEmpty() && qty.isFinite() && qty > 0 }
        if (dish.isEmpty() || items.isEmpty()) {
            throw badRequest("A dish name and at least one ingredient with a quantity are required")
        }
        val ids = repository.findIngredientIdsByName(items.map { it.first }.toSet())
        val unknown = items.map { it.first }.filter { it !in ids }.distinct()
        if (unknown.isNotEmpty()) throw badRequest("Unknown ingredient: ${unknown.joinToString(", ")}")
        return dish to items.map { (name, qty) -> ids.getValue(name) to qty }
    }

    private companion object {
        val SUCCESS = mapOf("success" to true, "isLive" to true)
    }
}
