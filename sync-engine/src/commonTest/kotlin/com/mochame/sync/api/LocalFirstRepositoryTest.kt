@file:OptIn(ExperimentalKermitApi::class)

package com.mochame.sync.api

import co.touchlab.kermit.ExperimentalKermitApi
import com.mochame.node.fixtures.di.FixturesNodeConfig
import com.mochame.support.MochaPlatformTest
import com.mochame.support.runUnitEnvironment
import com.mochame.sync.api.boot.BootState
import com.mochame.sync.api.exceptions.MochaException
import com.mochame.sync.api.metadata.MutationOp
import com.mochame.sync.api.metadata.SyncStatus
import com.mochame.sync.common.bitmaskOf
import com.mochame.sync.common.toBitmask
import com.mochame.sync.di.api.LocalFirstRepoTestEnv
import com.mochame.sync.di.api.LocalFirstRepoTestModule
import com.mochame.sync.internal.fixtures.serialization.FakeFeatureCodec
import com.mochame.sync.internal.fixtures.serialization.FeatureCodecV1.Companion.TAG_COUNT_VALUE
import com.mochame.sync.internal.fixtures.serialization.FeatureCodecV1.Companion.TAG_TEXT_VALUE
import com.mochame.sync.internal.fixtures.serialization.FeatureEntity
import com.mochame.sync.internal.fixtures.serialization.deriveContext
import com.mochame.sync.spi.infrastructure.serialization.BaseFeatureCodec.Companion.TAG_CREATED_AT
import com.mochame.sync.spi.infrastructure.serialization.BaseFeatureCodec.Companion.TAG_PRIMARY_KEY
import com.mochame.sync.spi.models.DecodeContext
import com.mochame.utils.fixtures.TestHlcFactory
import com.mochame.utils.fixtures.TestNodeId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.io.IOException
import org.koin.plugin.module.dsl.modules
import kotlin.test.DefaultAsserter.assertNotNull
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private inline fun runEnv(crossinline block: suspend LocalFirstRepoTestEnv.(TestScope) -> Unit) =
    runUnitEnvironment<LocalFirstRepoTestEnv>(
        koinSetup = { modules(LocalFirstRepoTestModule::class) },
        block = block
    )

@ExperimentalCoroutinesApi
class LocalFirstRepositoryTest : MochaPlatformTest() {

    // -----------------------------------------------------------
    // Deletion / Restoration & Field Diffing
    // -----------------------------------------------------------

    @Test
    fun arrivalOrderInvariance_deleteAt50AndEditAt45_convergeIdentically() = runEnv {
        setupValidContext()
        val candidateKey1 = 2051L
        val candidateKey2 = 2052L

        val hlc10 = TestHlcFactory.createWithOffset((-60).minutes)
        val hlc45 = TestHlcFactory.createWithOffset((-45).minutes)
        val hlc50 = TestHlcFactory.createWithOffset((-40).minutes)

        val init1 =
            FeatureEntity(id = candidateKey1, hlc = hlc10, textValue = "BASE", countValue = 10)
        val init2 =
            FeatureEntity(id = candidateKey2, hlc = hlc10, textValue = "BASE", countValue = 10)
        val (ins1, insPay1) = makeRemoteIntent(init1, null, hlc10, MutationOp.UPSERT)
        val (ins2, insPay2) = makeRemoteIntent(init2, null, hlc10, MutationOp.UPSERT)
        repo.processRemoteIntent(ins1, insPay1)
        repo.processRemoteIntent(ins2, insPay2)

        val edit1 = init1.copy(hlc = hlc45, textValue = "CONCURRENT")
        val edit2 = init2.copy(hlc = hlc45, textValue = "CONCURRENT")
        val (eCtx1, ePay1) = makeRemoteIntent(edit1, init1, hlc45, MutationOp.UPSERT)
        val (eCtx2, ePay2) = makeRemoteIntent(edit2, init2, hlc45, MutationOp.UPSERT)

        val del1 = init1.withDeleteState(true).copy(hlc = hlc50)
        val del2 = init2.withDeleteState(true).copy(hlc = hlc50)
        val (dCtx1, dPay1) = makeRemoteIntent(del1, init1, hlc50, MutationOp.DELETE)
        val (dCtx2, dPay2) = makeRemoteIntent(del2, init2, hlc50, MutationOp.DELETE)

        // Replica 1 Order: Delete@50 THEN Edit@45
        repo.processRemoteIntent(dCtx1, dPay1)
        repo.processRemoteIntent(eCtx1, ePay1)

        // Replica 2 Order: Edit@45 THEN Delete@50
        repo.processRemoteIntent(eCtx2, ePay2)
        repo.processRemoteIntent(dCtx2, dPay2)

        val replica1 = repo.storedEntities[candidateKey1]
        val replica2 = repo.storedEntities[candidateKey2]

        assertNotNull(replica1)
        assertNotNull(replica2)
        assertEquals(
            replica1.isDeleted,
            replica2.isDeleted,
            "Both replicas must agree on deletion status"
        )
        assertTrue(replica1.isDeleted, "Delete@50 dominates Edit@45 regardless of arrival sequence")
        assertEquals(replica1.textValue, replica2.textValue)
        assertNull(replica1.textValue, "Field must be null on both replicas")
        assertEquals(replica1.hlc, replica2.hlc, "Both entities converge to identical HLC")
    }

    @Test
    fun deleteArrivingBeforeInsert_rejectsSafelyWithoutCrashing() = runEnv {
        setupValidContext()
        val candidateKey = 206L
        val deleteHlc = TestHlcFactory.createWithOffset((-20).minutes)

        assertNull(repo.storedEntities[candidateKey])

        val dummy = FeatureEntity(id = candidateKey, hlc = deleteHlc)
        val deletion = dummy.withDeleteState(true).copy(hlc = deleteHlc)
        val (delCtx, delPayload) = makeRemoteIntent(deletion, dummy, deleteHlc, MutationOp.DELETE)

        repo.processRemoteIntent(delCtx, delPayload)

        assertNull(
            repo.storedEntities[candidateKey],
            "Remote delete against non-existent record must not create row"
        )
        assertEquals(0, intentStore.intents.size, "No redundant intents generated")
        assertEquals(0, workerHook.invalidationCount, "No worker invalidation triggered")
    }

