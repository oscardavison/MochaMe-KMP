package com.mochame.node.policies

import co.touchlab.kermit.Logger
import com.mochame.logger.LogTags
import com.mochame.logger.withTags
import com.mochame.logger.withTimer
import com.mochame.sync.api.exceptions.MochaException
import com.mochame.sync.api.exceptions.toMochaException
import com.mochame.sync.spi.policy.ExecutionPolicy
import kotlinx.coroutines.delay
import org.koin.core.annotation.Single
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Execution policy for the local database.
 *
 * Developed before realizing Pragma timeout
 */
@Single(binds = [ExecutionPolicy::class])
class StaggeredDbRetryPolicy(
    logger: Logger,
    private val maxAttempts: Int = MAX_ATTEMPTS,
    private val initialDelay: Duration = INITIAL_DELAY
) : ExecutionPolicy {

    companion object {
        private const val MAX_ATTEMPTS = 5
        private val INITIAL_DELAY = 10.milliseconds
    }

    private val logger =
        logger.withTags(LogTags.Layer.ORCH, LogTags.Domain.POLICY, "Exectr")

    /**
     * Any block passed to StaggeredDbRetryPolicy must encapsulate their own atomicity configuration.
     * On staggering a retry, this policy will not ensure idempotency of total execution.
     */
    override suspend fun <R> execute(operationTag: String, block: suspend () -> R): R {
        val mark = TimeSource.Monotonic.markNow()
        var currentDelay = initialDelay

        repeat(maxAttempts - 1) { attempt ->
            try {
                return block().also {
                    if (attempt > 0)
                        logger.i { "$operationTag Recovered: ${attempt + 1} attempts".withTimer(mark) }
                }
            } catch (e: Exception) {
                val mochaError = e.toMochaException(operationTag)

                if (mochaError is MochaException.Transient.DatabaseBusy) {
                    if (attempt == 0)
                        logger.w { "$operationTag Database busy. Staggering retry".withTimer(mark) }

                    delay(currentDelay * Random.nextDouble(1.0, 1.5))
                    currentDelay *= 2

                } else {
                    logger.e(e) { "$operationTag Aborted. ${e.message}" }
                    throw mochaError
                }
            }
        }

        return try {
            block()
        } catch (e: Exception) {
            val finalError = e.toMochaException(operationTag)
            logger.e(e) { "$operationTag. Exhausted $maxAttempts retries".withTimer(mark) + " | ${finalError.message}" }
            throw finalError
        }
    }
}