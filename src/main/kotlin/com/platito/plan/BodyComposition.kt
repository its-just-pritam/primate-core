package com.platito.plan

import kotlin.math.max
import kotlin.math.min

// Port of lib/bodyComposition.ts.

private const val FAT_TISSUE_ENERGY_DENSITY = 7.7
private const val PROTEIN_TEF_RATE = 0.2
private const val FAT_TEF_RATE = 0.03
private const val CARBOHYDRATE_TEF_RATE = 0.08
private const val PROTEIN_FIRST_PASS_OXIDATION = 0.35
private const val BASAL_PROTEIN_LOSS_PER_KG = 0.34
private const val EXERCISE_PROTEIN_OXIDATION_PER_KCAL = 0.0125
private const val DEFICIT_PROTEIN_CATABOLISM_PER_KCAL = 0.05
private const val MAX_DEFICIT_PROTEIN_CATABOLISM_G = 25.0
private const val WET_MUSCLE_MASS_PER_DRY_PROTEIN = 5.0

data class DailyBodyCompositionInputs(
    val weightKg: Double,
    val proteinG: Double,
    val carbohydrateG: Double,
    val dietaryFatG: Double,
    val calorieIntake: Double,
    val workoutCalories: Double,
    val restingCalories: Double,
)

object BodyComposition {
    fun thermicEffectOfFood(proteinG: Double, carbohydrateG: Double, dietaryFatG: Double): Double =
        proteinG * 4 * PROTEIN_TEF_RATE + dietaryFatG * 9 * FAT_TEF_RATE + carbohydrateG * 4 * CARBOHYDRATE_TEF_RATE

    fun dailyFatMassChangeGrams(i: DailyBodyCompositionInputs): Double {
        val tef = thermicEffectOfFood(i.proteinG, i.carbohydrateG, i.dietaryFatG)
        val energyBalance = i.calorieIntake - i.restingCalories - i.workoutCalories - tef
        return energyBalance / FAT_TISSUE_ENERGY_DENSITY
    }

    fun dailyWetMuscleMassChangeGrams(i: DailyBodyCompositionInputs): Double {
        val tef = thermicEffectOfFood(i.proteinG, i.carbohydrateG, i.dietaryFatG)
        val totalEnergyExpenditure = i.restingCalories + i.workoutCalories + tef
        val energyDeficit = max(0.0, totalEnergyExpenditure - i.calorieIntake)
        val availableProtein = (1 - PROTEIN_FIRST_PASS_OXIDATION) * i.proteinG
        val basalProteinLoss = BASAL_PROTEIN_LOSS_PER_KG * i.weightKg
        // A day with no resting time yet (the plan starts right now) would otherwise divide by zero.
        val workoutLoad = if (i.restingCalories > 0) i.workoutCalories / i.restingCalories else 0.0
        val exerciseProteinOxidation = EXERCISE_PROTEIN_OXIDATION_PER_KCAL * i.workoutCalories * (1 + workoutLoad)
        val deficitProteinCatabolism = min(DEFICIT_PROTEIN_CATABOLISM_PER_KCAL * energyDeficit, MAX_DEFICIT_PROTEIN_CATABOLISM_G)
        val dryProteinMassChange = availableProtein - basalProteinLoss - exerciseProteinOxidation - deficitProteinCatabolism
        return dryProteinMassChange * WET_MUSCLE_MASS_PER_DRY_PROTEIN
    }
}
