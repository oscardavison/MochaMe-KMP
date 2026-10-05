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
import com.mochame.sync.di.api.LocalFirstRepoTestEnv
import com.mochame.sync.di.api.LocalFirstRepoTestModule
import com.mochame.sync.internal.fixtures.serialization.FeatureEntity
import com.mochame.utils.fixtures.TestHlcFactory
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

private inline fun runEnv(crossinline block: suspend LocalFirstRepoTestEnv.(TestScope) -> Unit) =
    runUnitEnvironment<LocalFirstRepoTestEnv>(
        koinSetup = { modules(LocalFirstRepoTestModule::class) },
        block = block
    )

/**
 * Unit & Integration tests for local feature repository mutations ([syncUpsert], [syncDelete]),
 * candidate key locking, transaction commits, and boot readiness checks.
 *
 * Remote intent processing tests are isolated in [com.mochame.sync.engine.DefaultLocalFirstEngineTest].
 */
@ExperimentalCoroutinesApi
class LocalFirstRepositoryTest : MochaPlatformTest() {

    // -----------------------------------------------------------
    // LOCAL OPERATIONS PIPELINE
    // -----------------------------------------------------------

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
    fun awaitReady_onCriticalFailureWithException_unwrapsAndThrowsException() = runEnv {
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
        hlcFactory.hydrate(null, com.mochame.utils.fixtures.TestNodeId.A)
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
    // LOCAL MUTATIONS & FIELD DIFFING
    // -----------------------------------------------------------

    @Test
    fun localUpsert_onNewEntity_stampsHlc_recordsPendingIntent_andNotifiesWorker() = runEnv {
        setupValidContext()
        val candidateKey = 101L

        val result =
            repo.upsert(candidateKey) { FeatureEntity(id = candidateKey, textValue = "NEW_ITEM") }

        assertEquals(101L, result)

        val stored = repo.storedEntities[candidateKey]
        assertNotNull(stored, "Stored entity")
        assertEquals("NEW_ITEM", stored.textValue, "Text value")
        assertEquals(1, hlcFactory.getNextHlcCallCount, "HLC call count")
        assertEquals(stored.hlc, nodeManager.getMaxHlc(), "Max HLC")

        val recordedIntents = intentStore.intents
        assertEquals(1, recordedIntents.size, "Intent count")
        val intent = recordedIntents.first()
        assertEquals(candidateKey, intent.candidateKey, "Candidate key")
        assertEquals(MutationOp.UPSERT, intent.operation, "Operation")
        assertEquals(SyncStatus.PENDING, intent.syncStatus, "Sync status")
        assertEquals(stored.hlc, intent.hlc, "Intent HLC")
        assertNotNull(intent.payload, "Payload")
        assertEquals(null, intent.overflowBlobId, "Overflow blob ID")

        assertEquals(1, workerHook.invalidationCount, "Invalidation Hook")
    }

    @Test
    fun localDelete_onExistingActiveEntity_recordsDeletionIntent_andPersists() = runEnv {
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

        val result = repo.delete(candidateKey)

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
            existing!!.copy(textValue = "UPDATED")
        }

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

            val result = integratedRepo.upsert(candidateKey) { initialEntity }

            assertEquals(0L, result)
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

        val result = integratedRepo.upsert(candidateKey) { existing ->
            existing!!.copy(
                isDeleted = false,
                textValue = "RESTORED_STATE",
                countValue = 1
            )
        }
        assertEquals(candidateKey, result)

        val stored = integratedRepo.storedEntities[candidateKey]
        assertNotNull(stored, "Stored entity exists after restore")
        assertEquals(false, stored.isDeleted, "Deletion status is restored")
        assertEquals("RESTORED_STATE", stored.textValue)
        assertEquals(1, stored.countValue)
        assertTrue(stored.hlc > deletedEntity.hlc, "HLC to be updated")
        assertNotEquals(deletedEntity.fieldHlcs, stored.fieldHlcs, "Field HLCs must be updated")

        assertEquals(2, intentStore.intents.size, "Contains delete and subsequent restore intents")
        val restoreIntent = intentStore.intents.last()
        assertEquals(MutationOp.UPSERT, restoreIntent.operation)
        assertEquals(SyncStatus.PENDING, restoreIntent.syncStatus)
        assertEquals(stored.hlc, restoreIntent.hlc)
        assertNotNull(restoreIntent.payload, "Payload contains encoded restore delta")

        assertEquals(2, workerHook.invalidationCount, "Invalidation triggered twice")
    }

    // -----------------------------------------------------------
    // COMMIT & OVERFLOW STAGING
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
        assertTrue(blobStore.existsInCommitted(blobId))
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
        assertEquals(0, blobStore.listPendingHashes().size, "blob must be aborted")
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
    // KEYED LOCKER CONCURRENCY
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