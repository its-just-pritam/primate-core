package com.platito.admin

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.platito.config.AppProperties
import com.platito.sheets.SheetSource
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Duration
import java.time.ZoneOffset
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest(
    properties = [
        "platito.auth-secret=integration-test-secret-integration-test",
        "platito.garmin.api-key-encryption-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
        "platito.garmin.sync-enabled=false",
        "platito.admin.username=admin",
        "platito.admin.password=correct horse battery staple",
    ],
)
@AutoConfigureMockMvc
@Testcontainers
class AdminIntegrationTest {
    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }

    /** A Sheet with no tabs, so a triggered sync fails fast without calling Google. */
    @TestConfiguration
    class EmptySheetConfig {
        @Bean
        @Primary
        fun emptySheet() = object : SheetSource {
            override fun readTab(tab: String): List<List<Any?>>? = null
        }
    }

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var mapper: ObjectMapper
    @Autowired lateinit var jdbc: JdbcClient

    private fun MockHttpServletRequestBuilder.auth(password: String = "correct horse battery staple") =
        header("Authorization", "Basic " + Base64.getEncoder().encodeToString("admin:$password".toByteArray()))

    private fun MockHttpServletRequestBuilder.admin() = auth().header(ADMIN_REQUEST_HEADER, "1")

    private fun json(builder: MockHttpServletRequestBuilder): Pair<Int, JsonNode> {
        val response = mvc.perform(builder).andReturn().response
        return response.status to mapper.readTree(response.contentAsString.ifEmpty { "{}" })
    }

    @Test
    fun `the page and its API require the admin login`() {
        val anonymous = mvc.perform(get("/admin")).andReturn().response
        assertEquals(401, anonymous.status)
        assertTrue(anonymous.getHeader("WWW-Authenticate")!!.startsWith("Basic"))
        assertEquals(401, mvc.perform(get("/admin/api/overview").auth("wrong")).andReturn().response.status)

        val page = mvc.perform(get("/admin").auth()).andReturn().response
        assertEquals(200, page.status)
        assertTrue("<title>Platito admin</title>" in page.contentAsString)
        assertEquals("no-store", page.getHeader("Cache-Control"))

        // Changes need the admin header, which a cross-site form cannot send.
        assertEquals(403, mvc.perform(post("/admin/api/jobs/sheets-sync/run").auth()).andReturn().response.status)
    }

    @Test
    fun `overview lists jobs and users without secrets`() {
        jdbc.sql("INSERT INTO app_user (email, name, password_hash) VALUES ('overview@example.com', 'Overview User', 'secret:hash') ON CONFLICT DO NOTHING").update()
        val (status, body) = json(get("/admin/api/overview").auth())
        assertEquals(200, status)
        assertEquals(listOf("sheets-sync", "garmin-sync"), body["jobs"].map { it["id"].asText() })
        assertTrue(body["users"].any { it["email"].asText() == "overview@example.com" })
        assertFalse("secret:hash" in body.toString())
    }

    @Test
    fun `run now starts the Sheets sync in the background`() {
        assertEquals(404, json(post("/admin/api/jobs/nope/run").admin()).first)

        val lastRunBefore = jdbc.sql("SELECT coalesce(max(id), 0) FROM sheets_sync_run").query(Long::class.java).single()
        assertEquals(202, json(post("/admin/api/jobs/sheets-sync/run").admin()).first)

        // The request returns at once; wait for the background run to finish.
        val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
        var run: Pair<String, String?>? = null
        while (run == null && System.nanoTime() < deadline) {
            Thread.sleep(100)
            run = jdbc.sql("SELECT status, error FROM sheets_sync_run WHERE id > :before AND status IS NOT NULL")
                .param("before", lastRunBefore)
                .query { rs, _ -> rs.getString(1) to rs.getString(2) }.optional().orElse(null)
        }
        assertEquals("FAILED" to "The Sheet has no User tab.", run)
    }

    @Test
    fun `run now starts garmin sync with optional date or timestamp`() {
        val (status1, body1) = json(
            post("/admin/api/jobs/garmin-sync/run")
                .admin()
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"date":"2026-11-08"}""")
        )
        assertEquals(202, status1)
        assertEquals("started", body1["status"].asText())

        val (badStatus, badBody) = json(
            post("/admin/api/jobs/garmin-sync/run")
                .admin()
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"date":"not-a-valid-date"}""")
        )
        assertEquals(400, badStatus)
        assertEquals("Invalid date or timestamp: not-a-valid-date", badBody["error"].asText())
    }

    @Test
    fun `garmin sync for one user needs a known, connected user`() {
        assertEquals(404, json(post("/admin/api/users/999999/garmin-sync").admin()).first)
        val id = jdbc.sql("INSERT INTO app_user (email, name) VALUES ('nogarmin@example.com', 'No Garmin') RETURNING id").query(Long::class.java).single()
        val (status, body) = json(post("/admin/api/users/$id/garmin-sync").admin())
        assertEquals(409, status)
        assertEquals("This user has not connected Garmin.", body["error"].asText())
    }
}

class AdminAuthInterceptorTest {
    @Test
    fun `the admin page does not exist without credentials configured`() {
        val properties = AppProperties(
            "x".repeat(32),
            AppProperties.Garmin("http://localhost", "", false, "-", Duration.ZERO, 1),
            AppProperties.Sheets(false, "-", ZoneOffset.UTC, "", "", "", "http://localhost"),
            AppProperties.Admin("", ""),
        )
        val response = MockHttpServletResponse()
        assertFalse(AdminAuthInterceptor(properties).preHandle(MockHttpServletRequest("GET", "/admin"), response, Any()))
        assertEquals(404, response.status)
    }
}
