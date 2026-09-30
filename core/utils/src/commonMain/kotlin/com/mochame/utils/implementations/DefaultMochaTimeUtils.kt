package com.mochame.utils.implementations

import com.mochame.utils.interfaces.MochaTimeUtils
import com.mochame.utils.interfaces.TimeUtils
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import org.koin.core.annotation.Single
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant


@Single(binds = [MochaTimeUtils::class])
open class DefaultMochaTimeUtils(
    private val timeUtils: TimeUtils
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

    override fun formatMochaDay(instant: Instant, timeZone: TimeZone): String {
        val epochDay = calculateMochaEpochDay(instant, timeZone)
        return formatStandardDay(epochDay)
    }

    override fun formatRelativeMochaDay(epochDay: Long): String {
        return formatRelativeDay(epochDay, referenceToday = getMochaDay())
    }
}