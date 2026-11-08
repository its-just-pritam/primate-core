package com.platito.sheets

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.auth.oauth2.ServiceAccountCredentials
import com.platito.config.AppProperties
import org.springframework.stereotype.Component
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class SheetsException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** Reads whole tabs of the configured spreadsheet. */
interface SheetSource {
    /**
     * Rows of [tab], header row first, or null when the spreadsheet has no such tab. Cells are
     * unformatted: numbers as [Number], dates and times as spreadsheet serial numbers, the rest as [String].
     */
    fun readTab(tab: String): List<List<Any?>>?
}

@Component
class GoogleSheetsSource(
    properties: AppProperties,
    private val objectMapper: ObjectMapper,
) : SheetSource {
    private val config = properties.sheets
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    /** Built on first use so the app starts without Google credentials while the sync is off. */
    private val credentials by lazy {
        if (config.spreadsheetId.isBlank() || config.serviceAccountEmail.isBlank() || config.privateKey.isBlank()) {
            throw SheetsException("Set GOOGLE_SHEET_ID, GOOGLE_SERVICE_ACCOUNT_EMAIL and GOOGLE_PRIVATE_KEY.")
        }
        ServiceAccountCredentials.fromPkcs8(
            null,
            config.serviceAccountEmail,
            config.privateKey.replace("\\n", "\n"),
            null,
            listOf(READONLY_SCOPE),
        )
    }

    override fun readTab(tab: String): List<List<Any?>>? {
        val range = URLEncoder.encode("$tab!A1:Z", Charsets.UTF_8)
        val uri = URI.create(
            "${config.baseUrl.trimEnd('/')}/v4/spreadsheets/${config.spreadsheetId}/values/$range" +
                "?valueRenderOption=UNFORMATTED_VALUE&dateTimeRenderOption=SERIAL_NUMBER",
        )
        val request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(60))
            .header("Authorization", "Bearer ${accessToken()}")
            .GET()
            .build()
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: IOException) {
            throw SheetsException("Reading the $tab tab failed: ${e.message}", e)
        }
        // Google answers 400 "Unable to parse range" for a tab that does not exist.
        if (response.statusCode() == 400 && "Unable to parse range" in response.body()) return null
        if (response.statusCode() !in 200..299) {
            throw SheetsException("Reading the $tab tab failed (${response.statusCode()}): ${response.body().take(400)}")
        }
        return objectMapper.readTree(response.body()).path("values").map { row -> row.map(::cell) }
    }

    private fun accessToken(): String =
        try {
            credentials.refreshIfExpired()
            credentials.accessToken.tokenValue
        } catch (e: IOException) {
            throw SheetsException("Google authentication failed: ${e.message}", e)
        }

    private fun cell(node: JsonNode): Any? = when {
        node.isNumber -> node.numberValue()
        node.isBoolean -> node.booleanValue().toString()
        node.isNull -> null
        else -> node.asText()
    }

    private companion object {
        const val READONLY_SCOPE = "https://www.googleapis.com/auth/spreadsheets.readonly"
    }
}
