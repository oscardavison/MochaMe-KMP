package com.mochame.utils.interfaces

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format.DateTimeFormat
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration
import kotlin.time.Instant

interface TimeUtils {
    fun now(): Instant
    val headerDateFormat : DateTimeFormat<LocalDate>
    val shortDateFormat: DateTimeFormat<LocalDate>

    fun getMillisAgo(
        duration: Duration,
        timeZone: TimeZone = TimeZone.currentSystemDefault()
    ): Long = (now() - duration).toEpochMilliseconds()

    fun isDaylight(instant: Instant): Boolean {
        val hour = instant.toLocalDateTime(TimeZone.currentSystemDefault()).hour
        return hour in 6..19
    }
}