package com.mochame.utils.implementations

import com.mochame.utils.interfaces.TimeUtils
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.format.DayOfWeekNames
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.char
import kotlinx.datetime.toLocalDateTime
import org.koin.core.annotation.Single
import kotlin.time.Clock
import kotlin.time.Instant

@Single(binds = [TimeUtils::class])
open class DefaultTimeUtils : TimeUtils {

    override fun now(): Instant = Clock.System.now()

    override val headerDateFormat = LocalDate.Format {
        dayOfWeek(DayOfWeekNames.ENGLISH_ABBREVIATED)
        chars(", ")
        monthName(MonthNames.ENGLISH_ABBREVIATED)
        char(' ')
        day()
        chars(", ")
        year()
    }

    override val shortDateFormat = LocalDate.Format {
        monthName(MonthNames.ENGLISH_ABBREVIATED)
        char(' ')
        day()
    }

    override fun getStandardEpochDay(
        instant: Instant,
        timeZone: TimeZone
    ): Long = instant.toLocalDateTime(timeZone).date.toEpochDays()

    override fun formatStandardDay(epochDay: Long): String {
        return LocalDate.fromEpochDays(epochDay).format(headerDateFormat)
    }

    override fun formatRelativeDay(
        epochDay: Long,
        referenceToday: Long
    ): String {
        val date = LocalDate.fromEpochDays(epochDay)
        val shortDate = date.format(shortDateFormat)

        return when (epochDay) {
            referenceToday -> "Today • $shortDate"
            referenceToday - 1L -> "Yesterday • $shortDate"
            referenceToday + 1L -> "Tomorrow • $shortDate"
            else -> formatStandardDay(epochDay)
        }
    }
}