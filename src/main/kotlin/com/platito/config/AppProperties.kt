package com.platito.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration
import java.time.ZoneId

@ConfigurationProperties("platito")
data class AppProperties(
    val authSecret: String,
    val garmin: Garmin,
    val sheets: Sheets,
    val admin: Admin,
) {
    init {
        require(authSecret.length >= 32) { "AUTH_SECRET must be set to a random value of at least 32 characters" }
    }

    data class Garmin(
        val baseUrl: String,
        val apiKeyEncryptionKey: String,
        val syncEnabled: Boolean,
        val syncCron: String,
        val summaryFreshness: Duration,
        val backfillDays: Int,
    )

    data class Sheets(
        val syncEnabled: Boolean,
        val syncCron: String,
        /** Zone of the daily schedule and of Sheet timestamps that carry no offset. */
        val zone: ZoneId,
        val spreadsheetId: String,
        val serviceAccountEmail: String,
        val privateKey: String,
        val baseUrl: String,
    )

    /** Credentials for the admin page; the page is off unless both are set. */
    data class Admin(val username: String, val password: String) {
        val enabled get() = username.isNotBlank() && password.isNotBlank()
    }
}
