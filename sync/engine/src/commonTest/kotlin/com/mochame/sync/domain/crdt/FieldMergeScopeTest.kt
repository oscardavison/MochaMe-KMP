package com.mochame.sync.domain.crdt

import co.touchlab.kermit.Logger
import com.mochame.support.MochaPlatformTest
import com.mochame.sync.api.codec.BaseFeatureCodec.Companion.TAG_IS_DELETED
import com.mochame.sync.api.models.HLC
import com.mochame.sync.api.models.NodeId
import com.mochame.sync.api.models.instant
import com.mochame.utils.fixtures.TestHlcFactory
import com.mochame.utils.fixtures.TestNodeId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class FieldMergeScopeTest : MochaPlatformTest() {

    private val testLogger = Logger.withTag("FieldMergeScopeTest")

    private fun emptyMap() = FieldHlcMap(ByteArray(0))

    private fun createHlc(
        ts: Long = 1000L,
        count: Int = 0,
        nodeId: NodeId = TestNodeId.A
    ): HLC = TestHlcFactory.create(ts = ts, count = count, nodeId = nodeId)

    @Test
    fun should_retainExistingValueAndNotUpdateIndex_when_tagAbsentFromChangedMask() {
        // Given
        val localHlc = createHlc(ts = 100L)
        val existingBytes = emptyMap().updateTag(tagId = 4, hlc = localHlc).bytes
        val incomingHlc = createHlc(ts = 500L)
        val scope = FieldMergeScope(
            existingBytes = existingBytes,
            incomingHlc = incomingHlc,
            changedMask = 0L,
            isDelete = false,
            logger = testLogger
        )

        // When
        val result = scope.resolve(tagId = 4, incoming = "newValue", existing = "currentValue")

        // Then
        assertEquals("currentValue", result)
        val resultMap = FieldHlcMap(scope.buildResultBlob())
        assertEquals(localHlc, resultMap.getHlc(tagId = 4))
    }

    @Test
    fun should_acceptIncomingValueAndRecordTag_when_localTagDoesNotExist() {
        // Given
        val incomingHlc = createHlc(ts = 200L)
        val scope = FieldMergeScope(
            existingBytes = ByteArray(0),
            incomingHlc = incomingHlc,
            changedMask = 1L shl 4,
            isDelete = false,
            logger = testLogger
        )

        // When
        val result = scope.resolve(tagId = 4, incoming = "insertedText", existing = null)

        // Then
        assertEquals("insertedText", result)
        val resultMap = FieldHlcMap(scope.buildResultBlob())
        assertEquals(incomingHlc, resultMap.getHlc(tagId = 4))
    }

    @Test
    fun should_acceptIncomingValueAndUpdateTag_when_incomingHlcIsHigherThanLocalTagHlc() {
        // Given
        val localHlc = createHlc(ts = 100L)
        val incomingHlc = createHlc(ts = 200L)
        val existingBytes = emptyMap().updateTag(tagId = 4, hlc = localHlc).bytes
        val scope = FieldMergeScope(
            existingBytes = existingBytes,
            incomingHlc = incomingHlc,
            changedMask = 1L shl 4,
            isDelete = false,
            logger = testLogger
        )

        // When
        val result = scope.resolve(tagId = 4, incoming = "acceptedValue", existing = "oldValue")

        // Then
        assertEquals("acceptedValue", result)
        val resultMap = FieldHlcMap(scope.buildResultBlob())
        assertEquals(incomingHlc, resultMap.getHlc(tagId = 4))
    }

    @Test
    fun should_rejectIncomingValueAndRetainExisting_when_incomingHlcIsLowerThanLocalTagHlc() {
        // Given
        val localHlc = createHlc(ts = 500L)
        val staleIncomingHlc = createHlc(ts = 200L)
        val existingBytes = emptyMap().updateTag(tagId = 4, hlc = localHlc).bytes
        val scope = FieldMergeScope(
            existingBytes = existingBytes,
            incomingHlc = staleIncomingHlc,
            changedMask = 1L shl 4,
            isDelete = false,
            logger = testLogger
        )

        // When
        val result = scope.resolve(tagId = 4, incoming = "staleValue", existing = "winnerValue")

        // Then
        assertEquals("winnerValue", result)
        val resultMap = FieldHlcMap(scope.buildResultBlob())
        assertEquals(localHlc, resultMap.getHlc(tagId = 4))
    }

    @Test
    fun should_rejectIncomingValueAndRetainExisting_when_incomingHlcIsOlderThanLocalDeleteHorizon() {
        // Given
        val deleteHlc = createHlc(ts = 300L)
        val existingBytes = emptyMap().updateTag(tagId = TAG_IS_DELETED, hlc = deleteHlc).bytes
        val staleIncomingHlc = createHlc(ts = 200L)
        val scope = FieldMergeScope(
            existingBytes = existingBytes,
            incomingHlc = staleIncomingHlc,
            changedMask = 1L shl 4,
            isDelete = false,
            logger = testLogger
        )

        // When
        val result = scope.resolve(tagId = 4, incoming = "staleUnderDelete", existing = null)

        // Then
        assertNull(result)
        val resultMap = FieldHlcMap(scope.buildResultBlob())
        assertNull(resultMap.getHlc(tagId = 4))
    }

    @Test
    fun should_nullifyOlderFieldAndRecordTag_when_deltaIsDeletion() {
        // Given
        val olderHlc = createHlc(ts = 100L)
        val deleteHlc = createHlc(ts = 200L)
        val existingBytes = emptyMap().updateTag(tagId = 4, hlc = olderHlc).bytes
        val scope = FieldMergeScope(
            existingBytes = existingBytes,
            incomingHlc = deleteHlc,
            changedMask = 0L,
            isDelete = true,
            logger = testLogger
        )

        // When
        val result = scope.resolve(tagId = 4, incoming = null, existing = "activeValue")

        // Then
        assertNull(result)
        val resultMap = FieldHlcMap(scope.buildResultBlob())
        assertEquals(deleteHlc, resultMap.getHlc(tagId = 4))
    }

    @Test
    fun should_retainExistingField_when_localEditOccurredStrictlyAfterDeleteHorizon() {
        // Given
        val newerLocalHlc = createHlc(ts = 300L)
        val olderDeleteHlc = createHlc(ts = 200L)
        val existingBytes = emptyMap().updateTag(tagId = 4, hlc = newerLocalHlc).bytes
        val scope = FieldMergeScope(
            existingBytes = existingBytes,
            incomingHlc = olderDeleteHlc,
            changedMask = 0L,
            isDelete = true,
            logger = testLogger
        )

        // When
        val result = scope.resolve(tagId = 4, incoming = null, existing = "survivingValue")

        // Then
        assertEquals("survivingValue", result)
        val resultMap = FieldHlcMap(scope.buildResultBlob())
        assertEquals(newerLocalHlc, resultMap.getHlc(tagId = 4))
    }

    @Test
    fun should_nullifyFieldAndRecordTag_when_deltaIsDeletionAndLocalTagDoesNotExist() {
        // Given
        val deleteHlc = createHlc(ts = 200L)
        val scope = FieldMergeScope(
            existingBytes = ByteArray(0),
            incomingHlc = deleteHlc,
            changedMask = 0L,
            isDelete = true,
            logger = testLogger
        )

        // When
        val result = scope.resolve(tagId = 4, incoming = null, existing = null)

        // Then
        assertNull(result)
        val resultMap = FieldHlcMap(scope.buildResultBlob())
        assertEquals(deleteHlc, resultMap.getHlc(tagId = 4))
    }

    @Test
    fun should_returnTrue_when_resolvingExplicitDeleteWithoutSurvivingFields() {
        // Given
        val deleteHlc = createHlc(ts = 200L)
        val scope = FieldMergeScope(
            existingBytes = ByteArray(0),
            incomingHlc = deleteHlc,
            changedMask = 0L,
            isDelete = true,
            logger = testLogger
        )

        // When
        val deleteState = scope.resolveDeleteState(
            deltaIsDeleted = true,
            existingIsDeleted = false,
            candidateKey = 1L
        )

        // Then
        assertTrue(deleteState)
        val resultMap = FieldHlcMap(scope.buildResultBlob())
        assertEquals(deleteHlc, resultMap.getHlc(tagId = TAG_IS_DELETED))
    }

    @Test
    fun should_returnFalseAndUpdateDeleteHorizon_when_resolvingExplicitDeleteWithSurvivingFields() {
        // Given
        val survivingFieldHlc = createHlc(ts = 300L)
        val deleteHlc = createHlc(ts = 200L)
        val existingBytes = emptyMap().updateTag(tagId = 5, hlc = survivingFieldHlc).bytes
        val scope = FieldMergeScope(
            existingBytes = existingBytes,
            incomingHlc = deleteHlc,
            changedMask = 0L,
            isDelete = true,
            logger = testLogger
        )

        // When
        val deleteState = scope.resolveDeleteState(
            deltaIsDeleted = true,
            existingIsDeleted = false,
            candidateKey = 1L
        )

        // Then: surviving field keeps entity active
        assertFalse(deleteState)
        assertEquals(deleteHlc, FieldHlcMap(scope.buildResultBlob()).getHlc(TAG_IS_DELETED))
    }

    @Test
    fun should_preserveExistingDeleteState_when_explicitDeleteIsStale() {
        // Given
        val localDeleteHlc = createHlc(ts = 500L)
        val staleDeleteHlc = createHlc(ts = 200L)
        val existingBytes = emptyMap().updateTag(tagId = TAG_IS_DELETED, hlc = localDeleteHlc).bytes
        val scope = FieldMergeScope(
            existingBytes = existingBytes,
            incomingHlc = staleDeleteHlc,
            changedMask = 0L,
            isDelete = true,
            logger = testLogger
        )

        // When
        val deleteState = scope.resolveDeleteState(
            deltaIsDeleted = true,
            existingIsDeleted = false,
            candidateKey = 1L
        )

        // Then
        assertFalse(deleteState)
        assertContentEquals(existingBytes, scope.buildResultBlob())
    }

    @Test
    fun should_restoreEntityAndMaintainDeleteHorizon_when_explicitUndeleteArrivesWithNewerHlc() {
        // Given
        val localDeleteHlc = createHlc(ts = 200L)
        val restoreHlc = createHlc(ts = 300L)
        val existingBytes = emptyMap().updateTag(tagId = TAG_IS_DELETED, hlc = localDeleteHlc).bytes
        val scope = FieldMergeScope(
            existingBytes = existingBytes,
            incomingHlc = restoreHlc,
            changedMask = 0L,
            isDelete = false,
            logger = testLogger
        )

        // When
        val deleteState = scope.resolveDeleteState(
            deltaIsDeleted = false,
            existingIsDeleted = true,
            candidateKey = 1L
        )

        // Then
        assertFalse(deleteState)
        assertContentEquals(existingBytes, scope.buildResultBlob())
    }

    @Test
    fun should_implicitlyReviveEntity_when_incomingUpsertIsNewerThanDelete() {
        // Given
        val localDeleteHlc = createHlc(ts = 200L)
        val upsertHlc = createHlc(ts = 300L)
        val existingBytes = emptyMap().updateTag(tagId = TAG_IS_DELETED, hlc = localDeleteHlc).bytes
        val scope = FieldMergeScope(
            existingBytes = existingBytes,
            incomingHlc = upsertHlc,
            changedMask = 1L shl 4,
            isDelete = false,
            logger = testLogger
        )

        // When
        val deleteState = scope.resolveDeleteState(
            deltaIsDeleted = null,
            existingIsDeleted = true,
            candidateKey = 1L
        )

        // Then
        assertFalse(deleteState)
        assertContentEquals(existingBytes, scope.buildResultBlob())
    }

    @Test
    fun should_preserveDeleteState_when_incomingUpsertIsOlderThanDelete() {
        // Given
        val localDeleteHlc = createHlc(ts = 500L)
        val staleUpsertHlc = createHlc(ts = 200L)
        val existingBytes = emptyMap().updateTag(tagId = TAG_IS_DELETED, hlc = localDeleteHlc).bytes
        val scope = FieldMergeScope(
            existingBytes = existingBytes,
            incomingHlc = staleUpsertHlc,
            changedMask = 1L shl 4,
            isDelete = false,
            logger = testLogger
        )

        // When
        val deleteState = scope.resolveDeleteState(
            deltaIsDeleted = null,
            existingIsDeleted = true,
            candidateKey = 1L
        )

        // Then
        assertTrue(deleteState)
        assertContentEquals(existingBytes, scope.buildResultBlob())
    }

    @Test
    fun should_resolveCreatedAtCorrectly_when_testingAllCombinations() {
        // Given
        val hlc = createHlc(ts = 1500L)
        val scope = FieldMergeScope(
            existingBytes = ByteArray(0),
            incomingHlc = hlc,
            changedMask = 0L,
            isDelete = false,
            logger = testLogger
        )

        val t1000 = Instant.fromEpochMilliseconds(1000L)
        val t2000 = Instant.fromEpochMilliseconds(2000L)

        // When & Then
        // 1. Initial insert: existing is null, delta is present
        assertEquals(
            t1000,
            scope.resolveCreatedAt(deltaCreatedAt = 1000L, existingCreatedAt = null)
        )

        // 2. Existing present, delta is null
        assertEquals(
            t1000,
            scope.resolveCreatedAt(deltaCreatedAt = null, existingCreatedAt = t1000)
        )

        // 3. Both present: minOf(existing, delta)
        assertEquals(
            t1000,
            scope.resolveCreatedAt(deltaCreatedAt = 2000L, existingCreatedAt = t1000)
        )
        assertEquals(
            t1000,
            scope.resolveCreatedAt(deltaCreatedAt = 1000L, existingCreatedAt = t2000)
        )

        // 4. Both null: fallback to incomingHlc
        assertEquals(
            hlc.instant,
            scope.resolveCreatedAt(deltaCreatedAt = null, existingCreatedAt = null)
        )
    }

    @Test
    fun should_batchUpdateTagsInSinglePass_when_buildingResultBlob() {
        // Given
        val hlc100 = createHlc(ts = 100L)
        val hlc200 = createHlc(ts = 200L)
        val existingBytes = emptyMap()
            .updateTag(tagId = 4, hlc = hlc100)
            .updateTag(tagId = 5, hlc = hlc200)
            .bytes

        val incomingHlc = createHlc(ts = 300L)
        val mask = (1L shl 4) or (1L shl 5) or (1L shl 6)
        val scope = FieldMergeScope(
            existingBytes = existingBytes,
            incomingHlc = incomingHlc,
            changedMask = mask,
            isDelete = false,
            logger = testLogger
        )

        // When
        val f4 = scope.resolve(tagId = 4, incoming = "v4_new", existing = "v4_old")
        val f5 = scope.resolve(tagId = 5, incoming = "v5_new", existing = "v5_old")
        val f6 = scope.resolve(tagId = 6, incoming = "v6_new", existing = null)

        // Then
        assertEquals("v4_new", f4)
        assertEquals("v5_new", f5)
        assertEquals("v6_new", f6)

        val resultMap = FieldHlcMap(scope.buildResultBlob())
        assertEquals(FieldHlcMap.RECORD_SIZE * 3, resultMap.bytes.size)
        assertEquals(incomingHlc, resultMap.getHlc(tagId = 4))
        assertEquals(incomingHlc, resultMap.getHlc(tagId = 5))
        assertEquals(incomingHlc, resultMap.getHlc(tagId = 6))
    }
}
