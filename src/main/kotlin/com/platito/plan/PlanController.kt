package com.platito.plan

import com.platito.common.badRequest
import com.platito.common.notFound
import com.platito.common.parseTimestamp
import com.platito.user.UserRecord
import com.platito.user.UserRepository
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

data class ProfileRequest(
    val weightKg: Double? = null,
    val heightCm: Double? = null,
    val workoutsPerWeek: Double? = null,
    /** Omitted leaves the stored number unchanged; blank clears it. */
    val phoneNumber: String? = null,
    val planName: String? = null,
    val targetWeightKg: Double? = null,
    val restingCalories: Double? = null,
    val startDate: String? = null,
    val comment: String? = null,
    /** Id of the plan to edit alongside the profile, if any. */
    val planRow: Long? = null,
)

data class DeletePlanRequest(val rowNumber: Long? = null, val comment: String? = null)

@RestController
class PlanController(
    private val analytics: PlanAnalyticsService,
    private val plans: PlanRepository,
    private val users: UserRepository,
) {
    @GetMapping("/api/plan")
    fun plan(user: UserRecord) = analytics.analytics(user)

    /** Updates body metrics and, when `planRow` is set, that plan. */
    @PostMapping("/api/profile")
    @Transactional
    fun updateProfile(user: UserRecord, @RequestBody body: ProfileRequest): Map<String, Boolean> {
        val weight = body.weightKg ?: 0.0
        val height = body.heightCm ?: -1.0
        val workoutsPerWeek = body.workoutsPerWeek ?: -1.0
        if (!(weight > 0) || !(height >= 0) || !(workoutsPerWeek >= 0)) throw badRequest("Invalid profile or plan values")
        val planId = body.planRow?.takeIf { it > 0 }
        val planInput = planId?.let { body.toPlanInput() ?: throw badRequest("Invalid profile or plan values") }

        if (planId != null && planInput != null) {
            plans.findById(user.id, planId) ?: throw notFound("Plan does not belong to this account")
            plans.update(user.id, planId, planInput)
        }
        users.updateBodyMetrics(user.id, weight, height, workoutsPerWeek)
        body.phoneNumber?.let { users.updatePhoneNumber(user.id, normalizePhoneNumber(it)) }
        return SUCCESS
    }

    /** Blank clears the number; otherwise digits with an optional leading + and spaces, dashes or brackets. */
    private fun normalizePhoneNumber(raw: String): String? {
        val phone = raw.trim()
        if (phone.isEmpty()) return null
        if (!PHONE_PATTERN.matches(phone) || phone.count(Char::isDigit) !in 6..15) throw badRequest("Enter a valid phone number")
        return phone
    }

    @PutMapping("/api/profile")
    fun addPlan(user: UserRecord, @RequestBody body: ProfileRequest): Map<String, Boolean> {
        plans.insert(user.id, body.toPlanInput() ?: throw badRequest("Invalid plan values"))
        return SUCCESS
    }

    /** Soft delete: the plan stays visible as DELETED but no longer drives tracking. */
    @DeleteMapping("/api/profile")
    fun deletePlan(user: UserRecord, @RequestBody body: DeletePlanRequest): Map<String, Boolean> {
        val id = body.rowNumber ?: throw badRequest("rowNumber is required")
        plans.findById(user.id, id) ?: throw notFound("Plan not found")
        plans.softDelete(user.id, id, body.comment?.trim())
        return SUCCESS
    }

    private fun ProfileRequest.toPlanInput(): PlanInput? {
        val name = planName?.trim().orEmpty()
        val target = targetWeightKg ?: 0.0
        val resting = restingCalories ?: 0.0
        val start = parseTimestamp(startDate)
        if (name.isEmpty() || !(target > 0) || !(resting > 0) || start == null) return null
        return PlanInput(name, target, resting, start, comment?.trim().orEmpty())
    }

    private companion object {
        val SUCCESS = mapOf("success" to true, "isLive" to true)
        val PHONE_PATTERN = Regex("""\+?[0-9 ()\-]{6,24}""")
    }
}
