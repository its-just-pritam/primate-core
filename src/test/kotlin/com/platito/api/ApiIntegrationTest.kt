package com.platito.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.platito.garmin.GarminApi
import com.platito.garmin.GarminSyncService
import liquibase.Contexts
import liquibase.LabelExpression
import liquibase.Liquibase
import liquibase.database.DatabaseFactory
import liquibase.database.jvm.JdbcConnection
import liquibase.resource.ClassLoaderResourceAccessor
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.sql.DriverManager
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val SECRET = "integration-test-secret-integration-test"

@SpringBootTest(
    properties = [
        "platito.auth-secret=$SECRET",
        "platito.garmin.api-key-encryption-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
        "platito.garmin.sync-enabled=false",
        "platito.garmin.backfill-days=2",
    ],
)
@AutoConfigureMockMvc
@Testcontainers
class ApiIntegrationTest {
    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }

    @TestConfiguration
    class FakeGarminConfig {
        @Bean
        @Primary
        fun fakeGarminApi() = FakeGarminApi()

        /** Runs the post-connect backfill inline so tests are deterministic. */
        @Bean
        @Primary
        fun directExecutor(): ExecutorService = DirectExecutorService()
    }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var garmin: FakeGarminApi
    @Autowired lateinit var garminSync: GarminSyncService

    private val today = LocalDate.now(ZoneOffset.UTC)

    @BeforeEach
    fun seedIngredients() {
        garmin.reset()
        jdbc.sql(
            """
            INSERT INTO ingredient (name, base_quantity, unit, calories, protein, carbs, fats, calcium)
            VALUES ('Oats', 100, 'g', 389, 16.9, 66.3, 6.9, 54), ('Milk', 100, 'ml', 42, 3.4, 5, 1, 120)
            ON CONFLICT (name) DO NOTHING
            """.trimIndent(),
        ).update()
    }

    @Test
    fun `signup, login and authentication`() {
        val email = "user-${UUID.randomUUID()}@example.com"
        val signup = call(HttpMethod.POST, "/api/auth/signup", null, mapOf("firstName" to "Ada", "lastName" to uniqueName(), "email" to email, "password" to "a-strong-password"))
        assertEquals(200, signup.status)
        assertEquals(email, signup.body["user"]["username"].asText())

        assertEquals(409, call(HttpMethod.POST, "/api/auth/signup", null, mapOf("firstName" to "Other", "lastName" to "Person", "email" to email, "password" to "a-strong-password")).status)
        assertEquals(200, call(HttpMethod.POST, "/api/auth/login", null, mapOf("username" to email.uppercase(), "password" to "a-strong-password")).status)
        assertEquals(401, call(HttpMethod.POST, "/api/auth/login", null, mapOf("username" to email, "password" to "wrong-password!!")).status)

        assertEquals(401, call(HttpMethod.GET, "/api/meals", null).status)
        assertEquals(401, call(HttpMethod.GET, "/api/meals", "not-a-token").status)
        val token = tokenFor(signup.body["user"]["name"].asText(), email)
        assertEquals(200, call(HttpMethod.GET, "/api/onboarding", token).status)
    }

    @Test
    fun `onboarding progress is resumable and never moves backwards`() {
        val token = newUser()
        fun stored() = call(HttpMethod.GET, "/api/onboarding", token).body["step"].asInt()
        assertEquals(0, stored())

        assertEquals(1, call(HttpMethod.POST, "/api/onboarding", token, mapOf("step" to 1)).body["step"].asInt())
        assertEquals(2, call(HttpMethod.POST, "/api/onboarding", token, mapOf("step" to 2)).body["step"].asInt())
        assertEquals(2, stored())

        // A late or repeated save cannot erase progress.
        assertEquals(2, call(HttpMethod.POST, "/api/onboarding", token, mapOf("step" to 1)).body["step"].asInt())
        assertEquals(2, stored())

        for (invalid in listOf(0, 6)) {
            val response = call(HttpMethod.POST, "/api/onboarding", token, mapOf("step" to invalid))
            assertEquals(400, response.status)
            assertEquals("Onboarding step must be between 1 and 5.", response.body["error"].asText())
        }
        assertEquals(2, stored())

        // A body-less save finishes onboarding, as before.
        val finished = call(HttpMethod.POST, "/api/onboarding", token)
        assertTrue(finished.body["success"].asBoolean())
        assertEquals(5, finished.body["step"].asInt())
        assertEquals(5, stored())
    }

    @Test
    fun `onboarding migration marks previously finished users as having completed every step`() {
        val schema = "migration_${UUID.randomUUID().toString().replace("-", "").take(12)}"
        jdbc.sql("CREATE SCHEMA $schema").update()
        try {
            // A dedicated connection, so the search path does not leak into the pool.
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { connection ->
                connection.createStatement().use { it.execute("SET search_path TO $schema") }
                val database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(JdbcConnection(connection))
                database.defaultSchemaName = schema
                val liquibase = Liquibase("db/changelog/db.changelog-master.yaml", ClassLoaderResourceAccessor(), database)

                // Apply everything before 006, seed users under the old 0/1 meaning, then apply 006.
                val pending = liquibase.listUnrunChangeSets(Contexts(), LabelExpression())
                liquibase.update(pending.indexOfFirst { it.id == "006-onboarding-progress" }, Contexts(), LabelExpression())
                connection.createStatement().use {
                    it.execute("INSERT INTO app_user (email, name, onboarding_step) VALUES ('new@example.com', 'New', 0), ('done@example.com', 'Done', 1)")
                }
                liquibase.update(Contexts(), LabelExpression())

                val steps = connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT email, onboarding_step FROM app_user").use { rs ->
                        generateSequence { if (rs.next()) rs.getString(1) to rs.getInt(2) else null }.toMap()
                    }
                }
                assertEquals(mapOf("new@example.com" to 0, "done@example.com" to 5), steps)
            }
        } finally {
            jdbc.sql("DROP SCHEMA $schema CASCADE").update()
        }
    }

    @Test
    fun `recipes, packaged foods and meal logs compute nutrition`() {
        val token = newUser()
        assertEquals(200, call(HttpMethod.POST, "/api/recipes", token, recipe("Porridge", "Oats" to 0.5, "Milk" to 2.0)).status)
        assertEquals(400, call(HttpMethod.POST, "/api/recipes", token, recipe("Mystery", "Unobtainium" to 1.0)).status)
        assertEquals(409, call(HttpMethod.POST, "/api/recipes", token, recipe("Porridge", "Oats" to 1.0)).status)
        call(HttpMethod.POST, "/api/packaged", token, mapOf("dish" to "Protein Bar", "quantity" to 2, "calories" to 400, "protein" to 40, "carbs" to 30, "fats" to 10, "micros" to mapOf("sodium" to 300)))

        val catalog = call(HttpMethod.GET, "/api/recipes", token).body
        val porridge = catalog["recipes"][0]
        assertEquals("Porridge", porridge["name"].asText())
        assertEquals(279.0, porridge["calories"].asDouble()) // 389*0.5 + 42*2 = 278.5
        assertEquals(15.3, porridge["protein"].asDouble()) // 8.45 + 6.8 = 15.25
        assertEquals(2, porridge["ingredients"].size())
        assertEquals(50.0, porridge["ingredients"][0]["amount"].asDouble())
        assertEquals(200.0, catalog["packaged"][0]["calories"].asDouble())
        assertEquals(150.0, catalog["packaged"][0]["micros"]["sodium"].asDouble())
        // The seeded catalog (db/changelog/data/ingredient-catalog.csv) is always present too.
        val ingredientNames = catalog["ingredientNames"].map { it.asText() }
        assertTrue(ingredientNames.containsAll(listOf("Milk", "Oats", "Toor Dal (Split Pigeon Peas)", "Ghee")))
        assertTrue(ingredientNames.size >= 102)

        val logged = call(HttpMethod.POST, "/api/meals", token, mapOf("dish" to "Porridge", "portion" to 2, "calories" to 557, "timestamp" to "2026-10-20T08:00:00.000Z"))
        assertEquals(557.0, logged.body["entry"]["calories"].asDouble())
        call(HttpMethod.POST, "/api/meals", token, mapOf("dish" to "Protein Bar", "portion" to 1, "calories" to 200))

        // Renaming a recipe keeps its meal history linked.
        call(HttpMethod.PUT, "/api/recipes", token, recipe("Oat Porridge", "Oats" to 0.5, "Milk" to 2.0) + ("originalDish" to "Porridge"))
        val logs = call(HttpMethod.GET, "/api/meals", token).body["logs"]
        assertEquals(listOf("Oat Porridge", "Protein Bar"), logs.map { it["dish"].asText() })
        assertEquals(557.0, logs[0]["calories"].asDouble())
        assertEquals(30.5, logs[0]["protein"].asDouble())

        val first = logs[0]
        assertEquals(409, call(HttpMethod.DELETE, "/api/meals", token, mapOf("id" to first["id"].asText(), "dish" to "Something else")).status)
        assertEquals(200, call(HttpMethod.DELETE, "/api/meals", token, mapOf("id" to first["id"].asText(), "dish" to first["dish"].asText(), "timestamp" to first["timestamp"].asText())).status)
        assertEquals(1, call(HttpMethod.GET, "/api/meals", token).body["logs"].size())

        // Another account cannot see or delete this user's data.
        val other = newUser()
        assertEquals(0, call(HttpMethod.GET, "/api/meals", other).body["logs"].size())
        assertEquals(404, call(HttpMethod.DELETE, "/api/meals", other, mapOf("id" to logs[1]["id"].asText(), "dish" to "Protein Bar")).status)
    }

    @Test
    fun `plans drive analytics and soft delete`() {
        val token = newUser()
        call(HttpMethod.POST, "/api/profile", token, mapOf("weightKg" to 80, "heightCm" to 180, "workoutsPerWeek" to 3))
        val noPlan = call(HttpMethod.GET, "/api/plan", token).body
        assertFalse(noPlan["plan"]["hasPlan"].asBoolean())
        assertEquals(80.0, noPlan["plan"]["currentWeight"].asDouble())
        assertFalse(noPlan["plan"].has("phoneNumber"))

        // Phone number: set, kept when omitted, rejected when invalid, cleared when blank.
        val metrics = mapOf("weightKg" to 80, "heightCm" to 180, "workoutsPerWeek" to 3)
        assertEquals(200, call(HttpMethod.POST, "/api/profile", token, metrics + ("phoneNumber" to " +91 98765-43210 ")).status)
        assertEquals("+91 98765-43210", call(HttpMethod.GET, "/api/plan", token).body["plan"]["phoneNumber"].asText())
        call(HttpMethod.POST, "/api/profile", token, metrics)
        assertEquals("+91 98765-43210", call(HttpMethod.GET, "/api/plan", token).body["plan"]["phoneNumber"].asText())
        val invalid = call(HttpMethod.POST, "/api/profile", token, metrics + ("phoneNumber" to "call me"))
        assertEquals(400, invalid.status)
        assertEquals("Enter a valid phone number", invalid.body["error"].asText())
        call(HttpMethod.POST, "/api/profile", token, metrics + ("phoneNumber" to ""))
        assertFalse(call(HttpMethod.GET, "/api/plan", token).body["plan"].has("phoneNumber"))
        call(HttpMethod.POST, "/api/profile", token, metrics + ("phoneNumber" to "+91 98765 43210"))

        val start = Instant.now().minus(3, ChronoUnit.DAYS).toString()
        assertEquals(200, call(HttpMethod.PUT, "/api/profile", token, mapOf("planName" to "Cut", "targetWeightKg" to 75, "restingCalories" to 2000, "startDate" to start)).status)
        call(HttpMethod.PUT, "/api/profile", token, mapOf("planName" to "Future", "targetWeightKg" to 70, "restingCalories" to 2000, "startDate" to Instant.now().plus(5, ChronoUnit.DAYS).toString()))
        assertEquals(400, call(HttpMethod.PUT, "/api/profile", token, mapOf("planName" to "Bad", "targetWeightKg" to 0, "restingCalories" to 2000, "startDate" to start)).status)
        call(HttpMethod.POST, "/api/workouts", token, mapOf("name" to "Run", "caloriesBurnt" to 500))

        val analytics = call(HttpMethod.GET, "/api/plan", token).body
        val plan = analytics["plan"]
        assertTrue(plan["hasPlan"].asBoolean())
        assertEquals("Cut", plan["planName"].asText())
        assertEquals("+91 98765 43210", plan["phoneNumber"].asText())
        assertEquals(1, analytics["activePlans"].size())
        assertEquals(4, plan["daysElapsed"].asInt())
        // Three days of resting burn plus the workout; nothing eaten.
        assertEquals(6500.0, plan["totalCalorieBurnt"].asDouble(), 1.0)
        assertEquals(500.0, plan["workoutCaloriesByDate"][today.toString()].asDouble())
        assertEquals(listOf("ACTIVE", "INACTIVE"), plan["plans"].map { it["status"].asText() })

        val planId = plan["plans"][0]["rowNumber"].asLong()
        call(HttpMethod.DELETE, "/api/profile", token, mapOf("rowNumber" to planId, "comment" to "done"))
        val after = call(HttpMethod.GET, "/api/plan", token).body["plan"]
        assertFalse(after["hasPlan"].asBoolean())
        assertEquals("DELETED", after["plans"][0]["status"].asText())
        assertEquals("done", after["plans"][0]["comment"].asText())
    }

    @Test
    fun `garmin connect, activity reconciliation and stored summaries`() {
        val token = newUser()
        assertEquals(409, call(HttpMethod.POST, "/api/garmin/test", token, mapOf("date" to today.toString())).status)
        garmin.activities[today] = mutableListOf(activity(1, "Morning Run", 300.0), activity(2, "Evening Ride", 450.0))

        val connected = call(HttpMethod.POST, "/api/garmin/integration", token, mapOf("apiKey" to "ApiKey secret-garmin-key-1234"))
        assertEquals(200, connected.status)
        assertEquals("secret-garmin-key-1234", garmin.exchangedKeys.single())
        val status = call(HttpMethod.GET, "/api/garmin/integration", token).body
        assertTrue(status["connected"].asBoolean())
        assertEquals("secr••••••1234", status["apiKeyMasked"].asText())
        assertEquals("SUCCESS", status["lastSyncStatus"].asText())
        val ciphertext = jdbc.sql("SELECT api_key_ciphertext FROM garmin_integration").query(String::class.java).list()
        assertTrue(ciphertext.none { "secret-garmin-key" in it })

        // The backfill imported today's activities; a direct workout is left alone by reconciliation.
        call(HttpMethod.POST, "/api/workouts", token, mapOf("name" to "Pushups", "caloriesBurnt" to 50))
        assertEquals(setOf("Morning Run", "Evening Ride", "Pushups"), workoutNames(token))

        garmin.activities[today] = mutableListOf(activity(1, "Morning Run (edited)", 320.0))
        val sync = call(HttpMethod.POST, "/api/garmin/activities", token, mapOf("date" to today.toString(), "timeZone" to "Europe/Berlin")).body
        assertEquals(0, sync["imported"].asInt())
        assertEquals(1, sync["deleted"].asInt())
        assertEquals(setOf("Morning Run (edited)", "Pushups"), workoutNames(token))

        // Summaries come from the database; the backfill already stored the last two days.
        val callsBefore = garmin.summaryCalls
        val daily = call(HttpMethod.POST, "/api/garmin/test", token, mapOf("date" to today.minusDays(1).toString())).body
        assertEquals(today.minusDays(1).toString(), daily["data"][0]["calendarDate"].asText())
        assertEquals("Forerunner 965", daily["deviceName"].asText())
        assertEquals(callsBefore, garmin.summaryCalls)

        val weekly = call(HttpMethod.POST, "/api/garmin/weekly", token, mapOf("date" to today.toString())).body
        assertEquals(7, weekly["data"].size())
        assertEquals(callsBefore + 5, garmin.summaryCalls) // only the five days not yet stored
        assertEquals(7, call(HttpMethod.POST, "/api/garmin/sleep", token, mapOf("date" to today.toString())).body["data"].size())

        assertEquals(200, call(HttpMethod.DELETE, "/api/garmin/integration", token).status)
        assertFalse(call(HttpMethod.GET, "/api/garmin/integration", token).body["connected"].asBoolean())
        assertEquals(0L, jdbc.sql("SELECT count(*) FROM garmin_daily_metric").query(Long::class.java).single())
    }

    @Test
    fun `garmin failures are reported without losing stored data`() {
        val token = newUser()
        call(HttpMethod.POST, "/api/garmin/integration", token, mapOf("apiKey" to "another-key-5678"))
        garmin.failActivities = true
        val response = call(HttpMethod.POST, "/api/garmin/activities", token, mapOf("date" to today.toString(), "timeZone" to "UTC"))
        assertEquals(502, response.status)
        assertTrue(response.body["error"].asText().startsWith("Garmin "))

        val userId = jdbc.sql("SELECT user_id FROM garmin_integration ORDER BY connected_at DESC LIMIT 1").query(Long::class.java).single()
        garminSync.syncUser(userId)
        assertEquals("FAILED", call(HttpMethod.GET, "/api/garmin/integration", token).body["lastSyncStatus"].asText())

        garmin.failToken = true
        assertEquals(502, call(HttpMethod.POST, "/api/garmin/integration", token, mapOf("apiKey" to "bad-key")).status)
    }

    // --- helpers ---

    private data class Response(val status: Int, val body: JsonNode)

    private fun call(method: HttpMethod, path: String, token: String?, body: Any? = null): Response {
        val builder = request(method, path).contentType(MediaType.APPLICATION_JSON)
        token?.let { builder.header("Authorization", "Bearer $it") }
        body?.let { builder.content(mapper.writeValueAsString(it)) }
        val result = mvc.perform(builder).andReturn().response
        val text = result.contentAsString
        return Response(result.status, if (text.isBlank()) JsonNodeFactory.instance.objectNode() else mapper.readTree(text))
    }

    private fun newUser(): String {
        val email = "user-${UUID.randomUUID()}@example.com"
        val response = call(HttpMethod.POST, "/api/auth/signup", null, mapOf("firstName" to "Test", "lastName" to uniqueName(), "email" to email, "password" to "a-strong-password"))
        assertEquals(200, response.status, response.body.toString())
        return tokenFor(response.body["user"]["name"].asText(), email)
    }

    private fun uniqueName() = "User ${UUID.randomUUID().toString().take(8)}"

    /** Signs a token exactly like lib/authSession.ts does. */
    private fun tokenFor(name: String, email: String): String {
        val payload = mapper.writeValueAsBytes(mapOf("name" to name, "username" to email, "exp" to Instant.now().epochSecond + 3600))
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(SECRET.toByteArray(), "HmacSHA256")) }
        return encoded + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(encoded.toByteArray()))
    }

    private fun recipe(name: String, vararg items: Pair<String, Double>) =
        mapOf("dish" to name, "items" to items.map { (ingredient, quantity) -> mapOf("ingredient" to ingredient, "quantity" to quantity) })

    private fun workoutNames(token: String) = call(HttpMethod.GET, "/api/workouts", token).body["workouts"].map { it["name"].asText() }.toSet()

    private fun activity(id: Long, name: String, calories: Double): JsonNode = mapper.valueToTree(
        mapOf(
            "activityId" to id,
            "activityName" to name,
            "calories" to calories,
            "startTimeGMT" to "$today 10:0$id:00",
            "startTimeLocal" to "$today 12:0$id:00",
        ),
    )
}