    @Test
    fun offlineDeleteInterleavedWithConcurrentEditAndPostDeleteUpdate_convergesDeterministically() = runEnv {
        setupValidContext()
        val candidateKey = 211L

        val hlc10 = TestHlcFactory.createWithOffset((-50).minutes)
        val hlc20 = TestHlcFactory.createWithOffset((-40).minutes)
        val hlc25 = TestHlcFactory.createWithOffset((-35).minutes)
        val hlc30 = TestHlcFactory.createWithOffset((-30).minutes)

        // 1. Initial synced state: both fields populated @ HLC 10
        val initial = FeatureEntity(
            id = candidateKey,
            hlc = hlc10,
            isDeleted = false,
            textValue = "SLEEP_5",
            countValue = 2
        )
        val (initCtx, initPayload) = makeRemoteIntent(initial, null, hlc10, MutationOp.UPSERT)
        repo.processRemoteIntent(initCtx, initPayload)

        // 2. Peer B (JVM) performs concurrent edit on countValue @ HLC 25
        val jvmEdit = initial.copy(hlc = hlc25, countValue = 99)
        val (editCtx, editPayload) = makeRemoteIntent(jvmEdit, initial, hlc25, MutationOp.UPSERT)
        repo.processRemoteIntent(editCtx, editPayload)

        // 3. Peer A (Android) offline delete @ HLC 20 arrives (older than JVM edit @ 25, newer than initial @ 10)
        val offlineDelete = initial.withDeleteState(true).copy(hlc = hlc20)
        val (delCtx, delPayload) = makeRemoteIntent(offlineDelete, initial, hlc20, MutationOp.DELETE)
        repo.processRemoteIntent(delCtx, delPayload)

        // Verify intermediate state after delete ingestion:
        // - textValue (HLC 10 <= 20) was swept to null by the delete horizon
        // - countValue (HLC 25 > 20) survived the delete horizon
        // - isDeleted must NOT be true because a surviving field keeps the entity alive
        val storedAfterDelete = repo.storedEntities[candidateKey]
        assertNotNull(storedAfterDelete, "Entity must exist in storage")
        assertFalse(
            storedAfterDelete.isDeleted,
            "Surviving field write at HLC 25 must prevent entity from being marked deleted"
        )
        assertNull(
            storedAfterDelete.textValue,
            "Pre-delete field value at HLC 10 must be swept to null by delete horizon at HLC 20"
        )
        assertEquals(
            99,
            storedAfterDelete.countValue,
            "Concurrent field write at HLC 25 must survive the delete horizon at HLC 20"
        )

        // 4. Peer A (Android) post-delete update arrives @ HLC 30 updating textValue
        val androidUpdate = initial.copy(
            hlc = hlc30,
            textValue = "READINESS_3",
            countValue = null
        )
        val (updateCtx, updatePayload) = makeRemoteIntent(androidUpdate, offlineDelete, hlc30, MutationOp.UPSERT)
        repo.processRemoteIntent(updateCtx, updatePayload)

        // 5. Final state verification across both fields
        val finalStored = repo.storedEntities[candidateKey]
        assertNotNull(finalStored, "Final entity must exist in storage")
        assertFalse(finalStored.isDeleted, "Entity must remain active")
        assertEquals(
            "READINESS_3",
            finalStored.textValue,
            "Post-delete edit at HLC 30 must be applied"
        )
        assertEquals(
            99,
            finalStored.countValue,
            "Concurrent edit from JVM at HLC 25 must not be lost or flipped"
        )
        assertEquals(
            hlc30,
            finalStored.hlc,
            "Root entity HLC must advance to the latest edit"
        )
    }

    @Test
    fun fieldLessRestore_revivesEntityViaStructuralWitnessTags() = runEnv {
        setupValidContext()
        val candidateKey = 208L

        val hlc10 = TestHlcFactory.createWithOffset((-50).minutes)
        val hlc20 = TestHlcFactory.createWithOffset((-40).minutes)
        val hlc30 = TestHlcFactory.createWithOffset((-30).minutes)

        // 1. Initial insert @ 10, delete @ 20
        val initial =
            FeatureEntity(id = candidateKey, hlc = hlc10, textValue = "INIT", countValue = 10)
        val (insCtx, insPayload) = makeRemoteIntent(initial, null, hlc10, MutationOp.UPSERT)
        repo.processRemoteIntent(insCtx, insPayload)

        val deletion = initial.withDeleteState(true).copy(hlc = hlc20)
        val (delCtx, delPayload) = makeRemoteIntent(deletion, initial, hlc20, MutationOp.DELETE)
        repo.processRemoteIntent(delCtx, delPayload)

        // 2. Restore arrives at HLC 30 with ALL domain fields null
        val fieldLessRestore = deletion.withDeleteState(false).copy(
            hlc = hlc30,
            textValue = null,
            countValue = null
        )
        val (resCtx, resPayload) = makeRemoteIntent(
            fieldLessRestore,
            deletion,
            hlc30,
            MutationOp.UPSERT
        )
        repo.processRemoteIntent(resCtx, resPayload)

        val stored = repo.storedEntities[candidateKey]
        assertNotNull(stored)
        assertFalse(
            stored.isDeleted,
            "Structural tags keep entity alive even with null domain fields"
        )
        assertNull(stored.textValue)
        assertNull(stored.countValue)
        assertEquals(hlc30, stored.hlc)
    }

