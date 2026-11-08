package com.platito.config

import com.platito.user.UserRepository
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.jdbc.core.simple.JdbcClient
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.assertEquals

/** With DATABASE_SCHEMA set (as on Supabase), the schema is created and holds every table. */
@SpringBootTest(
    properties = [
        "DATABASE_SCHEMA=platito",
        "platito.auth-secret=integration-test-secret-integration-test",
        "platito.garmin.api-key-encryption-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=",
        "platito.garmin.sync-enabled=false",
    ],
)
@Testcontainers
class CustomSchemaTest {
    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
    }

    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var users: UserRepository

    @Test
    fun `migrations and queries use the configured schema`() {
        fun tablesIn(schema: String) =
            jdbc.sql("SELECT count(*) FROM information_schema.tables WHERE table_schema = :schema AND table_name IN ('app_user', 'databasechangelog', 'shedlock')")
                .param("schema", schema).query(Long::class.java).single()
        assertEquals(3, tablesIn("platito"))
        assertEquals(0, tablesIn("public"))

        users.create("schema@example.com", "Schema", "Test", "aa:bb")
        assertEquals("Schema Test", users.findByEmail("schema@example.com")?.name)
    }
}
