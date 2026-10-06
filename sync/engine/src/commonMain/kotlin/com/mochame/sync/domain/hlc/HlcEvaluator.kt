package com.mochame.sync.domain.hlc

import com.mochame.sync.api.models.HLC
import com.mochame.sync.api.models.HLC.Companion.APP_RELEASE_TIME
import com.mochame.sync.api.models.HLC.Companion.MAX_DRIFT
import com.mochame.sync.api.models.instant
import com.mochame.sync.api.models.NodeId
import kotlin.time.Instant

/**
 * Stateless evaluator for HLC operations.
 * Implements HLC time progression, hydration reconciliation, and bounds validation.
 */
internal object HlcEvaluator {

    /**
     * Calculates the next logical tick for local event generation.
     * Returns null if counter exhaustion occurs for the current millisecond.
     */
    fun computeNextTick(
        deviceClock: Long,
        last: HLC,
        nodeId: NodeId
    ): HLC? {
        val nextTs = maxOf(deviceClock, last.ts)
        val nextCount = if (nextTs == last.ts) last.count + 1 else 0

        if (nextCount > HLC.MAX_COUNTER_INT) return null

        return HLC(nextTs, nextCount, nodeId)
    }

    /**
     * Adjusts internal HLC baseline upon observing remote data.
     */
    fun computeWitness(
        deviceClock: Long,
        last: HLC,
        remote: HLC,
        nodeId: NodeId
    ): HLC {
        val newTs = maxOf(deviceClock, last.ts, remote.ts)

        val newCount = when {
            newTs == last.ts && newTs == remote.ts -> maxOf(last.count, remote.count)
            newTs == remote.ts -> remote.count
            newTs == last.ts -> last.count
            else -> 0
        }

        return HLC(newTs, newCount, nodeId)
    }

    /**
     * Inspects a remote HLC against constraints and maximum drift limits,
     * without raising exceptions.
     */
    fun validate(hlc: HLC, deviceClock: Instant): HlcEvaluation {
        if (hlc.count > HLC.MAX_COUNTER_INT)
            return HlcEvaluation.CounterOverflow(
                count = hlc.count,
                limit = HLC.MAX_COUNTER_INT
            )

        captureFloorViolation(hlc.instant)?.let { return it }
        captureFutureDrift(hlc.instant, deviceClock)?.let { return it }

        return HlcEvaluation.Valid(
            hlcInstant = hlc.instant,
            deviceClock = deviceClock,
            drift = hlc.instant - deviceClock
        )
    }

    /**
     * Reconciles physical time against persisted historical HLC state during startup.
     *
     * @throws com.mochame.sync.api.exceptions.MochaException.Persistent.ClockSkew if device clock is before APP_RELEASE_TIME
     *         or if persisted history is beyond MAX_DRIFT in the future.
     */
    fun reconcileHydration(
        deviceClock: Instant,
        history: HLC?,
        currentNodeId: NodeId
    ): Pair<HLC, HlcEvaluation> {
        captureFloorViolation(deviceClock)?.let { return (history ?: HLC.EMPTY) to it }

        if (history == null) {
            val hydrationHlc = HLC(deviceClock.toEpochMilliseconds(), 0, currentNodeId)
            return hydrationHlc to HlcEvaluation.NewInstall(hydrationHlc.instant)
        }

        captureFutureDrift(history.instant, deviceClock)?.let { return history to it }

        val finalInstant = maxOf(deviceClock, history.instant)
        val finalCounter = if (finalInstant == history.instant) history.count else 0
        val finalHlc = HLC(finalInstant.toEpochMilliseconds(), finalCounter, currentNodeId)

        return finalHlc to HlcEvaluation.Valid(
            finalInstant,
            deviceClock,
            finalInstant - deviceClock,
            deviceClock - history.instant
        )
    }

    // --- Helpers ---

    private fun captureFloorViolation(instant: Instant): HlcEvaluation.PrecedesAppRelease? {
        if (instant < APP_RELEASE_TIME) {
            val drift = APP_RELEASE_TIME - instant
            return HlcEvaluation.PrecedesAppRelease(
                hlcInstant = instant,
                releaseFloor = APP_RELEASE_TIME,
                drift = drift
            )
        }
        return null
    }

    fun captureFutureDrift(
        hlcInstant: Instant,
        deviceClock: Instant
    ): HlcEvaluation.ExceedsMaxDrift? {
        val drift = hlcInstant - deviceClock
        if (drift > MAX_DRIFT) {
            return HlcEvaluation.ExceedsMaxDrift(
                hlcInstant = hlcInstant,
                deviceClock = deviceClock,
                drift = drift
            )
        }
        return null
    }
}