    @Test
    fun legacyActiveRow_processesSubsequentRemoteUpsertWithoutFalseDeletion() = runEnv {
        setupValidContext()
        val candidateKey = 209L

        val hlc10 = TestHlcFactory.createWithOffset((-50).minutes)
        val hlc30 = TestHlcFactory.createWithOffset((-30).minutes)

        val legacyEntity = FeatureEntity(
            id = candidateKey,
            hlc = hlc10,
            isDeleted = false,
            textValue = "LEGACY_TEXT",
            countValue = 10
        )
        repo.seed(legacyEntity)

        // Subsequent update arrives modifying a single field
        val update = legacyEntity.copy(hlc = hlc30, countValue = 20)
        val (editCtx, editPayload) = makeRemoteIntent(
            update,
            legacyEntity,
            hlc30,
            MutationOp.UPSERT
        )
        repo.processRemoteIntent(editCtx, editPayload)

        val stored = repo.storedEntities[candidateKey]
        assertNotNull(stored)
        assertFalse(
            stored.isDeleted,
            "Active entity must remain active after subsequent partial sync"
        )
        assertEquals("LEGACY_TEXT", stored.textValue)
        assertEquals(20, stored.countValue)
        assertEquals(hlc30, stored.hlc)
    }

    @Test
    fun localOperations_upsertAndDelete_updateStoreAndDispatchIntents() = runEnv {
        setupValidContext()
        val candidateKey = 210L

        // 1. Local Upsert
        repo.upsert(candidateKey) {
            FeatureEntity(
                id = candidateKey,
                isDeleted = false,
                textValue = "LOCAL_INSERT",
                countValue = 1
            )
        }

        val afterUpsert = repo.storedEntities[candidateKey]
        assertNotNull(afterUpsert)
        assertFalse(afterUpsert.isDeleted)
        assertEquals("LOCAL_INSERT", afterUpsert.textValue)
        assertEquals(1, intentStore.intents.size, "Local upsert records sync intent")
        assertEquals(1, workerHook.invalidationCount, "Local upsert triggers worker invalidation")

        // 2. Local Delete
        repo.delete(candidateKey)

        val afterDelete = repo.storedEntities[candidateKey]
        assertNotNull(afterDelete)
        assertTrue(afterDelete.isDeleted)
        assertEquals(2, intentStore.intents.size, "Local delete records second sync intent")

        // 3. Redundant Local Delete (doesn't throw)
        repo.delete(candidateKey)
        assertEquals(
            2,
            intentStore.intents.size,
            "Duplicate local delete rejected without creating third intent"
        )
    }

    // -----------------------------------------------------------
    // BOOT STATE GUARD
    // -----------------------------------------------------------

    @Test
    fun awaitReady_onCriticalFailureWithException_unwrapsAndThrowsException() =
        runEnv {
            val rootCause = IllegalStateException("Corrupt local database")
            bootProvider.updateState(
                BootState.LockOut(
                    message = "DB_CORRUPT", cause = rootCause
                )
            )

            val thrown = assertFailsWith<IllegalStateException> {
                repo.delete(5L)
            }

            assertEquals("Corrupt local database", thrown.message)
        }

    @Test
    fun awaitReady_whenInitializingOrIdle_suspendsUntilTransitionToActive() = runEnv {
        hlcFactory.hydrate(null, TestNodeId.A)
        bootProvider.updateState(BootState.Init)
        repo.seed(FeatureEntity(id = 1L))

        var completed = false
        val deferred = it.async {
            repo.delete(1L) // suspending
            completed = true
        }

        it.runCurrent()
        assertEquals(false, completed)
        assertEquals(false, deferred.isCompleted)

        bootProvider.updateState(BootState.Idle)
        it.runCurrent()
        assertEquals(false, completed)

        bootProvider.updateState(BootState.Ready)
        it.runCurrent()

        assertTrue(completed)
        assertTrue(deferred.isCompleted)
    }

    @Test
    fun awaitReady_whenRemainingInitializing_timesOutAndThrows() = runEnv {
        bootProvider.updateState(BootState.Init)

        val error = assertFailsWith<MochaException.Persistent.BootInitializationError> {
            repo.delete(1L)
        }

        assertContains(error.message, "timed out")
        assertEquals(
            FixturesNodeConfig.BOOT_TIMEOUT.inWholeMilliseconds,
            it.testScheduler.currentTime
        )
    }

    @Test
    fun awaitReady_whenReady_returnsImmediately() = runEnv {
        setupValidContext()

        repo.upsert(1L) { FeatureEntity() }

        assertEquals(0L, it.testScheduler.currentTime)
    }

    // -----------------------------------------------------------
    // LOCAL PIPELINE
    // -----------------------------------------------------------

    // FAKING SERIALIZATION / FIELD DIFFING ----------------------

    @Test
    fun localUpsert_onNewEntity_stampsHlc_recordsPendingIntent_andNotifiesWorker() = runEnv {
        setupValidContext()
        val candidateKey = 101L

        val result =
            repo.upsert(candidateKey) { FeatureEntity(id = candidateKey, textValue = "NEW_ITEM") }

        assertEquals(101L, result)

        // Verify entity state in repository
        val stored = repo.storedEntities[candidateKey]
        assertNotNull(stored, "Stored entity")
        assertEquals("NEW_ITEM", stored.textValue, "Text value")
        assertEquals(1, hlcFactory.getNextHlcCallCount, "HLC call count")
        assertEquals(stored.hlc, nodeManager.getMaxHlc(), "Max HLC")

        // Verify SyncIntent record
        val recordedIntents = intentStore.intents
        assertEquals(1, recordedIntents.size, "Intent count")
        val intent = recordedIntents.first()
        assertEquals(candidateKey, intent.candidateKey, "Candidate key")
        assertEquals(MutationOp.UPSERT, intent.operation, "Operation")
        assertEquals(SyncStatus.PENDING, intent.syncStatus, "Sync status")
        assertEquals(stored.hlc, intent.hlc, "Intent HLC")
        assertNotNull(intent.payload, "Payload")
        assertEquals(null, intent.overflowBlobId, "Overflow blob ID")

        // Verify dispatched worker post commit
        assertEquals(1, workerHook.invalidationCount, "Invalidation Hook")
    }

