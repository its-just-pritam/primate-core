package com.platito.auth

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.platito.config.AppProperties
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Vectors produced by Node's crypto module, as used by the Sheets-era app. */
class NodeCompatibilityTest {
    private val hasher = PasswordHasher()

    @Test
    fun `verifies a password hash created by node scrypt`() {
        val nodeHash = "00112233445566778899aabbccddeeff:" +
            "fcd5a58d5301bbc44e90fc9a53f156134baee795eb7735ed6473da86e34ba930" +
            "09476236665814fe08f7bd38ad1f5a2709832fb447b93b94e1a4a94dc5d1442e"
        assertTrue(hasher.verify("correct horse battery staple", nodeHash))
        assertFalse(hasher.verify("wrong password", nodeHash))
    }

    @Test
    fun `round-trips its own hashes and rejects malformed ones`() {
        val hash = hasher.hash("a long enough password")
        assertTrue(Regex("^[a-f0-9]{32}:[a-f0-9]{128}$").matches(hash))
        assertTrue(hasher.verify("a long enough password", hash))
        assertFalse(hasher.verify("a long enough password", "not-a-hash"))
        assertFalse(hasher.verify("a long enough password", null))
    }

    @Test
    fun `verifies a session token signed by the next app`() {
        val claims = verifier(Instant.parse("2026-01-01T00:00:00Z")).verify(NODE_TOKEN)
        assertEquals(SessionClaims("Ada Lovelace", "ada@example.com", 4102444800.0), claims)
    }

    @Test
    fun `rejects tampered, expired and malformed tokens`() {
        val verifier = verifier(Instant.parse("2026-01-01T00:00:00Z"))
        assertNull(verifier.verify(NODE_TOKEN.dropLast(2) + "AA"))
        assertNull(verifier.verify("x" + NODE_TOKEN))
        assertNull(verifier.verify("no-dot"))
        assertNull(verifier.verify(null))
        assertNull(verifier(Instant.ofEpochSecond(4102444800)).verify(NODE_TOKEN))
    }

    private fun verifier(now: Instant) = SessionTokenVerifier(
        AppProperties(
            SECRET,
            AppProperties.Garmin("http://localhost", "", false, "-", Duration.ZERO, 1),
            AppProperties.Sheets(false, "-", ZoneOffset.UTC, "", "", "", "http://localhost"),
            AppProperties.Admin("", ""),
        ),
        jacksonObjectMapper() as ObjectMapper,
        Clock.fixed(now, ZoneOffset.UTC),
    )

    private companion object {
        const val SECRET = "test-secret-test-secret-test-secret-123"
        const val NODE_TOKEN = "eyJuYW1lIjoiQWRhIExvdmVsYWNlIiwidXNlcm5hbWUiOiJhZGFAZXhhbXBsZS5jb20iLCJleHAiOjQxMDI0NDQ4MDB9" +
            ".rF9G06FIcUP6Cy4rPvVrn0RC1Z22oWZSdpchUKueoTA"
    }
}
