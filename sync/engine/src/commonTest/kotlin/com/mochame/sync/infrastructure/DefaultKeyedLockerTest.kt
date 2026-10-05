package com.mochame.sync.infrastructure

import com.mochame.support.MochaPlatformTest
import com.mochame.support.runUnitEnvironment
import com.mochame.sync.api.metadata.FeatureContext
import com.mochame.sync.di.SyncInfraModule
import com.mochame.sync.common.InternalTestApi
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.koin.plugin.module.dsl.modules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds


private inline fun runEnv(crossinline block: suspend DefaultKeyedLocker.(TestScope) -> Unit) =
    runUnitEnvironment<DefaultKeyedLocker>(
        koinSetup = { modules(SyncInfraModule::class) },
        block = block
    )


@OptIn(InternalTestApi::class)
@ExperimentalCoroutinesApi
class DefaultKeyedLockerTest : MochaPlatformTest() {

    // -----------------------------------------------------------
    // IDENTITY & KEY ISOLATION
    // -----------------------------------------------------------
    @Test
    fun should_executeConcurrently_when_sameIdAcrossDistinctFeatureContexts() = runEnv { scope ->
        val sharedId = 1L
        val aStarted = CompletableDeferred<Unit>()
        val bStarted = CompletableDeferred<Unit>()
        val releaseA = CompletableDeferred<Unit>()
        val bFinished = CompletableDeferred<Unit>()

        // Coroutine A: Lock STUB_A for ID 1
        val jobA = scope.launch {
            withLock(FeatureContext.TEST_STUB_A, sharedId) {
                aStarted.complete(Unit)
                releaseA.await()
            }
        }

        // Coroutine B: Lock STUB_B for ID 1
        val jobB = scope.launch {
            aStarted.await()
            withLock(FeatureContext.TEST_STUB_B, sharedId) {
                bStarted.complete(Unit)
                bFinished.complete(Unit)
            }
        }

        scope.runCurrent()
        assertTrue(aStarted.isCompleted, "A lock should be acquired")

        scope.runCurrent()
        assertTrue(bStarted.isCompleted)
        assertTrue(bFinished.isCompleted)

        releaseA.complete(Unit)
        scope.runCurrent()
        jobA.join()
        jobB.join()
        assertNull(activeUsersFor(FeatureContext.TEST_STUB_A, sharedId))
        assertNull(activeUsersFor(FeatureContext.TEST_STUB_B, sharedId))
    }

    @Test
    fun should_enforceStrictMutualExclusion_when_sameFeatureContextAndId() = runEnv { scope ->
        val targetContext = FeatureContext.TEST_STUB_A
        val targetId = 1L

        var concurrentExecutions = 0
        var maxConcurrentObserved = 0
        var job2Entered = false

        val job1Entered = CompletableDeferred<Unit>()
        val allowJob1Exit = CompletableDeferred<Unit>()

        val job1 = scope.launch {
            withLock(targetContext, targetId) {
                concurrentExecutions++
                maxConcurrentObserved = maxOf(maxConcurrentObserved, concurrentExecutions)
                job1Entered.complete(Unit)

                allowJob1Exit.await()

                concurrentExecutions--
            }
        }

        val job2 = scope.launch {
            job1Entered.await()
            withLock(targetContext, targetId) {
                concurrentExecutions++
                maxConcurrentObserved = maxOf(maxConcurrentObserved, concurrentExecutions)
                job2Entered = true
                concurrentExecutions--
            }
        }

        // Job 1 enters and holds lock, Job 2 suspends on the lock
        scope.runCurrent()
        assertTrue(job1Entered.isCompleted, "First caller should acquire the lock")
        assertFalse(job2Entered, "Second caller must be suspended and blocked")
        assertEquals(1, maxConcurrentObserved, "Only one coroutine may execute")

        // Release Job 1: Job 2 acquires and exits
        allowJob1Exit.complete(Unit)
        scope.runCurrent()

        job1.join()
        job2.join()

        assertTrue(job2Entered, "Second caller should complete after first releases")
        assertEquals(1, maxConcurrentObserved, "Max concurrent executions must strictly remain 1")
        assertEquals(0, concurrentExecutions, "All lock holders must have exited")
    }


