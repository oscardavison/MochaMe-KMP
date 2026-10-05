package com.mochame.sync.spi.node

import com.mochame.sync.api.models.HLC
import kotlin.time.Clock
import kotlin.time.Instant

data class NodeContext(
    val nodeId: NodeId,
    val appVersion: Int,
    val createdAt: Long = Clock.System.now().toEpochMilliseconds(),
    val lastInboundWatermark: Long? = null,
    val lastOutboundWatermark: Long? = null,
    val maxHlc: HLC? = null,
    val lastServerResponseTime: Instant? = null,
)