class FakeGarminApi : GarminApi {
    private val nodes = JsonNodeFactory.instance
    val activities = mutableMapOf<LocalDate, MutableList<JsonNode>>()
    val exchangedKeys = mutableListOf<String>()
    var summaryCalls = 0
    var failActivities = false
    var failToken = false

    fun reset() {
        activities.clear(); exchangedKeys.clear(); summaryCalls = 0; failActivities = false; failToken = false
    }

    override fun exchangeToken(apiKey: String): String {
        if (failToken) throw com.platito.garmin.GarminException("Garmin token exchange failed (401)")
        exchangedKeys += apiKey
        return "access-token-for-$apiKey"
    }

    override fun dailySummary(accessToken: String, date: LocalDate): JsonNode {
        summaryCalls++
        return nodes.arrayNode().add(nodes.objectNode().put("calendarDate", date.toString()).put("totalSteps", 1000 + date.dayOfMonth))
    }

    override fun sleepSummary(accessToken: String, date: LocalDate): JsonNode =
        nodes.objectNode().set(
            "dailySleepDTO",
            nodes.objectNode().put("calendarDate", date.toString()).put("sleepTimeSeconds", 25200),
        )

    override fun activitiesForDate(accessToken: String, date: LocalDate): List<JsonNode> {
        if (failActivities) throw com.platito.garmin.GarminException("Garmin activities request failed (500).")
        return activities[date].orEmpty().toList()
    }

    override fun deviceName(accessToken: String) = "Forerunner 965"
}

class DirectExecutorService : AbstractExecutorService() {
    override fun execute(command: Runnable) = command.run()
    override fun shutdown() {}
    override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
    override fun isShutdown() = false
    override fun isTerminated() = false
    override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
}
