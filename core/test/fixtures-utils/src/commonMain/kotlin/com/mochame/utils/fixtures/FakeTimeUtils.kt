package com.mochame.utils.fixtures

import com.mochame.sync.api.hlc.HLC
import com.mochame.utils.implementations.DefaultMochaTimeUtils
import com.mochame.utils.interfaces.TimeUtils
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.format.DayOfWeekNames
import kotlinx.datetime.format.MonthNames
import kotlinx.datetime.format.char
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Defaults initial time to: Saturday, March 1, 2025 at 00:00:00 UTC.
 */
open class FakeTimeUtils(
    initialTime: Instant = HLC.APP_RELEASE_TIME.plus(1.days)
) : TimeUtils {

    private val lock = reentrantLock()
    var currentTime: Instant = initialTime

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

    fun advanceTime(duration: Duration) = lock.withLock { currentTime += duration }
    fun reverseTime(duration: Duration) = lock.withLock { currentTime -= duration }
    fun setTime(instant: Instant) = lock.withLock { currentTime = instant }

    override fun now(): Instant = lock.withLock { currentTime }
}

class AutoIncrementFakeTimeUtils(
    private val baseClock: FakeTimeUtils = FakeTimeUtils()
) : TimeUtils by baseClock {

    override fun now() = baseClock.now().plus(1.seconds).also { baseClock.currentTime = it }
}

class MochaFakeTimeUtils(
    val baseClock: FakeTimeUtils = FakeTimeUtils()
) : DefaultMochaTimeUtils(baseClock) {

    fun advanceTime(duration: Duration) = baseClock.advanceTime(duration)
    fun reverseTime(duration: Duration) = baseClock.reverseTime(duration)
    fun setTime(instant: Instant) = baseClock.setTime(instant)

    /**
     * Sets Clock instant based on base day
     *
     * @param baseDay Instant for test orientation. Defaults to August 27, 2026 (00:00:00 UTC).
     * @return [fakeClock.calculateMochaDay] equivalent
     */
    fun wind(baseDay: Long = 20693L): Long {
        val baseInstant = Instant.fromEpochSeconds(baseDay * 86_400L)
        setTime(baseInstant)
        return calculateMochaEpochDay(baseInstant)
    }
}