    // -----------------------------------------------------------
    // USER REGISTRATION LIFECYCLE
    // ----------------------------------------------------------

    @Test
    fun should_trackActiveUsersAndRetainEntryUnderOverlap_when_multipleCallersQueue() =
        runEnv { scope ->
            val context = FeatureContext.TEST_STUB_A
            val key = 100L

            val job1Entered = CompletableDeferred<Unit>()
            val allowJob1Exit = CompletableDeferred<Unit>()
            val job2Entered = CompletableDeferred<Unit>()
            val allowJob2Exit = CompletableDeferred<Unit>()
            val job3Entered = CompletableDeferred<Unit>()
            val allowJob3Exit = CompletableDeferred<Unit>()

            // 1. Initial State: Registry is empty
            assertEquals(0, activeKeysCount, "Registry must be empty initially")
            assertNull(activeUsersFor(context, key), "No active users should exist for key")

            // 2. Launch Job 1 (Acquires Mutex)
            val job1 = scope.launch {
                withLock(context, key) {
                    job1Entered.complete(Unit)
                    allowJob1Exit.await()
                }
            }

            scope.runCurrent()
            assertTrue(job1Entered.isCompleted)
            assertEquals(1, activeKeysCount, "Map entry should be created")
            assertEquals(1, activeUsersFor(context, key), "activeUsers should increment to 1")

            // 3. Launch Job 2 & Job 3 (Queue behind Job 1)
            val job2 = scope.launch {
                withLock(context, key) {
                    job2Entered.complete(Unit)
                    allowJob2Exit.await()
                }
            }

            val job3 = scope.launch {
                withLock(context, key) {
                    job3Entered.complete(Unit)
                    allowJob3Exit.await()
                }
            }

            scope.runCurrent()
            assertEquals(1, activeKeysCount, "No duplicate map entries should be allocated")
            assertEquals(3, activeUsersFor(context, key), "must reflect 1 holder + 2 waiters")

            // 4. Release Job 1 -> Job 2 enters -> Counter decrements to 2 (Retention Under Overlap)
            allowJob1Exit.complete(Unit)
            scope.runCurrent()
            job1.join()

            assertTrue(job2Entered.isCompleted, "Job 2 must acquire mutex after Job 1 releases")
            assertEquals(1, activeKeysCount)
            assertEquals(2, activeUsersFor(context, key), "activeUsers must decrement to 2")

            // 5. Release Job 2 -> Job 3 enters -> Counter decrements to 1 (Retention Under Overlap)
            allowJob2Exit.complete(Unit)
            scope.runCurrent()
            job2.join()

            assertTrue(job3Entered.isCompleted, "Job 3 must acquire mutex after Job 2 releases")
            assertEquals(1, activeKeysCount, "Map entry must remain retained for last active user")
            assertEquals(1, activeUsersFor(context, key), "activeUsers must decrement to 1")

            // 6. Release Job 3 -> Zero active users -> Eviction from Registry
            allowJob3Exit.complete(Unit)
            scope.runCurrent()
            job3.join()

            assertEquals(0, activeKeysCount)
            assertNull(activeUsersFor(context, key), "Key entry must no longer exist in map")
        }

    @Test
    fun should_evictEntryImmediately_when_singleUserCompletes() = runEnv { scope ->
        val context = FeatureContext.TEST_STUB_A
        val key = 42L
        val entered = CompletableDeferred<Unit>()
        val allowExit = CompletableDeferred<Unit>()

        val job = scope.launch {
            withLock(context, key) {
                entered.complete(Unit)
                allowExit.await()
            }
        }

        scope.runCurrent()
        assertTrue(entered.isCompleted)
        assertEquals(1, activeKeysCount)
        assertEquals(1, activeUsersFor(context, key))

        allowExit.complete(Unit)
        scope.runCurrent()
        job.join()

        assertEquals(0, activeKeysCount, "Map entry must be evicted on completion")
        assertNull(activeUsersFor(context, key))
    }

