package com.platito.workout

import com.platito.common.badRequest
import com.platito.common.conflict
import com.platito.common.notFound
import com.platito.common.parseTimestamp
import com.platito.nutrition.DeleteLogRequest
import com.platito.user.UserRecord
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock

data class WorkoutEntry(
    val id: String,
    val name: String,
    val caloriesBurnt: Double,
    val timestamp: String,
    val user: String,
    val source: WorkoutSource,
)

data class WorkoutRequest(val name: String? = null, val caloriesBurnt: Double? = null, val timestamp: String? = null)

fun WorkoutRecord.toEntry(user: UserRecord) =
    WorkoutEntry(id.toString(), name, caloriesBurnt, performedAt.toString(), user.name, source)

@RestController
@RequestMapping("/api/workouts")
class WorkoutController(
    private val repository: WorkoutRepository,
    private val clock: Clock,
) {
    @GetMapping
    fun list(user: UserRecord) =
        mapOf("workouts" to repository.findByUser(user.id).map { it.toEntry(user) }, "isLive" to true)

    @PostMapping
    fun create(user: UserRecord, @RequestBody body: WorkoutRequest): Map<String, Any> {
        val name = body.name?.trim().orEmpty()
        val calories = body.caloriesBurnt ?: 0.0
        if (name.isEmpty() || !(calories.isFinite() && calories > 0)) {
            throw badRequest("Workout name and calories burnt (> 0) are required")
        }
        val performedAt = if (body.timestamp.isNullOrBlank()) clock.instant() else {
            parseTimestamp(body.timestamp) ?: throw badRequest("Invalid timestamp")
        }
        val id = repository.insertDirect(user.id, name, calories, performedAt)
        val entry = WorkoutRecord(id, name, calories, performedAt, WorkoutSource.DIRECT, null).toEntry(user)
        return mapOf("success" to true, "entry" to entry, "isLive" to true)
    }

    @DeleteMapping
    fun delete(user: UserRecord, @RequestBody body: DeleteLogRequest): Map<String, Boolean> {
        val id = body.id?.toLongOrNull()
        val name = body.name?.trim().orEmpty()
        if (id == null || name.isEmpty()) throw badRequest("id and name are required")
        val workout = repository.findById(user.id, id)
            ?: throw notFound("This entry does not belong to this account. Refresh and try again.")
        val expectedTime = parseTimestamp(body.timestamp)
        if (workout.name != name || (expectedTime != null && expectedTime != workout.performedAt)) {
            throw conflict("This entry has changed or does not belong to this account. Refresh and try again.")
        }
        repository.delete(user.id, id)
        return mapOf("success" to true, "isLive" to true)
    }
}
