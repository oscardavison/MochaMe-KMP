package com.mochame.sync.domain.stores

import com.mochame.sync.api.hlc.HLC
import com.mochame.sync.spi.models.QuarantinedFeatureSummary
import kotlinx.coroutines.flow.Flow

interface SyncIntentMaintenanceStore : SyncIntentStore {
    suspend fun pruneAgedIntents(pruneAfter: Long, limit: Int): Int
    suspend fun existsForBlob(blobId: String): Boolean
    suspend fun resetStaleLeases(
        cutOff: Long,
        retryThreshold: Int,
        shouldIncrementRetry: Boolean = true
    ): Int

    suspend fun quarantineStaleLeases(cutOff: Long, retryThreshold: Int): Int
    suspend fun cascadeQuarantine(): Int
    suspend fun stampLastError(batchId: Long, message: String)
    suspend fun quarantineIntent(hlc: HLC, candidateKey: Long, errorMessage: String)
    suspend fun releaseIntents(batchId: Long): Int
    suspend fun releaseIntents(hlcs: List<HLC>): Int
    suspend fun observeQuarantinedCountByModule(): Flow<List<QuarantinedFeatureSummary>>
}