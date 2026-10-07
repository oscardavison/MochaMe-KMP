@file:OptIn(ExperimentalKermitApi::class)

package com.mochame.sync.infrastructure

import co.touchlab.kermit.ExperimentalKermitApi
import com.mochame.support.MochaPlatformTest
import com.mochame.support.runUnitEnvironment
import com.mochame.sync.api.boot.BootState
import com.mochame.sync.api.exceptions.MochaException
import com.mochame.sync.api.metadata.MutationOp
import com.mochame.sync.di.fixtures.FixturesNodeConfig
import com.mochame.sync.domain.model.SyncStatus
import com.mochame.sync.di.infrastructure.LocalFirstEngineTestEnv
import com.mochame.sync.di.infrastructure.LocalFirstRepoTestModule
import com.mochame.sync.domain.model.DecodeContext
import com.mochame.sync.internal.fixtures.serialization.FakeFeatureCodec
import com.mochame.sync.internal.fixtures.serialization.FeatureEntity
import com.mochame.sync.internal.fixtures.serialization.deriveContext
import com.mochame.sync.utils.bitmaskOf
import com.mochame.sync.utils.toBitmask
import com.mochame.utils.fixtures.TestHlcFactory
import com.mochame.utils.fixtures.TestNodeId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.io.IOException
import org.koin.plugin.module.dsl.modules
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private inline fun runEnv(crossinline block: suspend LocalFirstEngineTestEnv.(TestScope) -> Unit) =
    runUnitEnvironment<LocalFirstEngineTestEnv>(
        koinSetup = { modules(LocalFirstRepoTestModule::class) },
        block = block
    )

@OptIn(ExperimentalCoroutinesApi::class)
internal class DefaultLocalFirstEngineTest : MochaPlatformTest() {

    // -----------------------------------------------------------
    // Boot Guard
    // -----------------------------------------------------------

    @Test
    fun should_unwrapAndPropagateException_on_bootCriticalFailure() = runEnv {
        // Given
        val rootCause = IllegalStateException("Corrupt local database")
        bootProvider.updateState(
            BootState.LockOut(
                message = "DB_CORRUPT",
                cause = rootCause
            )
        )

        // When / Then
        val thrown = assertFailsWith<IllegalStateException> {
            repo.delete(5L)
        }
        assertEquals("Corrupt local database", thrown.message)
    }

    @Test
    fun should_suspendUntilTransitionToActive_when_bootIsInitializingOrIdle() = runEnv { scope ->
        // Given
        hlcFactory.hydrate(null, TestNodeId.A)
        bootProvider.updateState(BootState.Init)
        repo.seed(FeatureEntity(id = 1L))

        // When
        var completed = false
        val deferred = scope.async {
            repo.delete(1L)
            completed = true
        }

        scope.runCurrent()
        assertFalse(completed)
        assertFalse(deferred.isCompleted)

        bootProvider.updateState(BootState.Idle)
        scope.runCurrent()
        assertFalse(completed)

        bootProvider.updateState(BootState.Ready)
        scope.runCurrent()

        // Then
        assertTrue(completed)
        assertTrue(deferred.isCompleted)
    }

    @Test
    fun should_timeoutAndThrow_when_bootRemainsInitializingPastDeadline() = runEnv { scope ->
        // Given
        bootProvider.updateState(BootState.Init)

        // When / Then
        val error = assertFailsWith<MochaException.Persistent.BootInitializationError> {
            repo.delete(1L)
        }

        assertContains(error.message, "timed out")
        assertEquals(
            FixturesNodeConfig.BOOT_TIMEOUT.inWholeMilliseconds,
            scope.testScheduler.currentTime
        )
    }

    @Test
    fun should_returnImmediately_when_bootIsReady() = runEnv { scope ->
        // Given
        setupValidContext()

        // When
        repo.upsert(1L) { FeatureEntity() }

        // Then
        assertEquals(0L, scope.testScheduler.currentTime)
    }

    // -----------------------------------------------------------
    // Local Pipeline
    // -----------------------------------------------------------