    @Test
    fun localDelete_onExistingActiveEntity_recordsDeletionIntent_andPersists() = runEnv {
        // Given
        setupValidContext()
        val candidateKey = 104L
        val initialHlc = hlcFactory.getNextHlc()
        val activeEntity = FeatureEntity(
            id = candidateKey,
            hlc = initialHlc,
            isDeleted = false,
            textValue = "TO_BE_DELETED"
        )
        repo.seed(activeEntity)

        // When
        val result = repo.delete(candidateKey)

        // Then
        assertEquals(104L, result)
        val stored = repo.storedEntities[candidateKey]
        assertNotNull(stored)
        assertTrue(stored.isDeleted)

        val recordedIntents = intentStore.intents
        assertEquals(1, recordedIntents.size)
        val intent = recordedIntents.first()
        assertEquals(MutationOp.DELETE, intent.operation)
        assertEquals(SyncStatus.PENDING, intent.syncStatus)
        assertEquals(1, workerHook.invalidationCount)
    }

    @Test
    fun localDelete_onNonExistentRecord_skipsWithoutErrorOrIntentGeneration() = runEnv {
        setupValidContext()
        val nonExistentKey = 999L

        repo.delete(nonExistentKey)

        assertEquals(0, intentStore.intents.size)
        assertEquals(0, workerHook.invalidationCount)
        assertEquals(0, hlcFactory.getNextHlcCallCount)
    }

    @Test
    fun localDelete_onAlreadyDeletedEntity_skipsWithoutErrorOrIntentGeneration() = runEnv {
        setupValidContext()
        val candidateKey = 105L
        val initialHlc = hlcFactory.getNextHlc()
        val deletedEntity = FeatureEntity(
            id = candidateKey,
            hlc = initialHlc,
            isDeleted = true,
            textValue = "ALREADY_DELETED"
        )
        repo.seed(deletedEntity)
        val initialHlcCalls = hlcFactory.getNextHlcCallCount

        val result = repo.delete(candidateKey)

        assertEquals(0L, result)
        assertEquals(0, intentStore.intents.size)
        assertEquals(0, workerHook.invalidationCount)
        assertEquals(initialHlcCalls, hlcFactory.getNextHlcCallCount)
    }

    @Test
    fun processRemoteIntent_whenExistingIsNullAndOpIsDelete_skipsGracefullyWithoutRecordingIntent() =
        runEnv {
            setupValidContext()
            val candidateKey = 112L

            // Device B: Remote delete intent for record only present in local state
            val remoteHlc = TestHlcFactory.createWithOffset((-1).minutes)
            val decodeContext = DecodeContext(
                candidateKey = candidateKey,
                hlc = remoteHlc,
                op = MutationOp.DELETE,
                featureSchemaVersion = 1,
                changedMask = 0L
            )

            // Device A: ingesting remote intent
            repo.processRemoteIntent(decodeContext, FakeFeatureCodec.BYTES_PRESET)

            // Final State Assertions
            val stored = repo.storedEntities[candidateKey]
            assertEquals(null, stored, "No entity should be created or stored")
            assertTrue(writer.logs.any { "Non-existent local record" in it.message })
            // Side-effect Assertions
            assertEquals(0, intentStore.intents.size, "No intents recorded")
            assertEquals(0, workerHook.invalidationCount, "No worker invalidation triggered")
        }

    // INTEGRATED SERIALIZATION / FIELD DIFFING ------------------

    @Test
    fun localUpsert_onExistingEntity_computesFieldDiff_andStampsUpdatedFieldHlcs() = runEnv {
        setupValidContext()
        val integratedRepo = createCodecIntegratedRepo()

        val candidateKey = 102L
        val initialHlc = hlcFactory.getNextHlc()
        val initialEntity = FeatureEntity(
            id = candidateKey,
            hlc = initialHlc,
            textValue = "ORIGINAL",
            countValue = 10
        )
        integratedRepo.seed(initialEntity)

        val result = integratedRepo.upsert(candidateKey) { existing ->
            existing!!.copy(textValue = "UPDATED") // diff encodes proto tag 4
        }

        assertEquals(102L, result)

        val updated = integratedRepo.storedEntities[candidateKey]
        assertNotNull(updated)
        assertEquals("UPDATED", updated.textValue)
        assertEquals(10, updated.countValue)
        assertTrue(updated.hlc > initialHlc)
        assertNotEquals(updated.fieldHlcs, initialEntity.fieldHlcs)
        assertTrue(writer.logs.any { "OP:UPSERT [4]" in it.message })

        val recordedIntents = intentStore.intents
        assertEquals(1, recordedIntents.size)
        assertEquals(MutationOp.UPSERT, recordedIntents.first().operation)
        assertEquals(1, workerHook.invalidationCount)
    }

    @Test
    fun localUpsert_whenUnchanged_returnsViaOnSkipAfterTriggeringFieldDiffing_withoutRecordingIntentOrUpdatingHlcFields() =
        runEnv {
            setupValidContext()
            val integratedRepo = createCodecIntegratedRepo()

            val candidateKey = 103L
            val initialHlc = hlcFactory.getNextHlc()
            val initialEntity = FeatureEntity(
                id = candidateKey,
                hlc = initialHlc,
                textValue = "UNCHANGED",
                countValue = 5
            )
            integratedRepo.seed(initialEntity)

            // localUpsert computes exact same state -> changeMask is 0 so skips
            val result = integratedRepo.upsert(candidateKey) { initialEntity }

            assertEquals(0L, result)
            assertTrue(writer.logs.any { "Skipping" in it.message })
            assertEquals(0, intentStore.intents.size)
            assertEquals(0, workerHook.invalidationCount)
            assertEquals(2, hlcFactory.getNextHlcCallCount)
        }

