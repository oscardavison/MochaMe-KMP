package com.mochame.sync.domain.hlc

import co.touchlab.kermit.Logger
import com.mochame.logger.LogTags
import com.mochame.logger.withTags
import com.mochame.sync.api.exceptions.MochaException
import com.mochame.sync.api.models.HLC
import com.mochame.sync.api.models.NodeId
import com.mochame.utils.interfaces.TimeUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.annotation.Single
import kotlin.time.Duration.Companion.milliseconds

/**
 * Stateful HLC Engine. Delegates logical state transitions,
 * bounds checking, and hydration reconciliation to [HlcEvaluator].
 */
@Single
internal class EngineHlcFactory(
    private val timeUtils: TimeUtils,
    logger: Logger
) : HlcFactory {

    private val logger =
        logger.withTags(LogTags.Layer.INFRA, LogTags.Domain.SYNC, "HLCFac")

    private data class FactoryState(
        val lastHlc: HLC,
        val nodeId: NodeId,
    )

    private val stateMutex = Mutex()
    private var state: FactoryState? = null

    override suspend fun hydrate(lastKnownHlc: HLC?, currentNodeId: NodeId): HLC =
        stateMutex.withLock {
            state?.let {
                logger.w { "An attempt was made to rehydrate the HLC, finding an existing state: ${it.lastHlc} to $lastKnownHlc." }
                return@withLock it.lastHlc
            }
            val deviceClock = timeUtils.now()

            val (hydratedHlc, validation) = HlcEvaluator.reconcileHydration(
                deviceClock = deviceClock,
                history = lastKnownHlc,
                currentNodeId = currentNodeId
            )
            validation.logAndThrowIfInvalid(logger)

            state = FactoryState(hydratedHlc, currentNodeId)

            logger.i { "HLC successfully hydrated: $hydratedHlc" }
            hydratedHlc
        }

    override suspend fun getNextHlc(): HLC {
        var delayCount = 0

        while (true) {
            val candidateHlc = stateMutex.withLock {
                val currentState = state
                    ?: throw MochaException.Policy.CausalityViolation("Cannot fetchById a timestamp with no pre-existing state.")
                val deviceClock = timeUtils.now()

                HlcEvaluator.computeNextTick(
                    deviceClock = deviceClock.toEpochMilliseconds(),
                    last = currentState.lastHlc,
                    nodeId = currentState.nodeId
                )?.also { next ->
                    HlcEvaluator.validate(next, deviceClock).logAndThrowIfInvalid(logger)
                    state = currentState.copy(lastHlc = next)
                    logger.v { "HLC tick: $next" }
                }
            }

            if (candidateHlc != null) {
                if (delayCount > 0) {
                    logger.w { "Recovered from counter exhaustion. Attempts: $delayCount." }
                }
                return candidateHlc
            }

            delayCount++
            delay(1.milliseconds)
        }
    }

    override suspend fun witness(remoteHlc: HLC) = stateMutex.withLock {
        val currentState = state ?: run {
            logger.w { "Attempt made to witness remote HLC against no internal state." }
            return@withLock
        }
        val deviceClock = timeUtils.now()

        val witnessedHlc = HlcEvaluator.computeWitness(
            deviceClock = deviceClock.toEpochMilliseconds(),
            last = currentState.lastHlc,
            remote = remoteHlc,
            nodeId = currentState.nodeId
        )
        HlcEvaluator.validate(witnessedHlc, deviceClock).logAndThrowIfInvalid(logger)

        logger.v { "HLC witnessed: $witnessedHlc" }
        state = currentState.copy(lastHlc = witnessedHlc)
    }

    override fun assertValid(hlc: HLC, contextKey: Long?) {
        val deviceClock = timeUtils.now()
        HlcEvaluator.validate(hlc, deviceClock).logAndThrowIfInvalid(logger, contextKey)
    }

    override suspend fun getCurrentHlc(): HLC? =
        stateMutex.withLock { state?.lastHlc }
}