package com.mochame.node.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert


@Dao
interface NodeContextDao {

    @Transaction
    suspend fun getOrEstablish(
        fallbackId: String,
        baseVersion: Int,
        createdAt: Long
    ): NodeContextEntity {
        return getContext() ?: NodeContextEntity(
            id = 1,
            nodeId = fallbackId,
            appVersion = baseVersion,
            createdAt = createdAt
        ).also { insertOrReplaceContext(it) }
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrReplaceContext(nodeContext: NodeContextEntity)

    @Query("SELECT * FROM node_context WHERE id = 1")
    suspend fun getContext(): NodeContextEntity?

    @Query("SELECT nodeId FROM node_context WHERE id = 1")
    suspend fun getNodeId(): String?

    @Query("SELECT maxHlc FROM node_context WHERE id = 1")
    suspend fun getMaxHlc(): String?

    @Query("SELECT appVersion FROM node_context WHERE id = 1")
    suspend fun getLastBootedVersion(): Int?

    @Query("SELECT lastServerResponseTime FROM node_context WHERE id = 1")
    suspend fun getLastServerResponseTime(): Long?

    @Query("SELECT lastInboundWatermark FROM node_context WHERE id = 1")
    suspend fun getLastInboundWatermark(): Long?

    @Query("SELECT lastOutboundWatermark FROM node_context WHERE id = 1")
    suspend fun getLastOutboundWatermark(): Long?

    @Query("""
        UPDATE node_context
        SET lastInboundWatermark = :watermark, 
            lastServerResponseTime = :timeStamp  
        WHERE id = 1  AND (:watermark > lastInboundWatermark OR lastInboundWatermark IS NULL)
    """)
    suspend fun setInboundWatermark(watermark: Long, timeStamp: Long): Int

    @Query("""
        UPDATE node_context
        SET lastOutboundWatermark = :watermark, 
            lastServerResponseTime = :timeStamp  
        WHERE id = 1  AND (:watermark > lastOutboundWatermark OR lastOutboundWatermark IS NULL)
    """)
    suspend fun setOutboundWatermark(watermark: Long, timeStamp: Long)

    @Query("UPDATE node_context SET maxHlc = :hlc WHERE id = 1 AND (maxHlc IS NULL OR :hlc > maxHlc)")
    suspend fun setMaxHlc(hlc: String): Int

    @Upsert
    suspend fun upsert(entity: NodeContextEntity)

    @Query("UPDATE node_context SET appVersion = :version WHERE id = 1")
    suspend fun setVersion(version: Int)

}