    @Test
    fun localUpsert_onDeletedEntity_restoresDeletion_andEmitsRestoreIntent() = runEnv {
        val integratedRepo = createCodecIntegratedRepo()
        val candidateKey = 106L
        val initialHlc = TestHlcFactory.createWithOffset(offset = (-1).minutes)
        setupValidContext()

        // Initial State & Deletion
        val initialEntity = FeatureEntity(
            id = candidateKey,
            hlc = initialHlc,
            isDeleted = false,
            textValue = "INITIAL_STATE",
            countValue = 0
        )
        integratedRepo.seed(initialEntity)
        integratedRepo.delete(candidateKey)

        val deletedEntity = integratedRepo.storedEntities[candidateKey]
        assertNotNull(deletedEntity, "Deleted state must exist prior to restore")
        assertTrue(deletedEntity.isDeleted, "Entity is deleted")

        // Restoration via localUpsert
        val result = integratedRepo.upsert(candidateKey) { existing ->
            existing!!.copy(
                isDeleted = false,
                textValue = "RESTORED_STATE",
                countValue = 1
            )
        }
        assertEquals(candidateKey, result)

        // Verify restored entity
        val stored = integratedRepo.storedEntities[candidateKey]
        assertNotNull(stored, "Stored entity exists after restore")
        assertEquals(false, stored.isDeleted, "Deletion status is restored")
        assertEquals("RESTORED_STATE", stored.textValue)
        assertEquals(1, stored.countValue)
        assertTrue(stored.hlc > deletedEntity.hlc, "HLC to be updated")
        assertNotEquals(deletedEntity.fieldHlcs, stored.fieldHlcs, "Field HLCs must be updated")

        // Verify SyncIntent generation
        assertEquals(2, intentStore.intents.size, "Contains delete and subsequent restore intents")
        val restoreIntent = intentStore.intents.last()
        assertEquals(MutationOp.UPSERT, restoreIntent.operation)
        assertEquals(SyncStatus.PENDING, restoreIntent.syncStatus)
        assertEquals(stored.hlc, restoreIntent.hlc)
        assertNotNull(restoreIntent.payload, "Payload contains encoded restore delta")

        // Verify side-effects
        assertEquals(2, workerHook.invalidationCount, "Invalidation triggered twice")
    }

    @Test
    fun processRemoteIntent_whenLocalDeleteIsMoreRecentThanIncomingUpsert_preservesDeletionWhileMergingFieldValues() =
        runEnv {
            val integratedRepo = createCodecIntegratedRepo()
            val candidateKey = 107L
            val deletionHlc = TestHlcFactory.createWithOffset((-1).minutes)
            setupValidContext()

            // Device A: local delete
            val initialEntity = FeatureEntity(
                id = candidateKey,
                hlc = deletionHlc,
                isDeleted = false,
                textValue = "INITIAL_DELETED_TEXT",
                countValue = 10
            )
            integratedRepo.seed(initialEntity)
            integratedRepo.delete(candidateKey)

            // Device B: remote upsert
            val remoteState = FeatureEntity(
                id = candidateKey,
                hlc = TestHlcFactory.createWithOffset((-2).minutes),
                textValue = "REMOTE_UPDATED_TEXT",
                countValue = 10
            )
            val payload = integratedCodec.encode(remoteState, null)
            val changedTags = integratedCodec.computeChangedTags(remoteState, null)
            val decodeContext = remoteState.deriveContext(changedMask = changedTags.toBitmask())
            assertNotNull(payload, "Payload before processRemoteIntent")

            // Device A: Ingest remote intent
            integratedRepo.processRemoteIntent(decodeContext, payload)
            val finalEntity = integratedRepo.storedEntities[candidateKey]
            assertNotNull(finalEntity, "Stored after processRemoteIntent")

            // Verify final state
            assertTrue(finalEntity.isDeleted, "Deletion status preserved")
            assertEquals("REMOTE_UPDATED_TEXT", finalEntity.textValue)
            assertEquals(initialEntity.countValue, finalEntity.countValue)
            assertNotEquals(initialEntity.textValue, finalEntity.textValue)
            assertNotEquals(initialEntity.fieldHlcs, finalEntity.fieldHlcs)

            // Verify side-effects
            assertEquals(1, intentStore.intents.size, "Only local deletion.")
            assertEquals(1, workerHook.invalidationCount, "Only local deletion.")
        }

    @Test
    fun processRemoteIntent_whenIncomingUpsertIsMoreRecentThanDelete_shouldRestoreEntityAndApplyFieldDiff() =
        runEnv {
            val integratedRepo = createCodecIntegratedRepo()
            val candidateKey = 108L
            val initialHlc = TestHlcFactory.create()
            setupValidContext(initialHlc)

            // Device A: Seed active entity and perform local delete
            val initialEntity = FeatureEntity(
                id = candidateKey,
                hlc = initialHlc,
                isDeleted = false,
                textValue = "INITIAL_FILLED_TEXT",
                countValue = 10
            )
            integratedRepo.seed(initialEntity)
            integratedRepo.delete(candidateKey)

            val deletedEntity = integratedRepo.storedEntities[candidateKey]
            assertNotNull(deletedEntity, "Deleted entity must exist locally")
            assertTrue(deletedEntity.isDeleted, "Entity is locally deleted")

            // Device B: More recent remote upsert
            val remoteState = FeatureEntity(
                id = candidateKey,
                hlc = TestHlcFactory.createWithOffset(10.seconds),
                isDeleted = false,
                textValue = null,
                countValue = 10
            )
            val payload = integratedCodec.encode(remoteState, deletedEntity)
            val changedTags = integratedCodec.computeChangedTags(remoteState, deletedEntity)
            val decodeContext = remoteState.deriveContext(changedMask = changedTags.toBitmask())
            assertNotNull(payload, "Payload before processRemoteIntent")

            // Device A: Ingest remote intent
            integratedRepo.processRemoteIntent(decodeContext, payload)
            val finalEntity = integratedRepo.storedEntities[candidateKey]
            assertNotNull(finalEntity, "Stored after processRemoteIntent")

            // Final State
            assertFalse(finalEntity.isDeleted, "Entity restored after remote upsert")
            assertEquals(null, finalEntity.textValue, "Text value to be blank")
            assertEquals(initialEntity.countValue, finalEntity.countValue)
            assertNotEquals(deletedEntity.fieldHlcs, finalEntity.fieldHlcs)

            // Side-effects
            assertEquals(1, intentStore.intents.size, "Only local deletion intent")
            assertEquals(1, workerHook.invalidationCount, "Only local deletion invalidation")
            assertEquals(1, hlcFactory.getNextHlcCallCount, "Only local delete hlc")
        }

