package com.mochame.utils.fixtures

import com.mochame.sync.api.models.HLC
import com.mochame.sync.api.models.NodeId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant


/**
 * Global provider for generating fixed HLCs.
 */
object TestHlcFactory {

    val DEFAULT_NODE = TestNodeId.A

    val BASE_TEST_TIME = HLC.APP_RELEASE_TIME.plus(1.days).toEpochMilliseconds()


    fun create(
        ts: Long = BASE_TEST_TIME,
        count: Int = 0,
        nodeId: NodeId = DEFAULT_NODE
    ): HLC = HLC(ts = ts, count = count, nodeId = nodeId)

    fun createWithOffset(
        offset: Duration = Duration.ZERO,
        count: Int = 0,
        nodeId: NodeId = DEFAULT_NODE
    ): HLC = HLC(
        ts = BASE_TEST_TIME + offset.inWholeMilliseconds,
        count = count,
        nodeId = nodeId
    )

    fun create(
        ts: Instant,
        count: Int = 0,
        nodeId: NodeId = DEFAULT_NODE
    ): HLC = HLC(ts = ts, count = count, nodeId = nodeId)

    /**
     * Generates a list of HLCs with strictly incrementing physical time (ts).
     * Simulates events happening sequentially across different clock ticks.
     */
    fun chronologicalSequence(
        size: Int,
        stepMs: Long = 1L,
        baseTs: Long = BASE_TEST_TIME,
        nodeId: NodeId = DEFAULT_NODE
    ): List<HLC> = List(size) { index ->
        HLC(ts = baseTs + (index * stepMs), count = 0, nodeId = nodeId)
    }

    /**
     * Generates a list of HLCs with identical physical time but incrementing logical counters.
     * Simulates high-frequency concurrent mutations happening on a single device within the same ms tick.
     */
    fun concurrentSequence(
        size: Int,
        ts: Long = BASE_TEST_TIME,
        nodeId: NodeId = DEFAULT_NODE
    ): List<HLC> = List(size) { index ->
        HLC(ts = ts, count = index, nodeId = nodeId)
    }
}