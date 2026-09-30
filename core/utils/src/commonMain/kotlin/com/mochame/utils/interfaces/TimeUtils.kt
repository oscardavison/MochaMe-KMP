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

    fun getStandardEpochDay(
        instant: Instant = now(),
        timeZone: TimeZone = TimeZone.currentSystemDefault()
    ): Long

    fun formatStandardDay(epochDay: Long): String

    fun formatRelativeDay(
        epochDay: Long,
        referenceToday: Long = getStandardEpochDay()
    ): String

    fun getMillisAgo(
        duration: Duration,
        timeZone: TimeZone = TimeZone.currentSystemDefault()
    ): Long = (now() - duration).toEpochMilliseconds()

    fun isDaylight(instant: Instant): Boolean {
        val hour = instant.toLocalDateTime(TimeZone.currentSystemDefault()).hour
        return hour in 6..19
    }
}