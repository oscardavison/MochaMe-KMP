package com.mochame.sync.domain.hlc

import co.touchlab.kermit.Logger
import com.mochame.sync.api.exceptions.MochaException
import com.mochame.sync.api.models.HLC
import com.mochame.sync.api.models.HLC.Companion.MAX_DRIFT
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

sealed interface HlcEvaluation {

    data class Valid(
        val hlcInstant: Instant,
        val deviceClock: Instant,
        val drift: Duration = Duration.ZERO,
        val historyLag: Duration? = null
    ) : HlcEvaluation {
        /**
         * Logical timestamp is strictly ahead of physical device clock.
         * Device clock lags behind logical reality.
         */
        val hasFutureDrift: Boolean get() = drift.inWholeMilliseconds > 0

        /**
         * Device clock is more than 60 days ahead of historical timestamp.
         */
        val hasNotableHistoryJump: Boolean get() = historyLag != null && historyLag >= 60.days
    }

    data class NewInstall(val hlcInstant: Instant) : HlcEvaluation

    data class CounterOverflow(
        val count: Int,
        val limit: Int = HLC.MAX_COUNTER_INT
    ) : HlcEvaluation

    data class PrecedesAppRelease(
        val hlcInstant: Instant,
        val releaseFloor: Instant,
        val drift: Duration
    ) : HlcEvaluation

    data class ExceedsMaxDrift(
        val hlcInstant: Instant,
        val deviceClock: Instant,
        val drift: Duration
    ) : HlcEvaluation

    val isValid: Boolean get() = this is Valid || this is NewInstall
}

/**
 * Logs evaluation result based on severity level.
 * Returns [this] to allow method chaining.
 */
internal fun HlcEvaluation.log(
    logger: Logger,
    contextKey: Long? = null
): HlcEvaluation {
    val keyContext = contextKey?.let { " for key [$it]" } ?: ""

    when (this) {
        is HlcEvaluation.Valid -> {
            if (hasFutureDrift) {
                logger.w {
                    "HLC Future Drift Detected: ${drift}$keyContext. Advancing local HLC to match remote truth."
                }
            }

            historyLag?.let { lag ->
                if (hasNotableHistoryJump) {
                    logger.w {
                        "Future Clock Jump Detected$keyContext: Device clock is ${lag.inWholeDays} days ahead of stored history."
                    }
                }
            }
        }

        is HlcEvaluation.NewInstall -> {
            logger.i { "Hydrating new install: $hlcInstant." }
        }

        is HlcEvaluation.CounterOverflow -> {
            logger.e { "HLC Counter Exhausted$keyContext: count=$count exceeds limit=$limit" }
        }

        is HlcEvaluation.PrecedesAppRelease -> {
            logger.e {
                "Clock Skew (Behind Floor)$keyContext: Timestamp $hlcInstant is " +
                        "${drift.inWholeDays} days (${drift.inWholeSeconds}s) behind app release floor $releaseFloor"
            }
        }

        is HlcEvaluation.ExceedsMaxDrift -> {
            logger.e {
                "Clock Skew (Future Drift)$keyContext: Timestamp $hlcInstant is " +
                        "${drift.inWholeMilliseconds}ms ahead of device clock $deviceClock (Max allowed: ${MAX_DRIFT.inWholeMilliseconds})"
            }
        }
    }
    return this
}

/**
 * Throws the appropriate system exception if the evaluation represents a hard failure state.
 */
internal fun HlcEvaluation.throwIfInvalid(contextKey: Long? = null) {
    val keyContext = contextKey?.let { " for key [$it]" } ?: ""

    when (this) {
        is HlcEvaluation.Valid,
        is HlcEvaluation.NewInstall -> Unit

        is HlcEvaluation.CounterOverflow -> {
            throw MochaException.Policy.CausalityViolation(
                "HLC counter overflow$keyContext: count=$count exceeds max $limit"
            )
        }

        is HlcEvaluation.PrecedesAppRelease -> {
            throw MochaException.Persistent.ClockSkew(drift)
        }

        is HlcEvaluation.ExceedsMaxDrift -> {
            throw MochaException.Persistent.ClockSkew(drift)
        }
    }
}

/**
 * Combined extension function for operations requiring both telemetry and failure enforcement.
 */
internal fun HlcEvaluation.logAndThrowIfInvalid(
    logger: Logger,
    contextKey: Long? = null
) {
    log(logger, contextKey).throwIfInvalid(contextKey)
}