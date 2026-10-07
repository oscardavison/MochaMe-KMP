@file:OptIn(ExperimentalKermitApi::class)

package com.mochame.sync.infrastructure

import co.touchlab.kermit.ExperimentalKermitApi
import com.mochame.support.MochaPlatformTest
import com.mochame.support.runUnitEnvironment
import com.mochame.sync.api.exceptions.MochaException
import com.mochame.sync.di.infrastructure.LocalFirstEngineTestEnv
import com.mochame.sync.di.infrastructure.LocalFirstRepoTestModule
import com.mochame.sync.internal.fixtures.serialization.FeatureEntity
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
import org.koin.plugin.module.dsl.modules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

private inline fun runEnv(crossinline block: suspend LocalFirstEngineTestEnv.(TestScope) -> Unit) =
    runUnitEnvironment<LocalFirstEngineTestEnv>(
        koinSetup = { modules(LocalFirstRepoTestModule::class) },
        block = block
    )

@OptIn(ExperimentalCoroutinesApi::class)
class LocalFirstEngineConcurrencyTest : MochaPlatformTest() {

    @Test
    fun should_serializeConcurrentMutationsOnSameCandidateKey_when_locked() = runEnv { scope ->
        // Given
        setupValidContext()
        val candidateKey = 301L
        val holdFirstMutation = CompletableDeferred<Unit>()
        val firstMutationEntered = CompletableDeferred<Unit>()
        var secondMutationEntered = false

        // When: first coroutine enters mutation lock
        val job1 = scope.launch {
            repo.upsert(candidateKey) {
                firstMutationEntered.complete(Unit)
                holdFirstMutation.await()
                FeatureEntity(id = candidateKey, textValue = "FIRST_MUTATION")
            }
        }

        scope.runCurrent()
        assertTrue(firstMutationEntered.isCompleted)
        assertEquals(1, locker.activeUsersFor(repo.featureContext, candidateKey))

        // And: second coroutine attempts mutation on the same candidateKey
        val job2 = scope.launch {
            repo.upsert(candidateKey) {
                secondMutationEntered = true
                FeatureEntity(id = candidateKey, textValue = "SECOND_MUTATION")
            }
        }

        scope.runCurrent()
        assertFalse(secondMutationEntered)
        assertEquals(2, locker.activeUsersFor(repo.featureContext, candidateKey))

        // Release first mutation
        holdFirstMutation.complete(Unit)
        scope.runCurrent()
        job1.join()
        job2.join()

        // Then: second mutation executes after first releases
        assertTrue(secondMutationEntered)
        assertEquals("SECOND_MUTATION", repo.storedEntities[candidateKey]?.textValue)
        assertNull(locker.activeUsersFor(repo.featureContext, candidateKey))
        assertEquals(0, locker.activeKeysCount)
    }

    @Test
    fun should_allowParallelExecutionAcrossDistinctCandidateKeys_when_locked() = runEnv { scope ->
        // Given
        setupValidContext()
        val keyA = 302L
        val keyB = 303L
        val holdKeyA = CompletableDeferred<Unit>()
        var keyBCompleted = false

        // When: coroutine on Key A suspends mid-lock
        val jobA = scope.launch {
            repo.upsert(keyA) {
                holdKeyA.await()
                FeatureEntity(id = keyA, textValue = "KEY_A")
            }
        }
        scope.runCurrent()
        assertEquals(1, locker.activeUsersFor(repo.featureContext, keyA))

        // And: coroutine on Key B proceeds without contention
        val jobB = scope.launch {
            repo.upsert(keyB) {
                FeatureEntity(id = keyB, textValue = "KEY_B")
            }
            keyBCompleted = true
        }
        scope.runCurrent()

        // Then: Key B completes independently while Key A is still held
        assertTrue(keyBCompleted)
        assertEquals("KEY_B", repo.storedEntities[keyB]?.textValue)
        assertEquals(1, locker.activeKeysCount)

        holdKeyA.complete(Unit)
        scope.runCurrent()
        jobA.join()
        jobB.join()

        assertEquals(2, repo.storedEntities.size)
        assertEquals(0, locker.activeKeysCount)
    }

    @Test
    fun should_cleanUpRegistryAndReleaseLock_when_exceptionIsThrown() = runEnv {
        // Given
        setupValidContext()
        val candidateKey = 304L

        // When / Then: force exception inside locked execution block
        assertFailsWith<MochaException.Transient.StateIssue> {
            repo.upsert(candidateKey) {
                throw IllegalStateException("Domain validation failure")
            }
        }
        assertNull(locker.activeUsersFor(repo.featureContext, candidateKey))
        assertEquals(0, locker.activeKeysCount)

        val result = repo.upsert(candidateKey) {
            FeatureEntity(id = candidateKey, textValue = "RECOVERED")
        }

        assertEquals(candidateKey, result)
        assertEquals("RECOVERED", repo.storedEntities[candidateKey]?.textValue)
        assertEquals(0, locker.activeKeysCount)
    }

    @Test
    fun should_serializeAndMergeBothMutations_when_concurrentUpsertOccursDuringDatabaseRetry() =
        runEnv { scope ->
            // Given
            setupValidContext()
            val candidateKey = 306L
            transactor.shouldThrow =
                MochaException.Transient.DatabaseBusy("Simulated SQLite database locked")

            // When: first mutation triggers database retry and delays
            val deferred1 = scope.async {
                repo.upsert(candidateKey) { existing ->
                    (existing ?: FeatureEntity(id = candidateKey)).copy(
                        textValue = "RETRY_SUCCESS",
                        countValue = 0
                    )
                }
            }
            scope.runCurrent()
            assertEquals(1, transactor.executionCount)
            assertFalse(deferred1.isCompleted)
            assertEquals(1, locker.activeUsersFor(repo.featureContext, candidateKey))

            // And: second mutation on same key is queued
            val deferred2 = scope.async {
                repo.upsert(candidateKey) { existing ->
                    existing!!.copy(countValue = 42)
                }
            }
            scope.runCurrent()
            assertFalse(deferred2.isCompleted)
            assertEquals(1, transactor.executionCount)
            assertEquals(2, locker.activeUsersFor(repo.featureContext, candidateKey))

            // Advance past retry backoff delay
            scope.advanceTimeBy(30.milliseconds)
            scope.runCurrent()
            val result1 = deferred1.await()
            val result2 = deferred2.await()

            // Then
            assertEquals(candidateKey, result1)
            assertEquals(candidateKey, result2)
            assertEquals(3, transactor.executionCount)

            val stored = repo.storedEntities[candidateKey]
            assertNotNull(stored)
            assertEquals(1, repo.storedEntities.size)
            assertEquals("RETRY_SUCCESS", stored.textValue)
            assertEquals(42, stored.countValue)

            assertEquals(2, intentStore.intents.size)
            assertEquals(2, workerHook.invalidationCount)
        }

    @Test
    fun should_recoverAndPersistCorrectly_on_concurrentMutationsWithTransientDbBusy() =
        runEnv { scope ->
            // Given
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

            // When: multiple workers launch concurrently on shared and distinct keys
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

            // Then
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

            val totalExpectedIntents = expectedKey1Count + expectedKey2Count
            assertEquals(0, locker.activeKeysCount)
            assertEquals(totalExpectedIntents, intentStore.intents.size)
            assertEquals(totalExpectedIntents, workerHook.invalidationCount)
            assertEquals(totalExpectedIntents + 1, hlcFactory.getNextHlcCallCount)
        }
}
