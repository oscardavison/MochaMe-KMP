package com.mochame.node.data

import com.mochame.utils.fixtures.TestHlcFactory
import com.mochame.sync.api.models.HLC
import com.mochame.sync.spi.node.NodeContext
import com.mochame.sync.spi.node.NodeId
import com.mochame.utils.fixtures.TestNodeId
import kotlin.time.Instant

/**
 * Generates a deterministic NodeContextEntity with standard test defaults.
 */
fun createTestNodeContextEntity(
    nodeId: String = TestNodeId.A.toString(),
    appVersion: Int = 1,
    createdAt: Long = TestHlcFactory.BASE_TEST_TIME,
    lastInboundWatermark: Long? = null,
    maxHlc: String? = null,
    lastServerResponseTime: Instant? = null,
): NodeContextEntity = NodeContextEntity(
    id = 1,
    nodeId = nodeId,
    appVersion = appVersion,
    createdAt = createdAt,
    lastInboundWatermark = lastInboundWatermark,
    maxHlc = maxHlc,
    lastServerResponseTime = lastServerResponseTime?.toEpochMilliseconds(),
)

/**
 * Generates a deterministic domain NodeContext with standard test defaults.
 */
fun createTestNodeContext(
    nodeId: NodeId = TestNodeId.A,
    appVersion: Int = 1,
    createdAt: Long = TestHlcFactory.BASE_TEST_TIME,
    lastInboundWatermark: Long? = null,
    maxHlc: HLC? = null,
    lastServerResponseTime: Instant? = null,
): NodeContext = NodeContext(
    nodeId = nodeId,
    appVersion = appVersion,
    createdAt = createdAt,
    lastInboundWatermark = lastInboundWatermark,
    maxHlc = maxHlc,
    lastServerResponseTime = lastServerResponseTime,
)