    @Test
    fun should_reacquireCleanlyWithoutStaleState_after_completeEviction() = runEnv { scope ->
        val context = FeatureContext.TEST_STUB_A
        val key = 999L

        // Phase 1: Run first execution cycle to total eviction
        var firstExecutionDone = false
        withLock(context, key) {
            firstExecutionDone = true
        }

        assertTrue(firstExecutionDone)
        assertEquals(0, activeKeysCount, "Entry must be cleanly evicted after first cycle")
        assertNull(activeUsersFor(context, key))

        // Phase 2: Re-acquire the same key after eviction
        val secondCycleEntered = CompletableDeferred<Unit>()
        val allowSecondCycleExit = CompletableDeferred<Unit>()

        val job = scope.launch {
            withLock(context, key) {
                secondCycleEntered.complete(Unit)
                allowSecondCycleExit.await()
            }
        }

        scope.runCurrent()
        assertTrue(secondCycleEntered.isCompleted)
        assertEquals(1, activeKeysCount, "A fresh LockEntry must be created in registry")
        assertEquals(1, activeUsersFor(context, key), "New entry must have activeUsers == 1")

        allowSecondCycleExit.complete(Unit)
        scope.runCurrent()
        job.join()

        assertEquals(0, activeKeysCount, "Re-acquired entry must also evict cleanly on exit")
        assertNull(activeUsersFor(context, key))
    }

    // -----------------------------------------------------------
    // CANCELLATION
    // -----------------------------------------------------------

    @Test
    fun should_releaseLockAndEvictEntry_when_actionThrowsException() = runEnv { scope ->
        val context = FeatureContext.TEST_STUB_A
        val key = 501L

        assertFailsWith<IllegalStateException> {
            withLock(context, key) {
                assertEquals(1, activeKeysCount)
                assertEquals(1, activeUsersFor(context, key))
                error("Simulated domain processing failure")
            }
        }

        assertEquals(0, activeKeysCount, "Map entry must be evicted after uncaught exception")
        assertNull(activeUsersFor(context, key))

        var subsequentExecutionCompleted = false
        withLock(context, key) {
            subsequentExecutionCompleted = true
        }

        assertTrue(subsequentExecutionCompleted, "Lock must be immediately re-acquirable")
        assertEquals(0, activeKeysCount)
    }

    @Test
    fun should_executeNonCancellableCleanupAndReleaseLock_when_activeHolderIsCancelled() =
        runEnv { scope ->
            val context = FeatureContext.TEST_STUB_A
            val key = 502L

            val holderEntered = CompletableDeferred<Unit>()
            var holderCompleted = false
            val waiterEntered = CompletableDeferred<Unit>()
            var waiterCompleted = false

            // 1. Launch active lock holder
            val holderJob = scope.launch {
                withLock(context, key) {
                    holderEntered.complete(Unit)
                    CompletableDeferred<Unit>().await() // Suspend indefinitely inside critical section
                    holderCompleted = true
                }
            }

            scope.runCurrent()
            assertTrue(holderEntered.isCompleted)
            assertEquals(1, activeUsersFor(context, key))

            // 2. Launch queued waiter
            val waiterJob = scope.launch {
                withLock(context, key) {
                    waiterEntered.complete(Unit)
                    waiterCompleted = true
                }
            }

            scope.runCurrent()
            assertFalse(waiterEntered.isCompleted, "Waiter must be suspended waiting for holder")
            assertEquals(2, activeUsersFor(context, key), "Holder + Waiter registered")

            // 3. Cancel the active holder while inside critical section
            holderJob.cancelAndJoin()
            scope.runCurrent()

            assertFalse(holderCompleted, "Holder action should have been aborted")
            assertTrue(waiterEntered.isCompleted, "Cancelling must free Mutex for  waiter")

            waiterJob.join()
            assertTrue(waiterCompleted, "Waiter must complete successfully")
            assertEquals(0, activeKeysCount, "Registry must be empty once waiter completes")
            assertNull(activeUsersFor(context, key))
        }

