package com.platito.garmin

import com.platito.config.AppProperties
import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** AES-256-GCM encryption for Garmin API keys at rest, stored as `v1:<base64(iv || ciphertext)>`. */
@Component
class ApiKeyCipher(properties: AppProperties) {
    private val key: SecretKeySpec
    private val random = SecureRandom()

    init {
        val raw = runCatching { Base64.getDecoder().decode(properties.garmin.apiKeyEncryptionKey.trim()) }.getOrNull()
        require(raw != null && raw.size == 32) {
            "GARMIN_API_KEY_ENCRYPTION_KEY must be the base64 encoding of 32 random bytes"
        }
        key = SecretKeySpec(raw, "AES")
    }

    fun encrypt(plaintext: String): String {
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv)) }
        return PREFIX + Base64.getEncoder().encodeToString(iv + cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)))
    }

    fun decrypt(stored: String): String {
        require(stored.startsWith(PREFIX)) { "Unsupported API key ciphertext version" }
        val bytes = Base64.getDecoder().decode(stored.removePrefix(PREFIX))
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
        }
        return String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
    }

    private companion object {
        const val PREFIX = "v1:"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
