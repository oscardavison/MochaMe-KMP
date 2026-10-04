package com.mochame.sync.domain.stores

import com.mochame.sync.domain.model.QuarantinedPayload

interface QuarantinedPayloadStore {
    suspend fun record(watermark: Long, rawPayload: ByteArray, failureReason: String)
    suspend fun getAll(): List<QuarantinedPayload>
    suspend fun getByWatermark(watermark: Long): QuarantinedPayload?
    suspend fun deleteByWatermark(watermark: Long)
    suspend fun pruneOlderThan(timestamp: Long): Int
}