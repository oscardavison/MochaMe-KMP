package com.mochame.sync.infrastructure

import app.cash.turbine.test
import com.mochame.support.MochaPlatformTest
import com.mochame.support.runUnitEnvironment
import com.mochame.sync.di.fixtures.SyncInternalFixturesModule
import com.mochame.sync.internal.fixtures.infrastructure.SpySyncWorkerHook
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.koin.plugin.module.dsl.modules
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

private inline fun runEnv(crossinline block: suspend SpySyncWorkerHook.(TestScope) -> Unit) =
    runUnitEnvironment<SpySyncWorkerHook>(
        koinSetup = { modules(SyncInternalFixturesModule::class) },
        block = block
    )


@ExperimentalCoroutinesApi
class SyncWorkerHookTest : MochaPlatformTest() {

    // -----------------------------------------------------------
    // LIFECYCLE & SUBSCRIPTION
    // -----------------------------------------------------------
    @Test
    fun should_maintainLifecycle_and_counterParityInFake() = runEnv {
        assertEquals(0, invalidationCount)

        // Drop when unsubscribed
        invalidate()
        assertEquals(1, invalidationCount)

        signals.test {
            expectNoEvents()

            invalidate()
            assertEquals(Unit, awaitItem())
            assertEquals(2, invalidationCount)

            cancelAndIgnoreRemainingEvents()
        }

        reset()
        assertEquals(0, invalidationCount)

        // Verify flow remains healthy after reset
        signals.test {
            invalidate()
            assertEquals(Unit, awaitItem())
            assertEquals(1, invalidationCount)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun should_dropInvalidation_when_noActiveSubscribersExist() = runEnv {
        invalidate()

        signals.test {
            expectNoEvents()

            invalidate()
            assertEquals(Unit, awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun should_deliverSignalsToAllActiveCollectors() = runEnv { scope ->
        val receivedById = mutableListOf<Int>()

        repeat(3) { id ->
            scope.backgroundScope.launch {
                signals.collect { receivedById.add(id) }
            }
        }

        scope.runCurrent()
        invalidate()
        scope.runCurrent()

        assertEquals(listOf(0, 1, 2), receivedById)
    }

    @Test
    fun should_deliverSignalOutputToAllActiveCollectors() = runEnv { scope ->
        val routine1 = scope.async(start = CoroutineStart.UNDISPATCHED) { signals.first() }
        val routine2 = scope.async(start = CoroutineStart.UNDISPATCHED) { signals.first() }
        val routine3 = scope.async(start = CoroutineStart.UNDISPATCHED) { signals.first() }

        invalidate()

        assertEquals(Unit, routine1.await())
        assertEquals(Unit, routine2.await())
        assertEquals(Unit, routine3.await())
    }

    @Test
    fun should_notLeakStaleSignals_to_subsequentCollectorAfterUnsubscription() = runEnv {
        // Collector 1 lifecycle
        signals.test {
            invalidate()
            assertEquals(Unit, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        // 0 active collectors
        invalidate()

        // Collector 2 lifecycle
        signals.test {
            expectNoEvents()

            invalidate()
            assertEquals(Unit, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }


    @Test
    fun should_startFreshCycle_when_invalidationArrivesAfterBecomingIdle() = runEnv { scope ->
        var executionCount = 0

        scope.backgroundScope.launch {
            signals.collect {
                delay(100.milliseconds)
                executionCount++
            }
        }
        scope.runCurrent()

        // Cycle 1: Dispatched & completed
        invalidate()
        scope.advanceTimeBy(100.milliseconds)
        scope.runCurrent()
        assertEquals(1, executionCount)

        // Settles to idle
        scope.advanceTimeBy(500.milliseconds)
        scope.runCurrent()

        // Cycle 2: Arrives while system is idle
        invalidate()
        scope.advanceTimeBy(100.milliseconds)
        scope.runCurrent()
        assertEquals(2, executionCount)
    }

    // -----------------------------------------------------------
    // EMISSION CONFLATION
    // -----------------------------------------------------------
    @Test
    fun should_coalesceRapidBurst_during_inFlightWorkIntoExactlyOneFollowUpCycle() =
        runEnv { scope ->
            var executionCount = 0
            var isProcessing = false

            // Simulates the consumer worker loop
            scope.backgroundScope.launch {
                signals.collect {
                    isProcessing = true
                    delay(100.milliseconds) // Simulates queue drain duration
                    executionCount++
                    isProcessing = false
                }
            }
            scope.runCurrent()

            // 1. Initial invalidation -> Enters Cycle 1
            invalidate()
            scope.runCurrent()
            assertTrue(isProcessing)
            assertEquals(0, executionCount)

            // 2. Advance time halfway into Cycle 1 execution (t = 50ms)
            scope.advanceTimeBy(50.milliseconds)
            assertTrue(isProcessing)

            // 3. Emit burst of 50 rapid invalidations mid-flight
            repeat(50) {
                invalidate() //
            }

            // 4. Complete Cycle 1 (t = 100ms)
            scope.advanceTimeBy(50.milliseconds)
            scope.runCurrent()
            assertEquals(1, executionCount)
            assertTrue(isProcessing) // Immediately rolled into the single follow-up Cycle 2

            // 5. Complete Cycle 2 (t = 200ms)
            scope.advanceTimeBy(100.milliseconds)
            scope.runCurrent()
            assertEquals(2, executionCount)
            assertFalse(isProcessing)

            // 6. Advance virtual time further to prove remaining 49 burst signals were discarded
            scope.advanceTimeBy(500.milliseconds)
            scope.runCurrent()
            assertEquals(2, executionCount)
            assertFalse(isProcessing)
        }

    // -----------------------------------------------------------
    // COLLECTOR CANCELLATION BEHAVIOUR
    // -----------------------------------------------------------
    @Test
    fun should_cancelCollection_when_jobThrowsNonCancellationException() =
        runEnv { scope ->
            throwOnNextSignal(CancellationException("Transient SQLite Disk I/O Failure"))

            val job = scope.backgroundScope.launch {
                signals.collect {
                    try {
                        // Consumer execution logic
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Throwable) {
                        // Does not handle CancellationException
                    }
                }
            }
            scope.runCurrent()

            invalidate() // triggers collect
            scope.runCurrent()

            assertEquals(1, totalCollects)
            assertTrue(job.isCancelled)
            assertFalse(job.isActive)

            invalidate()
            scope.runCurrent()
            assertEquals(1, totalCollects)
        }

    @Test
    fun should_cleanlyUnsubscribeAndIgnoreInvalidations_when_collectorJobCancelledExternally() =
        runEnv { scope ->
            val job = scope.backgroundScope.launch {
                signals.collect {}
            }
            scope.runCurrent()

            // 1. Initial valid cycle
            invalidate()
            scope.runCurrent()
            assertEquals(1, totalCollects)

            // 2. External cancellation of host scope / job
            job.cancel()
            scope.runCurrent()
            assertTrue(job.isCancelled)

            // 3. Subsequent invalidations have 0 subscribers and are dropped immediately (replay = 0)
            invalidate()
            scope.runCurrent()
            assertEquals(1, totalCollects)
        }

}
