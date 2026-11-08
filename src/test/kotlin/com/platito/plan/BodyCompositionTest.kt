package com.platito.plan

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BodyCompositionTest {
    private val day = DailyBodyCompositionInputs(
        weightKg = 80.0,
        proteinG = 150.0,
        carbohydrateG = 200.0,
        dietaryFatG = 60.0,
        calorieIntake = 1900.0,
        workoutCalories = 400.0,
        restingCalories = 2000.0,
    )

    @Test
    fun `thermic effect weights macros by their TEF rates`() {
        // 150*4*0.2 + 60*9*0.03 + 200*4*0.08
        assertEquals(120.0 + 16.2 + 64.0, BodyComposition.thermicEffectOfFood(150.0, 200.0, 60.0), 1e-9)
    }

    @Test
    fun `a deficit loses fat`() {
        // (1900 - 2000 - 400 - 200.2) / 7.7
        assertEquals(-700.2 / 7.7, BodyComposition.dailyFatMassChangeGrams(day), 1e-9)
    }

    @Test
    fun `dry muscle change returns dry protein mass change`() {
        val deficit = 2000 + 400 + 200.2 - 1900
        val dry = 0.65 * 150 - 0.34 * 80 - 0.0125 * 400 * (1 + 400.0 / 2000) - minOf(0.05 * deficit, 25.0)
        assertEquals(dry, BodyComposition.dailyDryMuscleMassChangeGrams(day), 1e-9)
    }

    @Test
    fun `a day with no resting time yet stays finite`() {
        val result = BodyComposition.dailyDryMuscleMassChangeGrams(day.copy(restingCalories = 0.0))
        assertTrue(result.isFinite())
    }
}
