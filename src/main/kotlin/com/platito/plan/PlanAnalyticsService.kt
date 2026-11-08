package com.platito.plan

import com.platito.nutrition.CatalogService
import com.platito.nutrition.MealLogEntry
import com.platito.nutrition.jsRound
import com.platito.user.UserRecord
import com.platito.workout.WorkoutRecord
import com.platito.workout.WorkoutRepository
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

enum class PlanStatus { ACTIVE, INACTIVE, DELETED }

data class PlanSummary(
    /** The plan id; named for the Sheets row number the client still sends back. */
    val rowNumber: Long,
    val name: String,
    val targetWeight: Double,
    val restingCalories: Double,
    val startDate: String,
    val comment: String,
    val status: PlanStatus,
    val isActive: Boolean,
)

data class PlanAnalytics(
    val planName: String,
    val userName: String,
    val currentWeight: Double,
    val targetWeight: Double,
    val restingCalories: Double,
    val totalCalorieBurnt: Double,
    val totalCalorieConsumed: Double,
    val totalCalorieDeficit: Double,
    val averageCalorieDeficit: Double,
    val totalFatMassChangeGrams: Double,
    val averageDailyFatMassChangeGrams: Double,
    /** Dry muscle-mass change in grams; retains the existing JSON field name for clients. */
    val totalMuscleMassChangeGrams: Double,
    val totalWeightChangeGrams: Double,
    val averageDailyWeightChangeGrams: Double,
    val projectedDate: String,
    val daysElapsed: Int,
    val daysRemaining: Int,
    val progressPercent: Double,
    val workoutCaloriesByDate: Map<String, Double>,
    val heightCm: Double,
    val workoutsPerWeek: Double,
    val phoneNumber: String?,
    val startDate: String,
    val plans: List<PlanSummary>,
    val hasPlan: Boolean,
)

data class PlanAnalyticsResponse(val plan: PlanAnalytics, val activePlans: List<PlanAnalytics>, val isLive: Boolean = true)

private data class DailyTotals(val calories: Double = 0.0, val protein: Double = 0.0, val carbs: Double = 0.0, val fats: Double = 0.0) {
    operator fun plus(meal: MealLogEntry) =
        DailyTotals(calories + meal.calories, protein + meal.protein, carbs + meal.carbs, fats + meal.fats)
}

/**
 * Plan progress modelled from logged meals and workouts since each plan's start. A plan is
 * ACTIVE once its start has passed; every active plan is returned, earliest first.
 */
