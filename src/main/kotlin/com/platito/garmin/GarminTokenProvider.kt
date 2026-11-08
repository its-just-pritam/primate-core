package com.platito.garmin

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** Caches short-lived Garmin access tokens per user so each sync does not repeat the key exchange. */
@Component
class GarminTokenProvider(
    private val api: GarminApi,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {
    private data class Cached(val keyFingerprint: String, val token: String, val expiresAt: Instant)

    private val cache = ConcurrentHashMap<Long, Cached>()

    fun token(userId: Long, apiKey: String): String {
        val fingerprint = fingerprint(apiKey)
        cache[userId]
            ?.takeIf { it.keyFingerprint == fingerprint && it.expiresAt.isAfter(clock.instant().plus(EXPIRY_MARGIN)) }
            ?.let { return it.token }
        return api.exchangeToken(apiKey).also { remember(userId, apiKey, it) }
    }

    fun remember(userId: Long, apiKey: String, token: String) {
        cache[userId] = Cached(fingerprint(apiKey), token, expiresAt(token))
    }

    fun evict(userId: Long) {
        cache.remove(userId)
    }

    /** The JWT `exp` claim when present, otherwise a conservative 50 minutes. */
    fun expiresAt(token: String): Instant {
        val exp = runCatching {
            val payload = token.split('.')[1]
            objectMapper.readTree(Base64.getUrlDecoder().decode(payload)).path("exp").takeIf { it.isNumber }?.asLong()
        }.getOrNull()
        return exp?.let(Instant::ofEpochSecond) ?: clock.instant().plus(Duration.ofMinutes(50))
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun fingerprint(apiKey: String) =
        MessageDigest.getInstance("SHA-256").digest(apiKey.toByteArray(Charsets.UTF_8)).toHexString()

    private companion object {
        val EXPIRY_MARGIN: Duration = Duration.ofSeconds(60)
    }
}
