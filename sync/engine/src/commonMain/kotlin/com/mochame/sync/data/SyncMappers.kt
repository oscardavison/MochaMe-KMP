package com.mochame.sync.data

import com.mochame.sync.api.models.HLC
import com.mochame.sync.domain.model.SyncIntent

internal fun SyncIntentEntity.toDomain(): SyncIntent = SyncIntent(
    hlc = HLC.parse(hlc),
    featureSchemaVersion = featureSchemaVersion,
    candidateKey = candidateKey,
    featureContext = featureContext,
    operation = operation,
    syncStatus = syncStatus,
    batchId = batchId,
    payload = payload,
    diagnosticSummary = diagnosticSummary,
    overflowBlobId = overflowBlobId,
    retryCount = retryCount,
    createdAt = createdAt,
    changedMask = changedMask,
    leasedAt = leasedAt,
    lastErrorMessage = lastErrorMessage
)

internal fun SyncIntent.toEntity(): SyncIntentEntity = SyncIntentEntity(
    hlc = hlc.toString(),
    featureSchemaVersion = featureSchemaVersion,
    candidateKey = candidateKey,
    featureContext = featureContext,
    operation = operation,
    syncStatus = syncStatus,
    batchId = batchId,
    payload = payload,
    diagnosticSummary = diagnosticSummary,
    overflowBlobId = overflowBlobId,
    retryCount = retryCount,
    createdAt = createdAt,
    changedMask = changedMask,
    leasedAt = leasedAt,
    lastErrorMessage = lastErrorMessage
)
