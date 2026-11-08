package com.platito.auth

import com.platito.common.ApiException
import com.platito.common.badRequest
import com.platito.common.conflict
import com.platito.user.UserRecord
import com.platito.user.UserRepository
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class SignupRequest(
    val firstName: String? = null,
    val lastName: String? = null,
    val email: String? = null,
    val password: String? = null,
)

data class LoginRequest(val username: String? = null, val password: String? = null)
data class ChangePasswordRequest(val newPassword: String? = null)
data class SessionUser(val name: String, val username: String)
data class AuthResponse(val success: Boolean, val user: SessionUser)

/**
 * Account endpoints. The Next.js app calls signup and login, then issues the session cookie
 * from the returned user; this service never sees the cookie, only the derived bearer token.
 */
@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val users: UserRepository,
    private val hasher: PasswordHasher,
) {
    @Public
    @PostMapping("/signup")
    fun signup(@RequestBody body: SignupRequest): AuthResponse {
        val firstName = body.firstName?.trim().orEmpty()
        val lastName = body.lastName?.trim().orEmpty()
        val email = body.email?.trim()?.lowercase().orEmpty()
        val password = body.password.orEmpty()
        if (firstName.isEmpty() || lastName.isEmpty() || firstName.length > 60 || lastName.length > 60 ||
            !EMAIL_PATTERN.matches(email) || email.length > 254 || password.length !in PASSWORD_LENGTH
        ) {
            throw badRequest("Enter your first name, last name, a valid email, and a password with at least 12 characters.")
        }
        // Names stay unique: the UI shows the full name as the account's identity.
        if (users.existsByEmail(email) || users.existsByName("$firstName $lastName")) throw duplicateAccount()
        val user = try {
            users.create(email, firstName, lastName, hasher.hash(password))
        } catch (e: DuplicateKeyException) {
            throw duplicateAccount()
        }
        return AuthResponse(true, user.toSessionUser())
    }

    @Public
    @PostMapping("/login")
    fun login(@RequestBody body: LoginRequest): AuthResponse {
        val username = body.username?.trim().orEmpty()
        val password = body.password.orEmpty()
        if (username.isEmpty() || password.isEmpty()) throw badRequest("Username and password are required")
        val user = users.findByEmail(username)
        if (user == null) {
            hasher.burnTime(password)
            throw invalidCredentials()
        }
        if (!hasher.verify(password, user.passwordHash)) throw invalidCredentials()
        return AuthResponse(true, user.toSessionUser())
    }

    @PostMapping("/change-password")
    fun changePassword(user: UserRecord, @RequestBody body: ChangePasswordRequest): Map<String, Boolean> {
        val newPassword = body.newPassword.orEmpty()
        if (newPassword.length !in PASSWORD_LENGTH) throw badRequest("Enter a new password between 12 and 256 characters.")
        users.updatePasswordHash(user.id, hasher.hash(newPassword))
        return mapOf("success" to true)
    }

    private fun UserRecord.toSessionUser() = SessionUser(name, email)

    private fun duplicateAccount() = conflict("An account with those details already exists.")
    private fun invalidCredentials() = ApiException(HttpStatus.UNAUTHORIZED, "Invalid username or password")

    private companion object {
        val EMAIL_PATTERN = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
        val PASSWORD_LENGTH = 12..256
    }
}