    @Test
    fun should_stampHlcAndRecordIntentAndNotifyWorker_on_localUpsertWithNewEntity() = runEnv {
        // Given
        setupValidContext()
        val candidateKey = 101L

        // When
        val result = repo.upsert(candidateKey) { FeatureEntity(id = candidateKey, textValue = "NEW_ITEM") }

        // Then
        assertEquals(101L, result)

        val stored = repo.storedEntities[candidateKey]
        assertNotNull(stored)
        assertEquals("NEW_ITEM", stored.textValue)
        assertEquals(1, hlcFactory.getNextHlcCallCount)
        assertEquals(stored.hlc, nodeManager.getMaxHlc())

        val recordedIntents = intentStore.intents
        assertEquals(1, recordedIntents.size)
        val intent = recordedIntents.first()
        assertEquals(candidateKey, intent.candidateKey)
        assertEquals(MutationOp.UPSERT, intent.operation)
        assertEquals(SyncStatus.PENDING, intent.syncStatus)
        assertEquals(stored.hlc, intent.hlc)
        assertNotNull(intent.payload)
        assertNull(intent.overflowBlobId)

        assertEquals(1, workerHook.invalidationCount)
    }

    @Test
    fun should_recordDeletionIntentAndPersist_on_localDeleteWithActiveEntity() = runEnv {
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
    fun should_skipWithoutErrorOrIntent_on_localDeleteWithNonExistentRecord() = runEnv {
        // Given
        setupValidContext()
        val nonExistentKey = 999L

        // When
        val result = repo.delete(nonExistentKey)

        // Then
        assertEquals(0L, result)
        assertEquals(0, intentStore.intents.size)
        assertEquals(0, workerHook.invalidationCount)
        assertEquals(0, hlcFactory.getNextHlcCallCount)
    }

    @Test
    fun should_skipWithoutErrorOrIntent_on_localDeleteWithAlreadyDeletedEntity() = runEnv {
        // Given
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

        // When
        val result = repo.delete(candidateKey)

        // Then
        assertEquals(0L, result)
        assertEquals(0, intentStore.intents.size)
        assertEquals(0, workerHook.invalidationCount)
        assertEquals(initialHlcCalls, hlcFactory.getNextHlcCallCount)
    }

    @Test
    fun should_computeFieldDiffAndStampUpdatedFieldHlcs_on_localUpsertWithExistingEntity() = runEnv {
        // Given
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

        // When
        val result = integratedRepo.upsert(candidateKey) { existing ->
            existing!!.copy(textValue = "UPDATED")
        }

        // Then
        assertEquals(102L, result)
        val updated = integratedRepo.storedEntities[candidateKey]
        assertNotNull(updated)
        assertEquals("UPDATED", updated.textValue)
        assertEquals(10, updated.countValue)
        assertTrue(updated.hlc > initialHlc)
        assertNotEquals(updated.fieldHlcs, initialEntity.fieldHlcs)

        val recordedIntents = intentStore.intents
        assertEquals(1, recordedIntents.size)
        assertEquals(MutationOp.UPSERT, recordedIntents.first().operation)
        assertEquals("OP:UPSERT [4]", recordedIntents.first().diagnosticSummary)
        assertEquals(1, workerHook.invalidationCount)
    }

    @Test
    fun should_returnViaOnSkipWithoutRecordingIntent_when_localUpsertEntityIsUnchanged() = runEnv {
        // Given
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

        // When
        val result = integratedRepo.upsert(candidateKey) { initialEntity }

        // Then
        assertEquals(0L, result)
        assertTrue(writer.logs.any { "Skipping" in it.message })
        assertEquals(0, intentStore.intents.size)
        assertEquals(0, workerHook.invalidationCount)
        assertEquals(2, hlcFactory.getNextHlcCallCount)
    }

    @Test
    fun should_restoreDeletionAndEmitRestoreIntent_on_localUpsertWithDeletedEntity() = runEnv {
        // Given
        val integratedRepo = createCodecIntegratedRepo()
        val candidateKey = 106L
        val initialHlc = TestHlcFactory.createWithOffset(offset = (-1).minutes)
        setupValidContext()

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
        assertNotNull(deletedEntity)
        assertTrue(deletedEntity.isDeleted)

        // When
        val result = integratedRepo.upsert(candidateKey) { existing ->
            existing!!.copy(
                isDeleted = false,
                textValue = "RESTORED_STATE",
                countValue = 1
            )
        }

        // Then
        assertEquals(candidateKey, result)
        val stored = integratedRepo.storedEntities[candidateKey]
        assertNotNull(stored)
        assertFalse(stored.isDeleted)
        assertEquals("RESTORED_STATE", stored.textValue)
        assertEquals(1, stored.countValue)
        assertTrue(stored.hlc > deletedEntity.hlc)
        assertNotEquals(deletedEntity.fieldHlcs, stored.fieldHlcs)

        assertEquals(2, intentStore.intents.size)
        val restoreIntent = intentStore.intents.last()
        assertEquals(MutationOp.UPSERT, restoreIntent.operation)
        assertEquals(SyncStatus.PENDING, restoreIntent.syncStatus)
        assertEquals(stored.hlc, restoreIntent.hlc)
        assertNotNull(restoreIntent.payload)

        assertEquals(2, workerHook.invalidationCount)
    }

    @Test
    fun should_skipGracefullyWithoutRecordingIntent_when_remoteDeleteArrivesForNullExisting() = runEnv {
        // Given
        setupValidContext()
        val candidateKey = 112L
        val remoteHlc = TestHlcFactory.createWithOffset((-1).minutes)
        val decodeContext = DecodeContext(
            primaryKey = candidateKey,
            hlc = remoteHlc,
            op = MutationOp.DELETE,
            featureSchemaVersion = 1,
            changedMask = 0L
        )

        // When
        repo.processRemoteIntent(decodeContext, FakeFeatureCodec.BYTES_PRESET)

        // Then
        val stored = repo.storedEntities[candidateKey]
        assertNull(stored)
        assertTrue(writer.logs.any { "Non-existent local record" in it.message })
        assertEquals(0, intentStore.intents.size)
        assertEquals(0, workerHook.invalidationCount)
    }

    // -----------------------------------------------------------
    // Blob Staging
    // -----------------------------------------------------------

    @Test
    fun should_embedPayloadDirectlyWithoutBlobStaging_on_localCommitWithInlinePayload() = runEnv {
        // Given
        setupValidContext()
        val integratedRepo = createCodecIntegratedRepo()
        val candidateKey = 201L

        // When
        val result = integratedRepo.upsert(candidateKey) { FeatureEntity(id = candidateKey) }

        // Then
        assertEquals(candidateKey, result)
        val recordedIntents = intentStore.intents
        val intent = recordedIntents.first()

        assertEquals(1, recordedIntents.size)
        assertNotNull(intent.payload)
        assertNull(intent.overflowBlobId)
        assertEquals(MutationOp.UPSERT, intent.operation)
        assertEquals(SyncStatus.PENDING, intent.syncStatus)
        assertEquals(1, workerHook.invalidationCount)
        assertEquals(1, hlcFactory.getNextHlcCallCount)
    }

    @Test
    fun should_stageBlobAndCommitPostTransaction_on_localCommitWithOverflowPayload() = runEnv {
        // Given
        setupValidContext()
        val integratedRepo = createCodecIntegratedRepo()
        val candidateKey = 202L
        val largeText = "A".repeat(70_000)

        // When
        val result = integratedRepo.upsert(candidateKey) {
            FeatureEntity(id = candidateKey, textValue = largeText)
        }

        // Then
        assertEquals(candidateKey, result)
        val recordedIntents = intentStore.intents
        val intent = recordedIntents.first()
        val blobId = intent.overflowBlobId

        assertEquals(1, recordedIntents.size)
        assertEquals(candidateKey, intent.candidateKey)
        assertNull(intent.payload)
        assertNotNull(blobId)
        assertTrue(blobStore.existsInCommitted(blobId))
        assertEquals(1, workerHook.invalidationCount)
    }

    @Test
    fun should_propagateExceptionAndAbort_when_blobStagingThrowsBeforeTransaction() = runEnv {
        // Given
        setupValidContext()
        val integratedRepo = createCodecIntegratedRepo()
        val candidateKey = 203L
        val largeText = "A".repeat(70_000)
        blobStore.stageError = IOException("Staging failed: Disk full")

        // When / Then
        assertFailsWith<MochaException.Persistent.IOFailure> {
            integratedRepo.upsert(candidateKey) {
                FeatureEntity(id = candidateKey, textValue = largeText)
            }
        }

        assertEquals(0, intentStore.intents.size)
        assertEquals(0, workerHook.invalidationCount)
        assertNull(repo.storedEntities[candidateKey])
    }

    @Test
    fun should_abortStagedBlobAndSuppressWorkerNotification_when_preCommitFails() = runEnv {
        // Given
        setupValidContext()
        val candidateKey = 204L
        val largeText = "A".repeat(70_000)
        val integratedRepo = createCodecIntegratedRepo()
        transactor.shouldThrow = IllegalStateException("Database write locked")

        // When / Then
        assertFailsWith<MochaException> {
            integratedRepo.upsert(candidateKey) {
                FeatureEntity(id = candidateKey, textValue = largeText)
            }
        }

        assertEquals(0, intentStore.intents.size)
        assertEquals(0, workerHook.invalidationCount)
        assertEquals(0, blobStore.listPendingHashes().size)
    }

    @Test
    fun should_throwBlobResolutionPending_when_postCommitBlobCommitFails() = runEnv {
        // Given
        setupValidContext()
        val candidateKey = 205L
        val largeText = "A".repeat(70_000)
        val integratedRepo = createCodecIntegratedRepo()
        blobStore.commitError = IOException("Access issue on path")

        // When / Then
        val thrown = assertFailsWith<MochaException.Transient.BlobResolutionPending> {
            integratedRepo.upsert(candidateKey) {
                FeatureEntity(id = candidateKey, textValue = largeText)
            }
        }

        assertNotNull(thrown.blobId)
        assertEquals(1, intentStore.intents.size)
        assertEquals(1, workerHook.invalidationCount)
        assertNotNull(integratedRepo.storedEntities[candidateKey])
    }

    // -----------------------------------------------------------
    // Remote Intent Processing
    // -----------------------------------------------------------

    @Test
    fun should_preserveDeletionWhileMergingFieldValues_when_localDeleteIsMoreRecentThanIncomingUpsert() = runEnv {
        // Given
        val integratedRepo = createCodecIntegratedRepo()
        val candidateKey = 107L
        val deletionHlc = TestHlcFactory.createWithOffset((-1).minutes)
        setupValidContext()

        val initialEntity = FeatureEntity(
            id = candidateKey,
            hlc = deletionHlc,
            isDeleted = false,
            textValue = "INITIAL_DELETED_TEXT",
            countValue = 10
        )
        integratedRepo.seed(initialEntity)
        integratedRepo.delete(candidateKey)
        val deletedEntity = integratedRepo.storedEntities[candidateKey]!!
        assertTrue(deletedEntity.fieldHlcs.isNotEmpty())

        val remoteState = FeatureEntity(
            id = candidateKey,
            hlc = TestHlcFactory.createWithOffset((-2).minutes),
            textValue = "REMOTE_UPDATED_TEXT",
            countValue = 10
        )
        val payload = integratedCodec.encode(remoteState, null)
        val changedTags = integratedCodec.computeChangedTags(remoteState, null)
        val decodeContext = remoteState.deriveContext(changedMask = changedTags.toBitmask())
        assertNotNull(payload)

        // When
        integratedRepo.processRemoteIntent(decodeContext, payload)

        // Then
        val finalEntity = integratedRepo.storedEntities[candidateKey]
        assertNotNull(finalEntity)
        assertTrue(finalEntity.isDeleted)
        assertNull(finalEntity.textValue)
        assertNull(finalEntity.countValue)
        assertEquals(deletedEntity.fieldHlcs, finalEntity.fieldHlcs)

        assertEquals(1, intentStore.intents.size)
        assertEquals(1, workerHook.invalidationCount)
    }

    @Test
    fun should_restoreEntityAndApplyFieldDiff_when_incomingUpsertIsMoreRecentThanDelete() = runEnv {
        // Given
        val integratedRepo = createCodecIntegratedRepo()
        val candidateKey = 108L
        val initialHlc = TestHlcFactory.create()
        setupValidContext(initialHlc)

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
        assertNotNull(deletedEntity)
        assertTrue(deletedEntity.isDeleted)

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
        assertNotNull(payload)

        // When
        integratedRepo.processRemoteIntent(decodeContext, payload)

        // Then
        val finalEntity = integratedRepo.storedEntities[candidateKey]
        assertNotNull(finalEntity)
        assertFalse(finalEntity.isDeleted)
        assertNull(finalEntity.textValue)
        assertEquals(initialEntity.countValue, finalEntity.countValue)
        assertNotEquals(deletedEntity.fieldHlcs, finalEntity.fieldHlcs)

        assertEquals(1, intentStore.intents.size)
        assertEquals(1, workerHook.invalidationCount)
        assertEquals(1, hlcFactory.getNextHlcCallCount)
    }

    @Test
    fun should_mergeFieldsCorrectly_when_incomingUpsertIsCaughtBetweenTwoLocalUpserts() = runEnv {
        // Given
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
        assertNotNull(payload)

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

        // When: Device A receives and ingests Device B's remote intent from T1
        integratedRepo.processRemoteIntent(decodeContext, payload)

        // Then
        val finalEntity = integratedRepo.storedEntities[candidateKey]
        assertNotNull(finalEntity)
        assertEquals("REMOTE_UPDATED_TEXT", finalEntity.textValue)
        assertEquals(99, finalEntity.countValue)
        assertFalse(finalEntity.isDeleted)
        assertNotEquals(initialEntity.fieldHlcs, finalEntity.fieldHlcs)

        assertEquals(1, intentStore.intents.size)
        assertEquals(1, workerHook.invalidationCount)
    }
}
