package com.platito.nutrition

import com.platito.common.badRequest
import com.platito.common.conflict
import com.platito.common.notFound
import com.platito.common.parseTimestamp
import com.platito.user.UserRecord
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock

data class MealLogRequest(
    val dish: String? = null,
    val portion: Double? = null,
    val calories: Double? = null,
    val timestamp: String? = null,
)

data class DeleteLogRequest(val id: String? = null, val dish: String? = null, val name: String? = null, val timestamp: String? = null)

@RestController
@RequestMapping("/api/meals")
class MealController(
    private val catalog: CatalogService,
    private val repository: NutritionRepository,
    private val clock: Clock,
) {
    @GetMapping
    fun list(user: UserRecord) = mapOf("logs" to catalog.mealLogs(user), "isLive" to true)

    @PostMapping
    fun create(user: UserRecord, @RequestBody body: MealLogRequest): Map<String, Any> {
        val dish = body.dish?.trim().orEmpty()
        val portion = body.portion ?: 0.0
        if (dish.isEmpty() || !(portion.isFinite() && portion > 0) || body.calories == null) {
            throw badRequest("Dish name, portion, and calories are required")
        }
        val loggedAt = if (body.timestamp.isNullOrBlank()) clock.instant() else {
            parseTimestamp(body.timestamp) ?: throw badRequest("Invalid timestamp")
        }
        val id = repository.insertMealLog(user.id, dish, portion, loggedAt)
        val entry = catalog.toEntry(MealLogRecord(id, dish, portion, loggedAt), catalog.catalog(user.id).dishes, user)
        return mapOf("success" to true, "entry" to entry, "isLive" to true)
    }

    /** Deletes only when the entry still matches what the client showed, so a stale view cannot remove the wrong meal. */
    @DeleteMapping
    fun delete(user: UserRecord, @RequestBody body: DeleteLogRequest): Map<String, Boolean> {
        val id = body.id?.toLongOrNull()
        val dish = body.dish?.trim().orEmpty()
        if (id == null || dish.isEmpty()) throw badRequest("id and dish are required")
        val log = repository.findMealLog(user.id, id)
            ?: throw notFound("This entry does not belong to this account. Refresh and try again.")
        val expectedTime = parseTimestamp(body.timestamp)
        if (log.dish != dish || (expectedTime != null && expectedTime != log.loggedAt)) {
            throw conflict("This entry has changed or does not belong to this account. Refresh and try again.")
        }
        repository.deleteMealLog(user.id, id)
        return mapOf("success" to true, "isLive" to true)
    }
}