    @Test
    fun should_decrementActiveUsersAndEvictOrphanedEntry_when_waiterJobIsCancelled() =
        runEnv { scope ->
            val context = FeatureContext.TEST_STUB_A
            val key = 503L

            val holderEntered = CompletableDeferred<Unit>()
            val allowHolderExit = CompletableDeferred<Unit>()
            val waiter1Entered = CompletableDeferred<Unit>()
            val waiter2Entered = CompletableDeferred<Unit>()

            // 1. Holder acquires lock
            val holderJob = scope.launch {
                withLock(context, key) {
                    holderEntered.complete(Unit)
                    allowHolderExit.await()
                }
            }

            // 2. Waiter 1 queues (will be cancelled)
            val waiter1Job = scope.launch {
                withLock(context, key) {
                    waiter1Entered.complete(Unit)
                }
            }

            // 3. Waiter 2 queues (will execute)
            val waiter2Job = scope.launch {
                withLock(context, key) {
                    waiter2Entered.complete(Unit)
                }
            }

            scope.runCurrent()
            assertTrue(holderEntered.isCompleted)
            assertEquals(3, activeUsersFor(context, key), "1 holder + 2 queued waiters")

            // 4. Cancel Waiter 1 while it is suspended waiting for the lock
            waiter1Job.cancelAndJoin()
            scope.runCurrent()

            assertFalse(
                waiter1Entered.isCompleted,
                "Cancelled waiter should never enter critical section"
            )
            assertEquals(
                2,
                activeUsersFor(context, key),
                "activeUsers must decrement to 2 after waiter cancellation"
            )

            // 5. Release Holder -> Waiter 2 acquires and completes
            allowHolderExit.complete(Unit)
            scope.runCurrent()

            holderJob.join()
            waiter2Job.join()

            assertTrue(waiter2Entered.isCompleted)
            assertEquals(0, activeKeysCount)
            assertNull(activeUsersFor(context, key))
        }

    @Test
    fun should_evictEntryImmediately_when_onlyWaitingCallerIsCancelledBeforeAcquisition() =
        runEnv { scope ->
            val context = FeatureContext.TEST_STUB_A
            val key = 504L

            val holderEntered = CompletableDeferred<Unit>()
            val allowHolderExit = CompletableDeferred<Unit>()
            val soleWaiterEntered = CompletableDeferred<Unit>()

            val holderJob = scope.launch {
                withLock(context, key) {
                    holderEntered.complete(Unit)
                    allowHolderExit.await()
                }
            }

            val waiterJob = scope.launch {
                withLock(context, key) {
                    soleWaiterEntered.complete(Unit)
                }
            }

            scope.runCurrent()
            assertTrue(holderEntered.isCompleted)
            assertEquals(2, activeUsersFor(context, key))

            // Cancel the only queued waiter
            waiterJob.cancelAndJoin()
            scope.runCurrent()

            assertEquals(1, activeUsersFor(context, key), "Only holder remains")

            // Release holder
            allowHolderExit.complete(Unit)
            scope.runCurrent()
            holderJob.join()

            assertFalse(soleWaiterEntered.isCompleted)
            assertEquals(0, activeKeysCount)
            assertNull(activeUsersFor(context, key))
        }

    @Test
    fun should_deadlockOrSuspendIndefinitely_when_reentrantLockInvokedOnSameKey() =
        runEnv { scope ->
            val context = FeatureContext.TEST_STUB_A
            val key = 505L

            val outerEntered = CompletableDeferred<Unit>()
            var innerEntered = false

            val reentrantJob = scope.launch {
                withLock(context, key) {
                    outerEntered.complete(Unit)
                    // Mutex is non-reentrant; attempting to acquire the same key within its own critical section
                    // will suspend indefinitely waiting on its own release.
                    withTimeoutOrNull(100.milliseconds) {
                        withLock(context, key) {
                            innerEntered = true
                        }
                    }
                }
            }

            scope.runCurrent()
            assertTrue(outerEntered.isCompleted, "Outer lock must be acquired")
            assertFalse(innerEntered, "Inner lock must fail to acquire")
            assertEquals(2, activeUsersFor(context, key))

            // Advance test clock to trigger timeout on the inner reentrant attempt
            scope.testScheduler.advanceTimeBy(101L)
            scope.runCurrent()

            reentrantJob.join()
            assertFalse(innerEntered, "Inner block must not execute")
            assertEquals(0, activeKeysCount, "Cleanup must restore state")
            assertNull(activeUsersFor(context, key))
        }