    @Test
    fun processRemoteIntent_whenIncomingUpsertCaughtBetweenTwoLocalUpserts_mergesFieldsCorrectly() =
        runEnv {
            fakeClock.reverseTime(3.minutes)
            val integratedRepo = createCodecIntegratedRepo()
            val candidateKey = 109L
            val initialHlc = TestHlcFactory.createWithOffset((-3).minutes)
            setupValidContext(initialHlc)

            // Initial State at T0 (-3.minutes)
            val initialEntity = FeatureEntity(
                id = candidateKey,
                hlc = initialHlc,
                isDeleted = false,
                textValue = "INITIAL_TEXT",
                countValue = 10
            )
            integratedRepo.seed(initialEntity)

            // Device B: Encodes a single-field mutation (tag 4) at T1 (-2.minutes)
            val remoteState = initialEntity.copy(
                hlc = TestHlcFactory.createWithOffset((-2).minutes),
                textValue = "REMOTE_UPDATED_TEXT",
                countValue = 20
            )
            val payload = integratedCodec.encode(remoteState, initialEntity)
            val decodeContext = remoteState.deriveContext(changedMask = bitmaskOf(4, 5))
            assertNotNull(payload, "Payload before processRemoteIntent")

            // Device A: Performs local mutation to a separate field (tag 5) at T2 (> T1)
            fakeClock.advanceTime(3.minutes)
            val localResult = integratedRepo.upsert(candidateKey) { existing ->
                existing!!.copy(countValue = 99)
            }
            assertEquals(candidateKey, localResult)
            val localEntityBeforeRemote = integratedRepo.storedEntities[candidateKey]
            assertNotNull(localEntityBeforeRemote)
            assertEquals("INITIAL_TEXT", localEntityBeforeRemote.textValue)
            assertEquals(99, localEntityBeforeRemote.countValue)

            // Device A: Receives and ingests Device B's remote intent from T1
            integratedRepo.processRemoteIntent(decodeContext, payload)
            val finalEntity = integratedRepo.storedEntities[candidateKey]
            assertNotNull(finalEntity, "Stored after processRemoteIntent")

            // Final state assertions:
            // - Remote textValue (T1 > T0) is accepted and merged
            // - Local countValue (T2 > T0) is preserved
            assertEquals("REMOTE_UPDATED_TEXT", finalEntity.textValue)
            assertEquals(99, finalEntity.countValue, "Count value preserved")
            assertFalse(finalEntity.isDeleted)
            assertNotEquals(initialEntity.fieldHlcs, finalEntity.fieldHlcs)
            assertTrue(writer.logs.any { "Field Rejected [tag=5]" in it.message })

            // Side-effect Assertions: Inbound remote processing dispatches no extra intents or signals
            assertEquals(1, intentStore.intents.size, "Only local upsert intent recorded")
            assertEquals(1, workerHook.invalidationCount, "Only local upsert invalidation")
        }

    // -----------------------------------------------------------
    // STAGING
    // -----------------------------------------------------------

    @Test
    fun handleLocalCommit_withInlinePayload_embedsPayloadDirectlyWithoutBlobStaging() = runEnv {
        setupValidContext()
        val integratedRepo = createCodecIntegratedRepo()
        val candidateKey = 201L

        // Standard payload <= 64KB
        val result = integratedRepo.upsert(candidateKey) { FeatureEntity(id = candidateKey) }
        assertEquals(candidateKey, result, "Key")

        // Intent verification
        val recordedIntents = intentStore.intents
        val intent = recordedIntents.first()

        assertEquals(1, recordedIntents.size)
        assertNotNull(intent.payload, "Payload <= 64KB must be embedded inline")
        assertNull(intent.overflowBlobId, "Inline payload must not set overflowBlobId")
        assertEquals(MutationOp.UPSERT, intent.operation)
        assertEquals(SyncStatus.PENDING, intent.syncStatus)

        // Side-effects
        assertEquals(1, workerHook.invalidationCount)
        assertEquals(1, hlcFactory.getNextHlcCallCount)
    }

    @Test
    fun handleLocalCommit_withOverflowPayload_stagesBlob_andCommitsPostTransaction() = runEnv {
        setupValidContext()
        val integratedRepo = createCodecIntegratedRepo()
        val candidateKey = 202L

        // Large payload > 65_536L bytes
        val largeText = "A".repeat(70_000)
        val result = integratedRepo.upsert(candidateKey) {
            FeatureEntity(id = candidateKey, textValue = largeText)
        }
        assertEquals(candidateKey, result)

        // Intent verification
        val recordedIntents = intentStore.intents
        val intent = recordedIntents.first()
        val blobId = intent.overflowBlobId

        assertEquals(1, recordedIntents.size)
        assertEquals(candidateKey, intent.candidateKey)
        assertNull(intent.payload, "Overflow payload must not be stored inline in SyncIntent")
        assertNotNull(blobId, "Overflow payload must assign an overflowBlobId")

        // Side-effect verification
        assertTrue(deps.blobStore.existsInCommitted(blobId!!))
        assertEquals(1, workerHook.invalidationCount)
    }

