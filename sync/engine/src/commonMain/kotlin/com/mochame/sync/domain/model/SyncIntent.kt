package com.mochame.sync.domain.model

import com.mochame.sync.api.metadata.MutationOp
import com.mochame.sync.api.models.HLC
import com.mochame.sync.api.metadata.FeatureContext

data class SyncIntent(
    val featureSchemaVersion: Int,
    val hlc: HLC,
    val candidateKey: Long,
    val featureContext: FeatureContext,
    val operation: MutationOp,
    val createdAt: Long,
    val changedMask: Long,
    val batchId: Long? = null,
    val payload: ByteArray? = null,
    val diagnosticSummary: String? = null,
    val overflowBlobId: String? = null,
    val syncStatus: SyncStatus,
    val retryCount: Int = 0,
    val leasedAt: Long? = null,
    val lastErrorMessage: String? = null
)