    // -----------------------------------------------------------
    // CONTENTION
    // -----------------------------------------------------------
    // Exposing variables using atomicfu as a possible solution. Test below it uses Mutex internally.
    private val insideCriticalSection = atomic(0)
    private val maxConcurrencyObserved = atomic(0)
    private val completedExecutions = atomic(0)

    @Test
    fun should_maintainIntegrityAndEvictCleanly_underMultithreadedThunderingHerd() =
        runEnv { scope ->
            insideCriticalSection.value = 0
            maxConcurrencyObserved.value = 0
            completedExecutions.value = 0

            val context = FeatureContext.TEST_STUB_A
            val sharedKey = 888L
            val totalCallers = 15
            val readySignals = List(totalCallers) { CompletableDeferred<Unit>() }
            val startGate = CompletableDeferred<Unit>()

            val jobs = List(totalCallers) { index ->
                scope.launch(Dispatchers.Default) {
                    readySignals[index].complete(Unit)
                    startGate.await()

                    withLock(context, sharedKey) {
                        val current = insideCriticalSection.incrementAndGet()
                        maxConcurrencyObserved.value = maxOf(maxConcurrencyObserved.value, current)

                        // Hold lock across a thread suspension to force heavy background contention
                        yield()
                        completedExecutions.incrementAndGet()
                        insideCriticalSection.decrementAndGet()
                    }
                }
            }
            readySignals.awaitAll()

            startGate.complete(Unit)
            jobs.joinAll()

            assertEquals(1, maxConcurrencyObserved.value)
            assertEquals(0, insideCriticalSection.value)
            assertEquals(totalCallers, completedExecutions.value)

            assertEquals(0, activeKeysCount)
            assertNull(activeUsersFor(context, sharedKey))
        }

    @Test
    fun should_isolateKeysAndMonitorPerKeyContention_underMultithreadedScatter() = runEnv { scope ->
        val keys = listOf(
            FeatureContext.TEST_STUB_A to 1L,
            FeatureContext.TEST_STUB_A to 2L,
            FeatureContext.TEST_STUB_B to 1L,
            FeatureContext.TEST_STUB_B to 2L,
            FeatureContext.UNRECOGNIZED_MODEL to 100L
        )
        val callersPerKey = 5
        val totalCallers = keys.size * callersPerKey

        val readySignals = List(totalCallers) { CompletableDeferred<Unit>() }
        val startGate = CompletableDeferred<Unit>()

        // Thread-safe metrics registry using a standard Mutex
        val trackerMutex = Mutex()
        val insidePerKey = keys.associateWith { 0 }.toMutableMap()
        val maxConcurrencyPerKey = keys.associateWith { 0 }.toMutableMap()
        val completedPerKey = keys.associateWith { 0 }.toMutableMap()

        var workerIndex = 0
        val jobs = keys.flatMap { (context, id) ->
            val pair = context to id
            List(callersPerKey) {
                val signalIndex = workerIndex++

                scope.launch(Dispatchers.Default) {
                    readySignals[signalIndex].complete(Unit)
                    startGate.await()
                    withLock(context, id) {

                        val currentActive = trackerMutex.withLock {
                            val active = insidePerKey.getValue(pair) + 1
                            insidePerKey[pair] = active
                            maxConcurrencyPerKey[pair] = maxOf(maxConcurrencyPerKey.getValue(pair), active)
                            active
                        }

                        assertEquals(1, currentActive, "Concurrency violation on key $pair!")
                        yield()

                        trackerMutex.withLock {
                            insidePerKey[pair] = insidePerKey.getValue(pair) - 1
                            completedPerKey[pair] = completedPerKey.getValue(pair) + 1
                        }
                    }
                }
            }
        }

        readySignals.awaitAll()
        startGate.complete(Unit)
        jobs.joinAll()

        for (key in keys) {
            assertEquals(1, maxConcurrencyPerKey.getValue(key), "Max concurrency for $key must be 1")
            assertEquals(0, insidePerKey.getValue(key), "Active count for $key must drain to 0")
            assertEquals(callersPerKey, completedPerKey.getValue(key), "All callers must finish")
            assertNull(activeUsersFor(key.first, key.second))
        }
        assertEquals(0, activeKeysCount)
    }

}