@Service
class PlanAnalyticsService(
    private val plans: PlanRepository,
    private val workouts: WorkoutRepository,
    private val catalog: CatalogService,
    private val clock: Clock,
) {
    fun analytics(user: UserRecord): PlanAnalyticsResponse {
        val now = clock.instant()
        val all = plans.findByUser(user.id)
        val active = all.filter { it.deletedAt == null && !it.startAt.isAfter(now) }.sortedBy { it.startAt }
        val activeIds = active.map { it.id }.toSet()
        val summaries = all.map { p ->
            PlanSummary(
                rowNumber = p.id,
                name = p.name,
                targetWeight = p.targetWeightKg,
                restingCalories = p.restingCalories,
                startDate = p.startAt.toString(),
                comment = p.comment,
                status = when {
                    p.deletedAt != null -> PlanStatus.DELETED
                    !p.startAt.isAfter(now) -> PlanStatus.ACTIVE
                    else -> PlanStatus.INACTIVE
                },
                isActive = p.id in activeIds,
            )
        }

        if (active.isEmpty()) return PlanAnalyticsResponse(noPlan(user, summaries), emptyList())

        val workoutList = workouts.findByUser(user.id)
        val meals = catalog.mealLogs(user)
        val built = active.map { build(it, user, workoutList, meals, summaries, now) }
        return PlanAnalyticsResponse(built.first(), built)
    }

    private fun build(
        plan: PlanRecord,
        user: UserRecord,
        workoutList: List<WorkoutRecord>,
        meals: List<MealLogEntry>,
        summaries: List<PlanSummary>,
        now: Instant,
    ): PlanAnalytics {
        val start = plan.startAt
        val currentWeight = user.weightKg
        val targetWeight = plan.targetWeightKg
        val restingCalories = plan.restingCalories

        val diffMs = max(0L, Duration.between(start, now).toMillis()).toDouble()
        val daysElapsed = max(1, ceil(diffMs / DAY_MS).toInt())
        // Fractional days drive the calorie maths so the current day accrues proportionally.
        val elapsedDays = diffMs / DAY_MS

        // Only activity since the plan started counts towards the plan.
        val planWorkouts = workoutList.filter { !it.performedAt.isBefore(start) }
        val totalWorkoutBurnt = planWorkouts.sumOf { it.caloriesBurnt }
        val workoutCaloriesByDate = linkedMapOf<String, Double>()
        planWorkouts.forEach {
            val key = utcDateKey(it.performedAt)
            workoutCaloriesByDate[key] = (workoutCaloriesByDate[key] ?: 0.0) + it.caloriesBurnt
        }

        val planMeals = meals.mapNotNull { meal -> Instant.parse(meal.timestamp).takeIf { !it.isBefore(start) }?.let { it to meal } }
        val totalCalorieConsumed = jsRound(planMeals.sumOf { it.second.calories })
        val nutritionByDate = mutableMapOf<String, DailyTotals>()
        planMeals.forEach { (at, meal) ->
            val key = utcDateKey(at)
            nutritionByDate[key] = (nutritionByDate[key] ?: DailyTotals()) + meal
        }

        val totalThermicEffect = nutritionByDate.values.sumOf {
            BodyComposition.thermicEffectOfFood(it.protein, it.carbs, it.fats)
        }
        val totalCalorieBurnt = jsRound(elapsedDays * restingCalories + totalWorkoutBurnt + totalThermicEffect)
        val totalCalorieDeficit = jsRound(totalCalorieBurnt - totalCalorieConsumed)
        val averageCalorieDeficit = jsRound(totalCalorieDeficit / max(1.0, elapsedDays))

        val fatChanges = mutableListOf<Double>()
        val muscleChanges = mutableListOf<Double>()
        var day = start.truncatedTo(ChronoUnit.DAYS)
        val today = now.truncatedTo(ChronoUnit.DAYS)
        while (!day.isAfter(today)) {
            val key = utcDateKey(day)
            val totals = nutritionByDate[key] ?: DailyTotals()
            val nextDay = day.plus(1, ChronoUnit.DAYS)
            val activeDayMs = max(0L, min(nextDay.toEpochMilli(), now.toEpochMilli()) - max(day.toEpochMilli(), start.toEpochMilli()))
            val inputs = DailyBodyCompositionInputs(
                weightKg = currentWeight,
                proteinG = totals.protein,
                carbohydrateG = totals.carbs,
                dietaryFatG = totals.fats,
                calorieIntake = totals.calories,
                workoutCalories = workoutCaloriesByDate[key] ?: 0.0,
                restingCalories = activeDayMs / DAY_MS * restingCalories,
            )
            fatChanges += BodyComposition.dailyFatMassChangeGrams(inputs)
            muscleChanges += BodyComposition.dailyDryMuscleMassChangeGrams(inputs)
            day = nextDay
        }

        val totalFat = fatChanges.sum()
        val averageDailyFat = totalFat / max(1, fatChanges.size)
        val totalMuscle = muscleChanges.sum()
        val totalWeightChange = totalFat + totalMuscle
        val averageDailyWeightChange = totalWeightChange / max(1, muscleChanges.size)

        // Infer the plan-start weight from the current weight and modelled change. Progress
        // follows the target direction: loss for cutting, gain for bulking.
        val planStartWeightGrams = currentWeight * 1000 - totalWeightChange
        val goalChange = targetWeight * 1000 - planStartWeightGrams
        val remainingChange = targetWeight * 1000 - currentWeight * 1000
        val direction = sign(goalChange)
        val progressPercent = if (goalChange == 0.0) 100.0 else min(100.0, jsRound(totalWeightChange / goalChange * 1000) / 10)

        var daysRemaining = 0
        var projectedDate = if (progressPercent >= 100) "Target achieved" else "Need Daily Weight Change"
        if (progressPercent < 100 && remainingChange * direction > 0 && averageDailyWeightChange * direction > 0) {
            daysRemaining = ceil(abs(remainingChange) / abs(averageDailyWeightChange)).toInt()
            projectedDate = PROJECTED_DATE.format(now.plus(daysRemaining.toLong(), ChronoUnit.DAYS).atZone(ZoneOffset.UTC))
        }

        return PlanAnalytics(
            planName = plan.name,
            userName = user.name,
            currentWeight = currentWeight,
            targetWeight = targetWeight,
            restingCalories = restingCalories,
            totalCalorieBurnt = totalCalorieBurnt,
            totalCalorieConsumed = totalCalorieConsumed,
            totalCalorieDeficit = totalCalorieDeficit,
            averageCalorieDeficit = averageCalorieDeficit,
            totalFatMassChangeGrams = totalFat,
            averageDailyFatMassChangeGrams = averageDailyFat,
            totalMuscleMassChangeGrams = totalMuscle,
            totalWeightChangeGrams = totalWeightChange,
            averageDailyWeightChangeGrams = averageDailyWeightChange,
            projectedDate = projectedDate,
            daysElapsed = daysElapsed,
            daysRemaining = daysRemaining,
            progressPercent = progressPercent,
            workoutCaloriesByDate = workoutCaloriesByDate,
            heightCm = user.heightCm,
            workoutsPerWeek = user.workoutsPerWeek,
            phoneNumber = user.phoneNumber,
            startDate = start.toString(),
            plans = summaries,
            hasPlan = true,
        )
    }

    private fun noPlan(user: UserRecord, summaries: List<PlanSummary>) = PlanAnalytics(
        planName = "",
        userName = user.name,
        currentWeight = user.weightKg,
        targetWeight = 0.0,
        restingCalories = 0.0,
        totalCalorieBurnt = 0.0,
        totalCalorieConsumed = 0.0,
        totalCalorieDeficit = 0.0,
        averageCalorieDeficit = 0.0,
        totalFatMassChangeGrams = 0.0,
        averageDailyFatMassChangeGrams = 0.0,
        totalMuscleMassChangeGrams = 0.0,
        totalWeightChangeGrams = 0.0,
        averageDailyWeightChangeGrams = 0.0,
        projectedDate = "",
        daysElapsed = 0,
        daysRemaining = 0,
        progressPercent = 0.0,
        workoutCaloriesByDate = emptyMap(),
        heightCm = user.heightCm,
        workoutsPerWeek = user.workoutsPerWeek,
        phoneNumber = user.phoneNumber,
        startDate = "",
        plans = summaries,
        hasPlan = false,
    )

    private fun utcDateKey(instant: Instant): String = LocalDate.ofInstant(instant, ZoneOffset.UTC).toString()

    private companion object {
        const val DAY_MS = 86_400_000.0
        val PROJECTED_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)
    }
}
