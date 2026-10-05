package com.mochame.sync.domain.stores

import com.mochame.sync.api.metadata.FeatureContext
import com.mochame.sync.domain.model.ClaimedBatch
import com.mochame.sync.domain.model.SyncIntent

interface SyncIntentStore {
    suspend fun getPendingByCandidateKey(candidateKey: Long): SyncIntent?

    suspend fun recordIntent(entry: SyncIntent)
    suspend fun getPendingByFeature(feature: FeatureContext): List<SyncIntent?>
    suspend fun claimNextBatch(limit: Int = 50): ClaimedBatch?

    suspend fun acknowledgeSuccess(batchId: Long): Int

}