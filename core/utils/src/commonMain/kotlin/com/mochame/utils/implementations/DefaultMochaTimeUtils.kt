package com.mochame.utils.implementations

import com.mochame.utils.interfaces.MochaTimeUtils
import com.mochame.utils.interfaces.TimeUtils
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.format
import kotlinx.datetime.format.DayOfWeekNames
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.char
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.koin.core.annotation.Single
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant


@Single(binds = [MochaTimeUtils::class])
open class DefaultMochaTimeUtils(
    private val timeUtils: TimeUtils = DefaultTimeUtils()
) : MochaTimeUtils, TimeUtils by timeUtils {

    override fun getMochaDay(): Long = calculateMochaEpochDay(now())

    override fun calculateMochaEpochDay(
        instant: Instant,
        timeZone: TimeZone
    ): Long {
        val biologicalInstant = instant.minus(4.hours)
        return biologicalInstant.toLocalDateTime(timeZone).date.toEpochDays()
    }

    override fun getMillisAgo(
        duration: Duration,
        timeZone: TimeZone
    ): Long {
        val targetMochaDay = getMochaDay() - duration.inWholeDays
        val targetDate = LocalDate.fromEpochDays(targetMochaDay)
        return targetDate.atTime(hour = 4, minute = 0)
            .toInstant(timeZone)
            .toEpochMilliseconds()
    }

    override fun formatStandardDay(epochDay: Long): String {
        val date = LocalDate.fromEpochDays(epochDay)
        return date.format(HeaderDateFormat)
    }

    override fun formatMochaDay(instant: Instant, timeZone: TimeZone): String {
        val epochDay = calculateMochaEpochDay(instant, timeZone)
        return formatStandardDay(epochDay)
    }

    override fun formatRelativeMochaDay(epochDay: Long): String {
        val today = getMochaDay()
        val date = LocalDate.fromEpochDays(epochDay)
        val shortDate = date.format(MochaShortDateFormat)

        return when (epochDay) {
            today -> "Today • $shortDate"
            today - 1L -> "Yesterday • $shortDate"
            today + 1L -> "Tomorrow • $shortDate"
            else -> formatStandardDay(epochDay)
        }
    }
}

private val HeaderDateFormat = LocalDate.Format {
    dayOfWeek(DayOfWeekNames.ENGLISH_ABBREVIATED)
    chars(", ")
    monthName(MonthNames.ENGLISH_ABBREVIATED)
    char(' ')
    day()
    chars(", ")
    year()
}

private val MochaShortDateFormat = LocalDate.Format {
    monthName(MonthNames.ENGLISH_ABBREVIATED)
    char(' ')
    day()
}