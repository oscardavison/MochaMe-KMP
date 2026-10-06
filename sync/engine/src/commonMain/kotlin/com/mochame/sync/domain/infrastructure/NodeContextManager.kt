package com.mochame.sync.domain.infrastructure

import com.mochame.sync.api.models.HLC
import com.mochame.sync.api.models.NodeContext
import com.mochame.sync.api.models.NodeId
import kotlin.time.Instant


interface NodeContextManager {

    suspend fun getOrEstablishContext(baseVersion: Int = 0): NodeContext

    suspend fun overwriteNodeContext(nodeContext: NodeContext)

    suspend fun setAppVersion(targetVersion: Int)

    suspend fun getLastBootedAppVersion(): Int?

    suspend fun getLastServerResponseTime(): Instant?

    suspend fun getNodeId(): NodeId?

    suspend fun getLastInboundWatermark(): Long?

    suspend fun updateHlcFloor(hlc: HLC)

    suspend fun commitInboundWatermark(
        watermark: Long,
        timestamp: Instant,
    )

    suspend fun commitOutboundWatermark(watermark: Long, timestamp: Instant)

    suspend fun getMaxHlc(): HLC?

}