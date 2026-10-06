package com.mochame.sync.data

import com.mochame.sync.api.models.HLC
import com.mochame.sync.api.models.NodeContext
import com.mochame.sync.api.models.NodeId
import kotlin.time.Instant

internal fun NodeContextEntity.toDomain() = NodeContext(
    nodeId = NodeId.parse(nodeId),
    appVersion = appVersion,
    createdAt = createdAt,
    lastInboundWatermark = lastInboundWatermark,
    maxHlc = maxHlc?.let { HLC.parse(maxHlc) },
    lastServerResponseTime = lastServerResponseTime?.let { Instant.fromEpochMilliseconds(it) }
)

internal fun NodeContext.toEntity() = NodeContextEntity(
    id = 1,
    nodeId = nodeId.toString(),
    appVersion = appVersion,
    createdAt = createdAt,
    lastInboundWatermark = lastInboundWatermark,
    maxHlc = maxHlc?.toString(),
    lastServerResponseTime = lastServerResponseTime?.toEpochMilliseconds(),
)
