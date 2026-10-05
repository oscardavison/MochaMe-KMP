package com.mochame.node.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlin.time.Clock

@Entity(tableName = "node_context")
data class NodeContextEntity(
    @PrimaryKey val id: Int = 1,
    val nodeId: String,
    val appVersion: Int,
    val createdAt: Long = Clock.System.now().toEpochMilliseconds(),
    val maxHlc: String? = null,
    val lastInboundWatermark: Long? = null,
    val lastOutboundWatermark: Long? = null,
    val lastServerResponseTime: Long? = null,
)
