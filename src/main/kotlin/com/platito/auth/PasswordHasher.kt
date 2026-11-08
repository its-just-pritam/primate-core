package com.platito.auth

import org.bouncycastle.crypto.generators.SCrypt
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * scrypt with Node's `crypto.scrypt` defaults (N=16384, r=8, p=1, 64-byte key), stored as
 * `<16-byte salt hex>:<64-byte digest hex>` so hashes created by the Sheets-era app keep working.
 */
@OptIn(ExperimentalStdlibApi::class)
@Component
class PasswordHasher {
    private val random = SecureRandom()

    fun hash(password: String): String {
        val salt = ByteArray(16).also(random::nextBytes)
        return "${salt.toHexString()}:${derive(password, salt).toHexString()}"
    }

    fun verify(password: String, stored: String?): Boolean {
        val parts = stored?.trim()?.split(':') ?: return false
        if (parts.size != 2 || !SALT.matches(parts[0]) || !DIGEST.matches(parts[1])) return false
        val derived = derive(password, parts[0].hexToByteArray())
        return MessageDigest.isEqual(derived, parts[1].hexToByteArray())
    }

    /** Spends the same work as a real check so unknown accounts are not distinguishable by timing. */
    fun burnTime(password: String) {
        derive(password, DUMMY_SALT)
    }

    private fun derive(password: String, salt: ByteArray): ByteArray =
        SCrypt.generate(password.toByteArray(Charsets.UTF_8), salt, 16384, 8, 1, 64)

    private companion object {
        val SALT = Regex("^[a-fA-F0-9]{32}$")
        val DIGEST = Regex("^[a-fA-F0-9]{128}$")
        val DUMMY_SALT = ByteArray(16)
    }
}
