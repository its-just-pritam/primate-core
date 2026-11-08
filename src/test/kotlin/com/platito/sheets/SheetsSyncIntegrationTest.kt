package com.platito.sheets

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.simple.JdbcClient
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest(
    properties = [
        "platito.auth-secret=integration-test-secret-integration-test",
        "platito.garmin.api-key-encryption-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
        "platito.garmin.sync-enabled=false",
        "platito.sheets.zone=UTC",
    ],
)
@Testcontainers
class SheetsSyncIntegrationTest {
    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }

    @TestConfiguration
    class FakeSheetConfig {
        @Bean
        @Primary
        fun fakeSheet() = FakeSheet()
    }

    class FakeSheet : SheetSource {
        val tabs = mutableMapOf<String, List<List<Any?>>>()
        override fun readTab(tab: String) = tabs[tab]
    }

    @Autowired lateinit var sheet: FakeSheet
    @Autowired lateinit var sync: SheetsSyncService
    @Autowired lateinit var jdbc: JdbcClient

    private val id = UUID.randomUUID().toString().take(8)
    private val email = "sheet-$id@example.com"
    private val oats = "Oats-$id"

    /** A small but complete Sheet for one user; [edit] changes it before it is installed. */
    private fun installSheet(edit: MutableMap<String, MutableList<List<Any?>>>.() -> Unit = {}) {
        val tabs = mutableMapOf<String, MutableList<List<Any?>>>(
            "User" to mutableListOf(
                listOf("Name", "Email", "Password Hash", "First Name", "Last Name", "Weight (kg)", "Height (cm)", "Workouts per week", "Onboarding Step"),
                listOf("Ada $id", email.uppercase(), "aa:bb", "Ada", id, 70, 170, 3, 1),
            ),
            "Ingredients" to mutableListOf(
                listOf("Name", "Quantity", "Unit", "Calories (kcal)", "Protein (g)", "Carbs (g)", "Fat (g)"),
                listOf(oats, 100, "g", 389, 16.9, 66.3, 6.9),
            ),
            "Recipe" to mutableListOf(
                listOf("Email", "Dish", "Ingredient", "Quantity"),
                listOf(email, "Porridge", oats, 0.5),
            ),
            "Packaged" to mutableListOf(
                listOf("Email", "Dish", "Quantity", "Calories"),
                listOf(email, "Protein bar", 1, 200),
            ),
            "Food" to mutableListOf(
                listOf("Email", "Dish", "Portion", "Timestamp"),
                listOf(email, "Porridge", 1, "2024-05-01T08:00:00.000Z"),
                // A date cell: serial 45413.5 is 2024-05-01 12:00 in the configured zone.
                listOf(email, "Protein bar", 2, 45413.5),
            ),
            "Workouts" to mutableListOf(
                listOf("Email", "Name", "Created At", "Calories Burnt", "Source"),
                listOf(email, "Run", "2024-05-01T07:00:00Z", 300, "DIRECT"),
                listOf(email, "Ride", "2024-05-01T09:00:00Z", 500, "Garmin Connect"),
            ),
            "Plan" to mutableListOf(
                listOf("Email", "Name", "Target Weight", "Resting Calories", "Start Date", "Comment", "Status"),
                listOf(email, "Cut", 65, 1800, "2024-05-01", "", "ACTIVE"),
            ),
        )
        tabs.edit()
        sheet.tabs.clear()
        sheet.tabs.putAll(tabs)
    }

    private fun userId() = jdbc.sql("SELECT id FROM app_user WHERE email = :email").param("email", email).query(Long::class.java).single()

    private fun count(sql: String) = jdbc.sql(sql).param("userId", userId()).query(Long::class.java).single()

    @Test
    fun `first run inserts the Sheet and a repeat run changes nothing`() {
        installSheet()
        val first = assertNotNull(sync.run())
        assertEquals(1, first.counts.getValue("app_user").inserted)
        assertEquals(1, first.counts.getValue("recipe").inserted)
        assertEquals(2, first.counts.getValue("meal_log").inserted)
        assertEquals(1, first.counts.getValue("workout").inserted)

        val userId = userId()
        val user = jdbc.sql("SELECT password_hash, onboarding_step FROM app_user WHERE id = :id").param("id", userId)
            .query { rs, _ -> rs.getString(1) to rs.getInt(2) }.single()
        assertEquals("aa:bb" to 5, user)
        assertEquals(
            listOf(Instant.parse("2024-05-01T08:00:00Z"), Instant.parse("2024-05-01T12:00:00Z")),
            jdbc.sql("SELECT logged_at FROM meal_log WHERE user_id = :userId ORDER BY logged_at").param("userId", userId)
                .query { rs, _ -> rs.getTimestamp(1).toInstant() }.list(),
        )
        // The Garmin row is left to the Garmin sync.
        assertEquals(0, count("SELECT count(*) FROM workout WHERE user_id = :userId AND name = 'Ride'"))

        val second = assertNotNull(sync.run())
        for (table in listOf("app_user", "recipe", "packaged_food", "meal_log", "workout", "plan")) {
            assertEquals(0, second.counts.getValue(table).inserted, table)
            assertEquals(0, second.counts.getValue(table).updated, table)
        }
        assertEquals(2, count("SELECT count(*) FROM meal_log WHERE user_id = :userId"))
    }

    @Test
    fun `changed Sheet rows update the database and nothing is deleted`() {
        installSheet()
        sync.run()
        val userId = userId()
        jdbc.sql("UPDATE app_user SET password_hash = 'changed:in-app' WHERE id = :id").param("id", userId).update()
        jdbc.sql("INSERT INTO meal_log (user_id, dish, portion, logged_at) VALUES (:userId, 'App snack', 1, now())").param("userId", userId).update()

        installSheet {
            getValue("User")[1] = listOf("Ada $id", email, "stale:hash", "Ada", id, 68, 170, 4, 1)
            getValue("Recipe").add(listOf(email, "Porridge", oats, 1))
            getValue("Food")[1] = listOf(email, "Porridge", 1.5, "2024-05-01T08:00:00.000Z")
            getValue("Food").removeAt(2)
            getValue("Plan")[1] = listOf(email, "Cut", 64, 1800, "2024-05-01", "harder", "DELETED")
        }
        val report = assertNotNull(sync.run())
        assertEquals(1, report.counts.getValue("app_user").updated)
        assertEquals(1, report.counts.getValue("recipe").updated)
        assertEquals(1, report.counts.getValue("meal_log").updated)
        assertEquals(1, report.counts.getValue("plan").updated)

        val user = jdbc.sql("SELECT weight_kg, password_hash FROM app_user WHERE id = :id").param("id", userId)
            .query { rs, _ -> rs.getDouble(1) to rs.getString(2) }.single()
        assertEquals(68.0 to "changed:in-app", user)
        assertEquals(2, count("SELECT count(*) FROM recipe_ingredient ri JOIN recipe r ON r.id = ri.recipe_id WHERE r.user_id = :userId"))
        assertEquals(1.5, jdbc.sql("SELECT portion FROM meal_log WHERE user_id = :userId AND dish = 'Porridge'").param("userId", userId).query(Double::class.java).single())
        // The meal removed from the Sheet and the one logged in the app are both kept.
        assertEquals(3, count("SELECT count(*) FROM meal_log WHERE user_id = :userId"))
        assertEquals(1, count("SELECT count(*) FROM plan WHERE user_id = :userId AND deleted_at IS NOT NULL AND target_weight_kg = 64"))
    }

    @Test
    fun `rows that cannot be stored are skipped with a warning`() {
        installSheet {
            getValue("Food").add(listOf(email, "Porridge", 1, "not a date"))
            getValue("Food").add(listOf(email, "Porridge", 3, "2024-05-01T08:00:00.000Z"))
            getValue("Workouts").add(listOf("nobody-$id@example.com", "Swim", "2024-05-01T10:00:00Z", 100, "DIRECT"))
            getValue("Recipe").add(listOf(email, "Mystery", "Unobtainium-$id", 1))
        }
        val report = assertNotNull(sync.run())
        assertEquals(2, report.counts.getValue("meal_log").skipped)
        assertEquals(1, report.counts.getValue("workout").skipped)
        assertEquals(1, report.counts.getValue("recipe").skipped)
        assertTrue(report.warnings.any { "Food row 4 has an invalid timestamp" in it })
        assertTrue(report.warnings.any { "Food row 5 repeats row 2" in it })
        assertTrue(report.warnings.any { "nobody-$id@example.com" in it && "no such user" in it })
        assertTrue(report.warnings.any { "Unobtainium-$id" in it })
    }

    @Test
    fun `a missing tab fails the run without changing anything`() {
        installSheet { remove("Plan") }
        assertNull(sync.run())
        assertEquals(0, jdbc.sql("SELECT count(*) FROM app_user WHERE email = :email").param("email", email).query(Long::class.java).single())
        val run = jdbc.sql("SELECT status, error FROM sheets_sync_run ORDER BY id DESC LIMIT 1")
            .query { rs, _ -> rs.getString(1) to rs.getString(2) }.single()
        assertEquals("FAILED" to "The Sheet has no Plan tab.", run)
    }
}
