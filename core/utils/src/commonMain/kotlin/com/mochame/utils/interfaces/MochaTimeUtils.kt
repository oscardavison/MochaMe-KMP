package com.mochame.utils.interfaces

import kotlinx.datetime.TimeZone
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * Domain-specific functionality on 4 AM cutoff.
 */
interface MochaTimeUtils : TimeUtils {
    fun getMochaDay(): Long

    fun calculateMochaEpochDay(
        instant: Instant,
        timeZone: TimeZone = TimeZone.currentSystemDefault()
    ): Long

    override fun getMillisAgo(
        duration: Duration,
        timeZone: TimeZone
    ): Long

    /**
     * Converts a Mocha epoch day into a clean human-readable date ("Mon, Aug 31, 2026").
     */
    fun formatStandardDay(epochDay: Long): String

    /**
     * Converts an Instant to its corresponding Mocha day and returns the formatted label.
     */
    fun formatMochaDay(
        instant: Instant,
        timeZone: TimeZone = TimeZone.currentSystemDefault()
    ): String

    /**
     * Formats the day with relative context ("Today • Aug 31", "Yesterday • Aug 30", or full date).
     */
    fun formatRelativeMochaDay(epochDay: Long): String
}