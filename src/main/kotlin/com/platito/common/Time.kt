package com.platito.common

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Parses timestamps the way the browser sends them: ISO instants or offsets, or a bare date
 * or date-time, which (like JavaScript's `new Date`) are read as UTC.
 */
fun parseTimestamp(value: String?): Instant? {
    val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return listOf<(String) -> Instant>(
        { Instant.parse(it) },
        { OffsetDateTime.parse(it).toInstant() },
        { LocalDateTime.parse(it).toInstant(ZoneOffset.UTC) },
        { LocalDate.parse(it).atStartOfDay().toInstant(ZoneOffset.UTC) },
    ).firstNotNullOfOrNull { parser -> runCatching { parser(text) }.getOrNull() }
}

fun parseZone(value: String?): ZoneId? =
    try {
        value?.trim()?.takeIf { it.isNotEmpty() }?.let(ZoneId::of)
    } catch (e: DateTimeException) {
        null
    }

private val DATE_PATTERN = Regex("^\\d{4}-\\d{2}-\\d{2}$")

fun parseDate(value: String?): LocalDate? =
    value?.takeIf { DATE_PATTERN.matches(it) }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
