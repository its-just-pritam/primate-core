package com.platito.admin

import com.platito.config.AppProperties
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import java.security.MessageDigest
import java.util.Base64

/** Header the admin page sends on every change; a cross-site form cannot set it, so it stops CSRF. */
const val ADMIN_REQUEST_HEADER = "X-Admin-Request"

/**
 * HTTP Basic auth for `/admin` and everything under it, against ADMIN_USERNAME / ADMIN_PASSWORD. Without both set the
 * admin page does not exist (404). Requests that change something must also carry [ADMIN_REQUEST_HEADER].
 */
@Component
class AdminAuthInterceptor(properties: AppProperties) : HandlerInterceptor {
    private val admin = properties.admin

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        if (!admin.enabled) return reject(response, HttpStatus.NOT_FOUND, "Not found")
        if (!authorized(request.getHeader("Authorization"))) {
            response.setHeader("WWW-Authenticate", "Basic realm=\"Platito admin\", charset=\"UTF-8\"")
            return reject(response, HttpStatus.UNAUTHORIZED, "Authentication required")
        }
        if (request.method != "GET" && request.getHeader(ADMIN_REQUEST_HEADER) == null) {
            return reject(response, HttpStatus.FORBIDDEN, "Missing $ADMIN_REQUEST_HEADER header")
        }
        response.setHeader("Cache-Control", "no-store")
        return true
    }

    private fun authorized(header: String?): Boolean {
        val encoded = header?.let { Regex("^Basic\\s+(.+)$", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) } ?: return false
        val decoded = runCatching { String(Base64.getDecoder().decode(encoded.trim()), Charsets.UTF_8) }.getOrNull() ?: return false
        val username = decoded.substringBefore(':', missingDelimiterValue = "")
        val password = decoded.substringAfter(':', missingDelimiterValue = "")
        // Compare both in constant time, and always both, so timing reveals neither.
        val userMatches = MessageDigest.isEqual(username.toByteArray(), admin.username.toByteArray())
        val passwordMatches = MessageDigest.isEqual(password.toByteArray(), admin.password.toByteArray())
        return userMatches and passwordMatches
    }

    private fun reject(response: HttpServletResponse, status: HttpStatus, message: String): Boolean {
        response.status = status.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.writer.write("""{"error":"$message"}""")
        return false
    }
}

@Configuration
class AdminWebConfig(private val adminAuthInterceptor: AdminAuthInterceptor) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(adminAuthInterceptor).addPathPatterns("/admin", "/admin/**")
    }
}