    @Test
    fun handleLocalCommit_whenStagingThrows_propagatesExceptionAndAbortsBeforeTransaction() =
        runEnv {
            setupValidContext()
            val integratedRepo = createCodecIntegratedRepo()
            val candidateKey = 203L
            val largeText = "A".repeat(70_000)

            blobStore.stageError = IOException("Staging failed: Disk full")

            assertFailsWith<MochaException.Persistent.IOFailure> {
                integratedRepo.upsert(candidateKey) {
                    FeatureEntity(id = candidateKey, textValue = largeText)
                }
            }

            assertEquals(0, intentStore.intents.size, "No intent recorded on staging failure")
            assertEquals(0, workerHook.invalidationCount, "Worker hook must not be triggered")
            assertNull(repo.storedEntities[candidateKey], "No entity should be persisted")
        }

    @Test
    fun handleLocalCommit_whenPreCommitFails_abortsStagedBlob_andDoesNotNotifyWorker() = runEnv {
        setupValidContext()
        val candidateKey = 204L
        val largeText = "A".repeat(70_000)
        val integratedRepo = createCodecIntegratedRepo()

        transactor.shouldThrow = IllegalStateException("Database write locked")

        assertFailsWith<MochaException> {
            integratedRepo.upsert(candidateKey) {
                FeatureEntity(id = candidateKey, textValue = largeText)
            }
        }

        assertEquals(0, intentStore.intents.size, "No intent recorded after rollback")
        assertEquals(0, workerHook.invalidationCount, "Worker must not be notified on rollback")
        assertEquals(0, deps.blobStore.listPendingHashes().size, "blob must be aborted")
    }

    @Test
    fun handleLocalCommit_whenPostCommitBlobCommitFails_throwsBlobResolutionPending() = runEnv {
        setupValidContext()
        val candidateKey = 205L
        val largeText = "A".repeat(70_000)
        val integratedRepo = createCodecIntegratedRepo()

        blobStore.commitError = IOException("Access issue on path")

        val thrown = assertFailsWith<MochaException.Transient.BlobResolutionPending> {
            integratedRepo.upsert(candidateKey) {
                FeatureEntity(id = candidateKey, textValue = largeText)
            }
        }

        assertNotNull(thrown.blobId)
        assertEquals(1, intentStore.intents.size, "Intent is retained in DB")
        assertEquals(1, workerHook.invalidationCount, "Worker is notified of DB commit")
        assertNotNull(integratedRepo.storedEntities[candidateKey], "Local entity remains persisted")
    }

    // -----------------------------------------------------------
    // CONTENTION / CONCURRENCY
    // -----------------------------------------------------------

    @Test
    fun keyedLocker_serializesConcurrentMutationsOnSameCandidateKey() = runEnv { scope ->
        setupValidContext()
        val candidateKey = 301L
        val holdFirstMutation = CompletableDeferred<Unit>()
        val firstMutationEntered = CompletableDeferred<Unit>()
        var secondMutationEntered = false

        // First coroutine
        val job1 = scope.launch {
            repo.upsert(candidateKey) {
                firstMutationEntered.complete(Unit)
                holdFirstMutation.await()
                FeatureEntity(id = candidateKey, textValue = "FIRST_MUTATION")
            }
        }

        scope.runCurrent()
        assertTrue(firstMutationEntered.isCompleted, "First mutation entered")
        assertEquals(1, locker.activeUsersFor(repo.featureContext, candidateKey))

        // Second coroutine
        val job2 = scope.launch {
            repo.upsert(candidateKey) {
                secondMutationEntered = true
                FeatureEntity(id = candidateKey, textValue = "SECOND_MUTATION")
            }
        }

        scope.runCurrent()
        assertEquals(false, secondMutationEntered)
        assertEquals(2, locker.activeUsersFor(repo.featureContext, candidateKey))

        // Release first mutation
        holdFirstMutation.complete(Unit)
        scope.runCurrent()
        job1.join()
        job2.join()

        // Second mutation runs after first; final entity state reflects second mutation
        assertTrue(secondMutationEntered, "Second mutation entered")
        assertEquals("SECOND_MUTATION", repo.storedEntities[candidateKey]?.textValue)
        assertNull(locker.activeUsersFor(repo.featureContext, candidateKey))
        assertEquals(0, locker.activeKeysCount)
    }

    @Test
    fun keyedLocker_allowsParallelExecutionAcrossDistinctCandidateKeys() = runEnv { scope ->
        setupValidContext()
        val keyA = 302L
        val keyB = 303L
        val holdKeyA = CompletableDeferred<Unit>()
        var keyBCompleted = false

        // Coroutine on Key A suspends mid-lock
        val jobA = scope.launch {
            repo.upsert(keyA) {
                holdKeyA.await()
                FeatureEntity(id = keyA, textValue = "KEY_A")
            }
        }
        scope.runCurrent()
        assertEquals(1, locker.activeUsersFor(repo.featureContext, keyA))

        // Coroutine on Key B proceeds without contention
        val jobB = scope.launch {
            repo.upsert(keyB) {
                FeatureEntity(id = keyB, textValue = "KEY_B")
            }
            keyBCompleted = true
        }
        scope.runCurrent()

        // Key B completes independently while Key A is still held
        assertTrue(keyBCompleted)
        assertEquals("KEY_B", repo.storedEntities[keyB]?.textValue)
        assertEquals(1, locker.activeKeysCount, "Only Key A should remain in registry")

        // Complete Key A
        holdKeyA.complete(Unit)
        scope.runCurrent()
        jobA.join()
        jobB.join()

        assertEquals(2, repo.storedEntities.size)
        assertEquals(0, locker.activeKeysCount)
    }

