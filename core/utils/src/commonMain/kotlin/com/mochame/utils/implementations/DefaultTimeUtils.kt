package com.mochame.utils.implementations

import com.mochame.utils.interfaces.TimeUtils
import kotlinx.datetime.LocalDate
import kotlinx.datetime.format.DayOfWeekNames
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.char
import org.koin.core.annotation.Single
import kotlin.time.Clock
import kotlin.time.Instant

@Single(binds = [TimeUtils::class])
class DefaultTimeUtils : TimeUtils {
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
}