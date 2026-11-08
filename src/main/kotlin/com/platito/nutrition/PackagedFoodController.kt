package com.platito.nutrition

import com.platito.common.badRequest
import com.platito.common.conflict
import com.platito.common.notFound
import com.platito.user.UserRecord
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class PackagedFoodRequest(
    val dish: String? = null,
    val originalDish: String? = null,
    val quantity: Double? = null,
    val calories: Double? = null,
    val protein: Double? = null,
    val carbs: Double? = null,
    val fats: Double? = null,
    val micros: Micronutrients? = null,
)

@RestController
@RequestMapping("/api/packaged")
class PackagedFoodController(private val repository: NutritionRepository) {
    @PostMapping
    @Transactional
    fun create(user: UserRecord, @RequestBody body: PackagedFoodRequest): Map<String, Boolean> {
        val food = body.toInput()
        if (repository.findPackagedFoodId(user.id, food.name) != null) {
            throw conflict("A packaged food named \"${food.name}\" already exists")
        }
        repository.insertPackagedFood(user.id, food)
        return SUCCESS
    }

    @PutMapping
    @Transactional
    fun update(user: UserRecord, @RequestBody body: PackagedFoodRequest): Map<String, Boolean> {
        val food = body.toInput()
        val original = body.originalDish?.trim()?.takeIf { it.isNotEmpty() } ?: food.name
        val id = repository.findPackagedFoodId(user.id, original)
            ?: throw notFound("Packaged item \"$original\" was not found")
        if (food.name != original) {
            if (repository.findPackagedFoodId(user.id, food.name) != null) {
                throw conflict("A packaged food named \"${food.name}\" already exists")
            }
            repository.renameMealLogDish(user.id, original, food.name)
        }
        repository.updatePackagedFood(id, food)
        return SUCCESS
    }

    private fun PackagedFoodRequest.toInput(): PackagedFoodInput {
        val name = dish?.trim().orEmpty()
        if (name.isEmpty()) throw badRequest("A name is required")
        return PackagedFoodInput(
            name = name,
            quantity = quantity?.takeIf { it.isFinite() && it > 0 } ?: 1.0,
            calories = calories.nonNegative(),
            protein = protein.nonNegative(),
            carbs = carbs.nonNegative(),
            fats = fats.nonNegative(),
            micros = (micros ?: Micronutrients.EMPTY).coerceNonNegative(),
        )
    }

    private fun Double?.nonNegative() = this?.takeIf { it.isFinite() && it >= 0 } ?: 0.0

    private companion object {
        val SUCCESS = mapOf("success" to true, "isLive" to true)
    }
}