    @Test
    fun keyedLocker_cleansUpRegistryAndReleasesLockOnThrownExceptionAndRepoPropagates() = runEnv {
        setupValidContext()
        val candidateKey = 304L

        // Force an exception inside the locked execution block
        assertFailsWith<MochaException.Transient.StateIssue> {
            repo.upsert(candidateKey) {
                throw IllegalStateException("Domain validation failure")
            }
        }
        assertNull(locker.activeUsersFor(repo.featureContext, candidateKey))
        assertEquals(0, locker.activeKeysCount)

        // Subsequent success with no deadlock
        val result = repo.upsert(candidateKey) {
            FeatureEntity(id = candidateKey, textValue = "RECOVERED")
        }

        assertEquals(candidateKey, result)
        assertEquals("RECOVERED", repo.storedEntities[candidateKey]?.textValue)
        assertEquals(0, locker.activeKeysCount)
    }

    @Test
    fun staggeredDbRetryPolicy_withConcurrentUpsertDuringRetry_serializesAndMergesBothMutations() =
        runEnv { scope ->
            setupValidContext()
            val candidateKey = 306L
            transactor.shouldThrow =
                MochaException.Transient.DatabaseBusy("Simulated SQLite database locked")

            // First mutation: sets textValue and triggers database retry
            val deferred1 = scope.async {
                repo.upsert(candidateKey) { existing ->
                    (existing ?: FeatureEntity(id = candidateKey)).copy(
                        textValue = "RETRY_SUCCESS",
                        countValue = 0
                    )
                }
            }
            scope.runCurrent() // hits delay backoff and holds the lock
            assertEquals(1, transactor.executionCount)
            assertEquals(false, deferred1.isCompleted)
            assertEquals(1, locker.activeUsersFor(repo.featureContext, candidateKey))

            // Second mutation on the same candidateKey dispatched
            val deferred2 = scope.async {
                repo.upsert(candidateKey) { existing ->
                    existing!!.copy(countValue = 42)
                }
            }
            scope.runCurrent() // Second intent blocked
            assertEquals(false, deferred2.isCompleted)
            assertEquals(1, transactor.executionCount)
            assertEquals(2, locker.activeUsersFor(repo.featureContext, candidateKey))

            // Advance past the retry backoff delay (10ms * random multiplier)
            scope.advanceTimeBy(30.milliseconds)
            scope.runCurrent()
            val result1 = deferred1.await()
            val result2 = deferred2.await()

            // -- Assertions --
            assertEquals(candidateKey, result1)
            assertEquals(candidateKey, result2)
            // 1 failed attempt + 1 retry success (Op 1) + 1 direct success (Op 2)
            assertEquals(3, transactor.executionCount)

            val stored = repo.storedEntities[candidateKey]
            assertNotNull(stored)
            assertEquals(1, repo.storedEntities.size)
            assertEquals("RETRY_SUCCESS", stored.textValue)
            assertEquals(42, stored.countValue)

            assertEquals(2, intentStore.intents.size, "Both mutations recorded distinct intents")
            assertEquals(2, workerHook.invalidationCount, "Worker notified after each commit")
        }

    @Test
    fun concurrentMutations_withSharedAndDistinctKeysAndTransientDbBusy_recoversAndPersistsCorrectly() =
        runEnv { scope ->
            setupValidContext()
            val key1 = 306L
            val key2 = 307L
            val multiThreadedRepo = createIntegratedMultiThreadedRepo(logger, fakeBufferProvider)
            multiThreadedRepo.seed(FeatureEntity(id = key1, countValue = 0, textValue = "1"))
            multiThreadedRepo.seed(FeatureEntity(id = key2, countValue = 0, textValue = "2"))
            transactor.shouldThrow = MochaException.Transient.DatabaseBusy("SQLite database locked")

            val key1Workers = 3
            val key2Workers = 3
            val totalWorkers = key1Workers + key2Workers
            val operationsPerWorker = 3
            val readySignals = List(totalWorkers) { CompletableDeferred<Unit>() }
            val startGate = CompletableDeferred<Unit>()

            val key1Jobs = List(key1Workers) { index ->
                scope.launch(Dispatchers.Default) {
                    readySignals[index].complete(Unit)
                    startGate.await()

                    repeat(operationsPerWorker) {
                        multiThreadedRepo.upsert(key1) { existing ->
                            val currentCount = existing?.countValue ?: 0
                            existing!!.copy(countValue = currentCount + 1)
                        }
                    }
                }
            }
            val key2Jobs = List(key2Workers) { index ->
                scope.launch(Dispatchers.Default) {
                    readySignals[key1Workers + index].complete(Unit)
                    startGate.await()

                    repeat(operationsPerWorker) {
                        multiThreadedRepo.upsert(key2) { existing ->
                            val currentCount = existing?.countValue ?: 0
                            existing!!.copy(countValue = currentCount + 1)
                        }
                    }
                }
            }
            readySignals.awaitAll()

            startGate.complete(Unit)
            (key1Jobs + key2Jobs).joinAll()

            // --- Assertions ---
            val expectedKey1Count = key1Workers * operationsPerWorker
            val finalKey1Entity = multiThreadedRepo.storedEntities[key1]
            assertNotNull(finalKey1Entity)
            assertEquals("1", finalKey1Entity.textValue)
            assertEquals(expectedKey1Count, finalKey1Entity.countValue)

            val expectedKey2Count = key2Workers * operationsPerWorker
            val finalKey2Entity = multiThreadedRepo.storedEntities[key2]
            assertNotNull(finalKey2Entity)
            assertEquals("2", finalKey2Entity.textValue)
            assertEquals(expectedKey2Count, finalKey2Entity.countValue)

            // Side-effects
            val totalExpectedIntents = expectedKey1Count + expectedKey2Count
            assertEquals(0, locker.activeKeysCount)
            assertEquals(totalExpectedIntents, intentStore.intents.size)
            assertEquals(totalExpectedIntents, workerHook.invalidationCount)
            assertEquals(totalExpectedIntents + 1, hlcFactory.getNextHlcCallCount)
        }

}