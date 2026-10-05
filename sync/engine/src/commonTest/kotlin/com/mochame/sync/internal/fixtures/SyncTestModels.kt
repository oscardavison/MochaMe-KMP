package com.mochame.sync.internal.fixtures

import com.mochame.sync.api.models.HLC
import com.mochame.sync.api.metadata.FeatureContext
import com.mochame.sync.api.metadata.MutationOp
import com.mochame.sync.api.metadata.SyncStatus
import com.mochame.sync.data.SyncIntentEntity
import com.mochame.sync.domain.model.SyncIntent
import com.mochame.utils.fixtures.TestHlcFactory
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

internal fun createTestSyncIntent(
    hlc: HLC = TestHlcFactory.create(),
    candidateKey: Long = 1000L,
    featureContext: FeatureContext = FeatureContext.UNRECOGNIZED_MODEL,
    payload: ByteArray? = byteArrayOf(0x00),
    status: SyncStatus = SyncStatus.PENDING,
    createdAt: Long = 0L,
    batchId: Long? = null,
    leasedAt: Long? = null,
    changedMask: Long? = null,
    overflowBlobId: String? = null,
    featureSchemaVersion: Int = 1,
    op: MutationOp = MutationOp.UPSERT,
    retryCount: Int = 0
) = SyncIntent(
    featureSchemaVersion = featureSchemaVersion,
    hlc = hlc,
    candidateKey = candidateKey,
    featureContext = featureContext,
    operation = op,
    syncStatus = status,
    retryCount = retryCount,
    createdAt = createdAt,
    payload = payload,
    leasedAt = leasedAt,
    batchId = batchId,
    overflowBlobId = overflowBlobId,
    changedMask = changedMask ?: 0L
)

internal fun createTestIntentEntity(
    hlc: HLC = TestHlcFactory.create(),
    status: SyncStatus = SyncStatus.PENDING,
    candidateKey: Long = 1000L,
    batchId: Long? = null,
    overflowBlobId: String? = null,
    createdAt: Long = TestHlcFactory.BASE_TEST_TIME,
    retryCount: Int = 0,
    leasedAt: Long? = TestHlcFactory.BASE_TEST_TIME,
    payload: ByteArray? = byteArrayOf(0x01),
    featureContext: FeatureContext = FeatureContext.UNRECOGNIZED_MODEL,
    changedMask: Long? = null,
) = SyncIntentEntity(
    hlc = hlc.toString(),
    featureSchemaVersion = 1,
    candidateKey = candidateKey,
    featureContext = featureContext,
    operation = MutationOp.UPSERT,
    payload = payload,
    overflowBlobId = overflowBlobId,
    syncStatus = status,
    batchId = batchId,
    leasedAt = leasedAt,
    diagnosticSummary = null,
    retryCount = retryCount,
    lastErrorMessage = null,
    createdAt = createdAt,
    changedMask = changedMask ?: 0L
)

internal fun assertDecodedIntentParity(expected: SyncIntent, actual: SyncIntent) {
    assertEquals(expected.featureSchemaVersion, actual.featureSchemaVersion)
    assertEquals(expected.hlc, actual.hlc)
    assertEquals(expected.candidateKey, actual.candidateKey)
    assertEquals(expected.featureContext, actual.featureContext)
    assertEquals(expected.operation, actual.operation)
    assertEquals(expected.operation.name, actual.operation.name)
    assertEquals(expected.overflowBlobId, actual.overflowBlobId)
    assertEquals(expected.createdAt, actual.createdAt)
    assertEquals(expected.changedMask, actual.changedMask)
    assertEquals(SyncStatus.RECEIVED, actual.syncStatus)
    assertEquals(0, actual.retryCount, "retryCount must reset to 0 upon decode")

    if (expected.payload == null) {
        assertEquals(null, actual.payload)
    } else {
        assertContentEquals(expected.payload, actual.payload)
    }
}

internal fun testBatch(size: Int = 1): List<SyncIntent> = List(size) { index ->
    createTestSyncIntent(candidateKey = index.toLong())
}