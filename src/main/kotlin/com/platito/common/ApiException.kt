package com.platito.common

import com.platito.garmin.GarminException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.resource.NoResourceFoundException

/** An error whose message is safe to show to the user. Rendered as `{ "error": message }`. */
class ApiException(val status: HttpStatus, override val message: String) : RuntimeException(message)

fun badRequest(message: String) = ApiException(HttpStatus.BAD_REQUEST, message)
fun notFound(message: String) = ApiException(HttpStatus.NOT_FOUND, message)
fun conflict(message: String) = ApiException(HttpStatus.CONFLICT, message)
fun unauthorized(message: String = "Authentication required") = ApiException(HttpStatus.UNAUTHORIZED, message)

@RestControllerAdvice
class ApiExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(ApiException::class)
    fun handleApi(e: ApiException) = error(e.status, e.message)

    @ExceptionHandler(GarminException::class)
    fun handleGarmin(e: GarminException): ResponseEntity<Map<String, String>> {
        log.warn("Garmin request failed: {}", e.message)
        return error(HttpStatus.BAD_GATEWAY, e.message)
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadable(e: HttpMessageNotReadableException) = error(HttpStatus.BAD_REQUEST, "Invalid request body")

    @ExceptionHandler(NoResourceFoundException::class)
    fun handleNoResource(e: NoResourceFoundException) = error(HttpStatus.NOT_FOUND, "Not found")

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(e: Exception): ResponseEntity<Map<String, String>> {
        log.error("Unhandled request failure", e)
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected server error")
    }

    private fun error(status: HttpStatus, message: String) =
        ResponseEntity.status(status).body(mapOf("error" to message))
}
