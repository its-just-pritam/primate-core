package com.platito.auth

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.platito.config.AppProperties
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.time.Clock
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class SessionClaims(val name: String = "", val username: String = "", val exp: Double = 0.0)

/**
 * Verifies session tokens issued by the Next.js app (lib/authSession.ts):
 * `base64url(json payload) + "." + base64url(HMAC-SHA256(payload, AUTH_SECRET))`.
 */
@Component
class SessionTokenVerifier(
    properties: AppProperties,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {
    private val key = SecretKeySpec(properties.authSecret.toByteArray(Charsets.UTF_8), "HmacSHA256")

    fun verify(token: String?): SessionClaims? {
        val parts = token?.split('.') ?: return null
        if (parts.size != 2 || parts.any { it.isEmpty() }) return null
        val (encoded, signature) = parts
        return try {
            val mac = Mac.getInstance("HmacSHA256").apply { init(key) }
            val expected = mac.doFinal(encoded.toByteArray(Charsets.US_ASCII))
            val actual = Base64.getUrlDecoder().decode(signature)
            if (!MessageDigest.isEqual(expected, actual)) return null
            val claims = objectMapper.readValue<SessionClaims>(Base64.getUrlDecoder().decode(encoded))
            claims.takeIf { it.name.isNotBlank() && it.username.isNotBlank() && it.exp > clock.instant().epochSecond }
        } catch (e: IllegalArgumentException) {
            null
        } catch (e: com.fasterxml.jackson.core.JacksonException) {
            null
        }
    }
}
