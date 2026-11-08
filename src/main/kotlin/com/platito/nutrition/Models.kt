package com.platito.nutrition

import kotlin.math.floor

// JSON shapes mirror types/nutrition.ts in the Next.js app.

data class Micronutrients(
    val calcium: Double = 0.0,
    val iron: Double = 0.0,
    val potassium: Double = 0.0,
    val sodium: Double = 0.0,
    val vitaminC: Double = 0.0,
    val vitaminB12: Double = 0.0,
    val vitaminD: Double = 0.0,
) {
    operator fun plus(o: Micronutrients) = Micronutrients(
        calcium + o.calcium, iron + o.iron, potassium + o.potassium, sodium + o.sodium,
        vitaminC + o.vitaminC, vitaminB12 + o.vitaminB12, vitaminD + o.vitaminD,
    )

    operator fun times(factor: Double) = Micronutrients(
        calcium * factor, iron * factor, potassium * factor, sodium * factor,
        vitaminC * factor, vitaminB12 * factor, vitaminD * factor,
    )

    /** Scales and rounds for display, matching the precision the app has always shown. */
    fun scaled(factor: Double) = Micronutrients(
        calcium = round1(calcium * factor),
        iron = round2(iron * factor),
        potassium = round1(potassium * factor),
        sodium = round1(sodium * factor),
        vitaminC = round1(vitaminC * factor),
        vitaminB12 = round2(vitaminB12 * factor),
        vitaminD = round1(vitaminD * factor),
    )

    fun coerceNonNegative() = Micronutrients(
        calcium.nonNegative(), iron.nonNegative(), potassium.nonNegative(), sodium.nonNegative(),
        vitaminC.nonNegative(), vitaminB12.nonNegative(), vitaminD.nonNegative(),
    )

    companion object {
        val EMPTY = Micronutrients()
    }
}

data class DishIngredient(
    val name: String,
    val quantity: Double? = null,
    val amount: Double,
    val unit: String,
    val calories: Double,
    val protein: Double,
    val carbs: Double,
    val fats: Double,
    val micros: Micronutrients,
) {
    /** Scales this ingredient to the portion eaten. */
    fun scaled(portion: Double) = copy(
        amount = round2(amount * portion),
        calories = jsRound(calories * portion),
        protein = round1(protein * portion),
        carbs = round1(carbs * portion),
        fats = round1(fats * portion),
        micros = micros.scaled(portion),
    )
}

data class Recipe(
    val id: String,
    val name: String,
    val servingUnit: String,
    val calories: Double,
    val protein: Double,
    val carbs: Double,
    val fats: Double,
    val isPackaged: Boolean? = null,
    val micros: Micronutrients,
    val ingredients: List<DishIngredient>,
)

data class MealLogEntry(
    val id: String,
    val dish: String,
    val portion: Double,
    val calories: Double,
    val protein: Double,
    val carbs: Double,
    val fats: Double,
    val timestamp: String,
    val user: String,
    val micros: Micronutrients,
    val ingredients: List<DishIngredient>,
)

/** Unrounded nutrition for one serving of a dish. */
data class DishNutrition(
    val calories: Double = 0.0,
    val protein: Double = 0.0,
    val carbs: Double = 0.0,
    val fats: Double = 0.0,
    val micros: Micronutrients = Micronutrients.EMPTY,
    val ingredients: List<DishIngredient> = emptyList(),
) {
    companion object {
        val EMPTY = DishNutrition()
    }
}

/** JavaScript's Math.round: halves round towards positive infinity. */
fun jsRound(value: Double): Double = floor(value + 0.5)
fun round1(value: Double): Double = jsRound(value * 10) / 10
fun round2(value: Double): Double = jsRound(value * 100) / 100

private fun Double.nonNegative() = if (isFinite() && this >= 0) this else 0.0
