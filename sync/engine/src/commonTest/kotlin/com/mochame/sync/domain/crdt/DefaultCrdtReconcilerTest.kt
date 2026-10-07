package com.mochame.sync.domain.crdt

import co.touchlab.kermit.Logger
import com.mochame.support.MochaPlatformTest
import com.mochame.sync.api.metadata.MutationOp
import com.mochame.sync.api.models.HLC
import com.mochame.sync.domain.model.DecodeContext
import com.mochame.sync.internal.fixtures.serialization.IntegratedResolver
import com.mochame.sync.internal.fixtures.serialization.FeatureCodecV1
import com.mochame.sync.internal.fixtures.serialization.FeatureEntity
import com.mochame.sync.spi.BufferProvider
import com.mochame.sync.utils.hasTag
import com.mochame.sync.utils.toBitmask
import com.mochame.utils.fixtures.TestHlcFactory
import kotlinx.io.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class DefaultCrdtReconcilerTest : MochaPlatformTest() {

    private val testLogger = Logger.withTag("DefaultCrdtReconcilerTest")

    private val bufferProvider = object : BufferProvider {
        private val buffer = Buffer()
        override fun get(): Buffer = buffer
    }

    private val codec = FeatureCodecV1(bufferProvider, testLogger)
    private val integratedResolver = IntegratedResolver(codec, testLogger)
    private val reconciler = DefaultCrdtReconciler(testLogger)

    private fun makeInbound(
        new: FeatureEntity,
        old: FeatureEntity?,
        hlc: HLC,
        op: MutationOp
    ): Pair<DecodeContext, ByteArray> {
        val payload = codec.encode(new, old)
        val changedTags = codec.computeChangedTags(new, old)
        val context = DecodeContext(
            featureSchemaVersion = 1,
            primaryKey = new.id,
            hlc = hlc,
            op = op,
            changedMask = changedTags.toBitmask()
        )
        return context to payload
    }

    // ===================================================================
    // CRDT CONVERGENCE TESTS (Migrated from LocalFirstRepositoryTest)
    // ===================================================================

    @Test
    fun should_convergeIdentically_when_deleteAt50AndEditAt45ArriveOutOfOrder() {
        // Given
        val key1 = 2051L
        val key2 = 2052L

        val hlc10 = TestHlcFactory.createWithOffset((-60).minutes)
        val hlc45 = TestHlcFactory.createWithOffset((-45).minutes)
        val hlc50 = TestHlcFactory.createWithOffset((-40).minutes)

        val init1 = FeatureEntity(id = key1, hlc = hlc10, textValue = "BASE", countValue = 10)
        val init2 = FeatureEntity(id = key2, hlc = hlc10, textValue = "BASE", countValue = 10)
        val (insCtx1, insPay1) = makeInbound(init1, null, hlc10, MutationOp.UPSERT)
        val (insCtx2, insPay2) = makeInbound(init2, null, hlc10, MutationOp.UPSERT)
        val seeded1 = reconciler.resolveInbound(insPay1, insCtx1, null, integratedResolver)
        val seeded2 = reconciler.resolveInbound(insPay2, insCtx2, null, integratedResolver)

        val edit1 = seeded1.copy(hlc = hlc45, textValue = "CONCURRENT")
        val edit2 = seeded2.copy(hlc = hlc45, textValue = "CONCURRENT")
        val (editCtx1, editPay1) = makeInbound(edit1, seeded1, hlc45, MutationOp.UPSERT)
        val (editCtx2, editPay2) = makeInbound(edit2, seeded2, hlc45, MutationOp.UPSERT)

        val del1 = seeded1.withDeleteState(true).copy(hlc = hlc50)
        val del2 = seeded2.withDeleteState(true).copy(hlc = hlc50)
        val (delCtx1, delPay1) = makeInbound(del1, seeded1, hlc50, MutationOp.DELETE)
        val (delCtx2, delPay2) = makeInbound(del2, seeded2, hlc50, MutationOp.DELETE)

        // When: Replica 1 receives Delete@50 then Edit@45
        val r1AfterDel = reconciler.resolveInbound(delPay1, delCtx1, seeded1, integratedResolver)
        val replica1Final =
            reconciler.resolveInbound(editPay1, editCtx1, r1AfterDel, integratedResolver)

        // When: Replica 2 receives Edit@45 then Delete@50
        val r2AfterEdit = reconciler.resolveInbound(editPay2, editCtx2, seeded2, integratedResolver)
        val replica2Final =
            reconciler.resolveInbound(delPay2, delCtx2, r2AfterEdit, integratedResolver)

        // Then: Delete@50 dominates Edit@45 regardless of arrival sequence
        assertEquals(replica1Final.isDeleted, replica2Final.isDeleted)
        assertTrue(replica1Final.isDeleted)
        assertEquals(replica1Final.textValue, replica2Final.textValue)
        assertNull(replica1Final.textValue)
        assertEquals(replica1Final.hlc, replica2Final.hlc)
    }

    @Test
    fun should_rejectSafelyWithoutCrashing_when_remoteDeleteArrivesBeforeInsert() {
        // Given
        val candidateKey = 206L
        val deleteHlc = TestHlcFactory.createWithOffset((-20).minutes)
        val dummy = FeatureEntity(id = candidateKey, hlc = deleteHlc)
        val deletion = dummy.withDeleteState(true).copy(hlc = deleteHlc)
        val (delCtx, delPayload) = makeInbound(deletion, dummy, deleteHlc, MutationOp.DELETE)

        // When
        val resolved = reconciler.resolveInbound(delPayload, delCtx, null, integratedResolver)

        // Then
        assertNotNull(resolved)
        assertTrue(resolved.isDeleted)
        assertNull(resolved.textValue)
        assertNull(resolved.countValue)
        assertEquals(deleteHlc, resolved.hlc)
    }

    @Test
    fun should_convergeDeterministically_when_offlineDeleteInterleavedWithConcurrentEdits() {
        // Given
        val candidateKey = 211L
        val hlc10 = TestHlcFactory.createWithOffset((-50).minutes)
        val hlc20 = TestHlcFactory.createWithOffset((-40).minutes)
        val hlc25 = TestHlcFactory.createWithOffset((-35).minutes)
        val hlc30 = TestHlcFactory.createWithOffset((-30).minutes)

        // 1. Initial synced state @ HLC 10
        val initial = FeatureEntity(
            id = candidateKey,
            hlc = hlc10,
            isDeleted = false,
            textValue = "SLEEP_5",
            countValue = 2
        )
        val (initCtx, initPayload) = makeInbound(initial, null, hlc10, MutationOp.UPSERT)
        val seeded = reconciler.resolveInbound(initPayload, initCtx, null, integratedResolver)

        // 2. Peer B edit on countValue @ HLC 25
        val peerBEdit = seeded.copy(hlc = hlc25, countValue = 99)
        val (editCtx, editPayload) = makeInbound(peerBEdit, seeded, hlc25, MutationOp.UPSERT)
        val afterPeerB = reconciler.resolveInbound(editPayload, editCtx, seeded, integratedResolver)

        // 3. Peer A offline delete @ HLC 20 arrives (older than edit @ 25, newer than initial @ 10)
        val offlineDelete = seeded.withDeleteState(true).copy(hlc = hlc20)
        val (delCtx, delPayload) = makeInbound(offlineDelete, seeded, hlc20, MutationOp.DELETE)
        val afterDelete =
            reconciler.resolveInbound(delPayload, delCtx, afterPeerB, integratedResolver)

        // Verify intermediate state: countValue at HLC 25 survived delete horizon at HLC 20
        assertFalse(afterDelete.isDeleted)
        assertNull(afterDelete.textValue)
        assertEquals(99, afterDelete.countValue)

        // 4. Peer A post-delete update arrives @ HLC 30 updating textValue
        val peerAUpdate = initial.copy(
            hlc = hlc30,
            textValue = "READINESS_3",
            countValue = null
        )
        val (updateCtx, updatePayload) = makeInbound(
            peerAUpdate,
            offlineDelete,
            hlc30,
            MutationOp.UPSERT
        )
        val finalState =
            reconciler.resolveInbound(updatePayload, updateCtx, afterDelete, integratedResolver)

        // Then: Final state verification
        assertFalse(finalState.isDeleted)
        assertEquals("READINESS_3", finalState.textValue)
        assertEquals(99, finalState.countValue)
        assertEquals(hlc30, finalState.hlc)
    }

    @Test
    fun should_reviveEntityViaStructuralWitnessTags_on_fieldLessRestore() {
        // Given
        val candidateKey = 208L
        val hlc10 = TestHlcFactory.createWithOffset((-50).minutes)
        val hlc20 = TestHlcFactory.createWithOffset((-40).minutes)
        val hlc30 = TestHlcFactory.createWithOffset((-30).minutes)

        val initial =
            FeatureEntity(id = candidateKey, hlc = hlc10, textValue = "INIT", countValue = 10)
        val (insCtx, insPayload) = makeInbound(initial, null, hlc10, MutationOp.UPSERT)
        val seeded = reconciler.resolveInbound(insPayload, insCtx, null, integratedResolver)

        val deletion = seeded.withDeleteState(true).copy(hlc = hlc20)
        val (delCtx, delPayload) = makeInbound(deletion, seeded, hlc20, MutationOp.DELETE)
        val deletedState = reconciler.resolveInbound(delPayload, delCtx, seeded, integratedResolver)

        // When: Restore arrives at HLC 30 with null domain fields
        val fieldLessRestore = deletion.withDeleteState(false).copy(
            hlc = hlc30,
            textValue = null,
            countValue = null
        )
        val (resCtx, resPayload) = makeInbound(fieldLessRestore, deletion, hlc30, MutationOp.UPSERT)
        val restored =
            reconciler.resolveInbound(resPayload, resCtx, deletedState, integratedResolver)

        // Then: Structural tags keep entity alive even with null domain fields
        assertFalse(restored.isDeleted)
        assertNull(restored.textValue)
        assertNull(restored.countValue)
        assertEquals(hlc30, restored.hlc)
    }

    @Test
    fun should_processTotalMergeSubsequentRemoteUpsert_on_legacyActiveRow() {
        // Given
        val candidateKey = 209L
        val hlc10 = TestHlcFactory.createWithOffset((-50).minutes)
        val hlc30 = TestHlcFactory.createWithOffset((-30).minutes)

        val legacyEntity = FeatureEntity(
            id = candidateKey,
            hlc = hlc10,
            isDeleted = false,
            fieldHlcs = ByteArray(0),
            textValue = "LEGACY_TEXT",
            countValue = 10
        )

        // When: Subsequent partial update modifies countValue
        val update = legacyEntity.copy(hlc = hlc30, countValue = 20)
        val (editCtx, editPayload) = makeInbound(update, legacyEntity, hlc30, MutationOp.UPSERT)
        val merged =
            reconciler.resolveInbound(editPayload, editCtx, legacyEntity, integratedResolver)

        // Then: Active entity remains active after subsequent partial sync
        assertFalse(merged.isDeleted)
        assertEquals("LEGACY_TEXT", merged.textValue)
        assertEquals(20, merged.countValue)
        assertEquals(hlc30, merged.hlc)
    }

    // ===================================================================
    // PREPARE OUTBOUND TESTS
    // ===================================================================

    @Test
    fun should_produceOutboundContextWithStampedHlcAndBitmask_when_fieldsChanged() {
        // Given
        val hlc10 = TestHlcFactory.create(ts = 1000L)
        val hlc20 = TestHlcFactory.create(ts = 2000L)
        val existing = FeatureEntity(id = 101L, hlc = hlc10, textValue = "OLD", countValue = 1)
        val candidate = existing.copy(textValue = "NEW")

        // When
        val outbound = reconciler.prepareOutbound(
            candidateState = candidate,
            existingState = existing,
            hlc = hlc20,
            op = MutationOp.UPSERT,
            codecResolver = integratedResolver
        )

        // Then
        assertNotNull(outbound)
        assertEquals(hlc20, outbound.hlc)
        assertTrue(outbound.changedMask.hasTag(FeatureCodecV1.TAG_TEXT_VALUE))
        assertFalse(outbound.changedMask.hasTag(FeatureCodecV1.TAG_COUNT_VALUE))
        assertTrue(outbound.payload.isNotEmpty())

        val fieldMap = FieldHlcMap(outbound.stampedState.fieldHlcs)
        assertEquals(hlc20, fieldMap.getHlc(FeatureCodecV1.TAG_TEXT_VALUE))
    }

    @Test
    fun should_returnNull_when_candidateStateMatchesExistingState() {
        // Given
        val hlc10 = TestHlcFactory.create(ts = 1000L)
        val hlc20 = TestHlcFactory.create(ts = 2000L)
        val existing = FeatureEntity(id = 101L, hlc = hlc10, textValue = "SAME", countValue = 1)
        val candidate = existing.copy()

        // When
        val outbound = reconciler.prepareOutbound(
            candidateState = candidate,
            existingState = existing,
            hlc = hlc20,
            op = MutationOp.UPSERT,
            codecResolver = integratedResolver
        )

        // Then
        assertNull(outbound)
    }

    // ===================================================================
    // RESOLVE INBOUND TESTS
    // ===================================================================

    @Test
    fun should_decodeDeltaAndStampSyncHeader_when_resolvingInboundUpsert() {
        // Given
        val hlc10 = TestHlcFactory.create(ts = 1000L)
        val hlc20 = TestHlcFactory.create(ts = 2000L)
        val initial = FeatureEntity(id = 102L, hlc = hlc10, textValue = "OLD", countValue = 5)
        val remoteEntity = initial.copy(hlc = hlc20, textValue = "REMOTE")
        val (decodeContext, payload) = makeInbound(remoteEntity, initial, hlc20, MutationOp.UPSERT)

        // When
        val resolved =
            reconciler.resolveInbound(payload, decodeContext, initial, integratedResolver)

        // Then
        assertEquals("REMOTE", resolved.textValue)
        assertEquals(5, resolved.countValue)
        assertEquals(hlc20, resolved.hlc)
        assertEquals(hlc20.ts, resolved.lastModified)
        assertFalse(resolved.isDeleted)

        val fieldMap = FieldHlcMap(resolved.fieldHlcs)
        assertEquals(hlc20, fieldMap.getHlc(FeatureCodecV1.TAG_TEXT_VALUE))
    }

    @Test
    fun should_preserveHigherLocalHlcInSyncHeader_when_incomingHlcIsLower() {
        // Given
        val hlc50 = TestHlcFactory.create(ts = 5000L)
        val hlc20 = TestHlcFactory.create(ts = 2000L)
        val existing = FeatureEntity(id = 103L, hlc = hlc50, textValue = "LOCAL", countValue = 10)
        val incoming = existing.copy(hlc = hlc20, textValue = "OLD_INCOMING")
        val (decodeContext, payload) = makeInbound(incoming, existing, hlc20, MutationOp.UPSERT)

        // When
        val resolved =
            reconciler.resolveInbound(payload, decodeContext, existing, integratedResolver)

        // Then: Root entity HLC retains the higher local HLC
        assertEquals(hlc50, resolved.hlc)
        assertEquals(hlc20.ts, resolved.lastModified) // As FeatureEntity constructor
    }
}
