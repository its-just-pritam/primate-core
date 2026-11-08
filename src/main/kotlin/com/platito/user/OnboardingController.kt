package com.platito.user

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import com.platito.auth.Public
import com.platito.common.badRequest

data class OnboardingRequest(val step: Int? = null)

/** Onboarding progress, stored as the number of completed steps: 0 (none) to [ONBOARDING_STEPS] (finished). */
@RestController
@RequestMapping("/api/onboarding")
class OnboardingController(private val users: UserRepository) {
    @GetMapping
    fun step(user: UserRecord) = mapOf("step" to user.onboardingStep.coerceIn(0, ONBOARDING_STEPS))

    /** Records progress; a missing step finishes onboarding. Progress never moves backwards. */
    @PostMapping
    fun complete(user: UserRecord, @RequestBody(required = false) body: OnboardingRequest?): Map<String, Any> {
        val step = body?.step ?: ONBOARDING_STEPS
        if (step !in 1..ONBOARDING_STEPS) throw badRequest("Onboarding step must be between 1 and $ONBOARDING_STEPS.")
        return mapOf("success" to true, "step" to users.advanceOnboardingStep(user.id, step))
    }

    private companion object {
        const val ONBOARDING_STEPS = 5
    }
}

@RestController
class StatusController(private val jdbc: JdbcClient) {
    @Public
    @GetMapping("/api/status")
    fun status(): Map<String, Any> {
        val connected = runCatching { jdbc.sql("SELECT 1").query(Int::class.java).single() == 1 }.getOrDefault(false)
        return mapOf("connected" to connected, "database" to "postgres")
    }
}
