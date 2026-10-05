@file:OptIn(ExperimentalKermitApi::class, ExperimentalCoroutinesApi::class)

package com.mochame.node.policies

import co.touchlab.kermit.ExperimentalKermitApi
import com.mochame.node.di.StaggeredDbPolicyTestEnv
import com.mochame.node.di.StaggeredDbPolicyTestModule
import com.mochame.support.MochaPlatformTest
import com.mochame.support.runUnitEnvironment
import com.mochame.sync.api.exceptions.MochaException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import org.koin.plugin.module.dsl.modules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

// -----------------------------------------------------------
// SUT ENVIRONMENT
// -----------------------------------------------------------
private inline fun runEnv(crossinline block: suspend StaggeredDbPolicyTestEnv.(TestScope) -> Unit) =
    runUnitEnvironment<StaggeredDbPolicyTestEnv>(
        koinSetup = { modules(StaggeredDbPolicyTestModule::class) },
        block = block
    )

object TestStaggerConfig {
    const val MAX_ATTEMPTS: Int = 3
    val INITIAL_DELAY: Duration = 1.milliseconds
}


class StaggeredDbRetryPolicyTest : MochaPlatformTest() {

    // -----------------------------------------------------------
    // RETRY AND STAGGER
    // -----------------------------------------------------------
    @Test
    fun should_exhaustRetriesAndThrowTerminalMochaException_when_databaseRemainsBusy() = runEnv {
        var attempts = 0
        val thrownMessage = "SQLITE_BUSY: database is locked"

        val exception = assertFailsWith<MochaException> {
            executor.execute("test.db.exhaustion") {
                attempts++
                throw Exception(thrownMessage)
            }
        }

        assertEquals(TestStaggerConfig.MAX_ATTEMPTS, attempts)
        assertEquals(thrownMessage, exception.cause?.message)
        assertIs<MochaException.Transient.DatabaseBusy>(exception)
    }

    @Test
    fun should_recoverAndReturnPayload_when_transientFailureResolvesBeforeMaxAttempts() = runEnv {
        var attempts = 0
        val expectedPayload = "SUCCESSFUL_ROW_INSERT"

        val result = executor.execute("test.db.recovery") {
            attempts++
            if (attempts < failureBoundary) {
                throw Exception("SQLITE_BUSY: lock contention")
            }
            expectedPayload
        }

        assertEquals(
            failureBoundary,
            attempts,
            "Expected block to succeed on attempt 2 after 1 transient failure"
        )
        assertEquals(expectedPayload, result)
    }

    @Test
    fun should_abortImmediatelyOnFirstAttempt_when_persistentErrorOccurs() = runEnv {
        var attempts = 0

        val exception = assertFailsWith<MochaException> {
            executor.execute("test.db.persistent_fail") {
                attempts++
                throw Exception("SQLITE_FULL: disk full")
            }
        }

        assertEquals(1, attempts, "Non-transient errors must abort without retry cycles")
        assertIs<MochaException.Persistent.DiskFull>(
            exception,
            "SQLITE_FULL error must immediately map and rethrow as DiskFull"
        )
    }

    @Test
    fun should_applyExponentialBackoffWithStagger_when_retrying() = runEnv { scope ->
        val executionTimestamps = mutableListOf<Long>()

        assertFailsWith<MochaException> {
            executor.execute("test.db.backoff_verification") {
                executionTimestamps.add(scope.testScheduler.currentTime)
                throw Exception("SQLITE_LOCKED")
            }
        }

        assertEquals(TestStaggerConfig.MAX_ATTEMPTS, executionTimestamps.size)

        val attempt0Time = executionTimestamps[0]
        val attempt1Time = executionTimestamps[1]
        val attempt2Time = executionTimestamps[2]

        // Initial delay = 1ms, stagger factor in [1.0, 1.5] -> Delta 1 in [1ms, 2ms]
        val firstInterval = attempt1Time - attempt0Time
        assertTrue(
            firstInterval in 1L..2L,
            "First retry backoff must fall within staggered initial bounds (1ms..2ms). Actual: ${firstInterval}ms"
        )

        // Exponential doubling = 2ms, stagger factor in [1.0, 1.5] -> Delta 2 in [2ms, 3ms]
        val secondInterval = attempt2Time - attempt1Time
        assertTrue(
            secondInterval in 2L..3L,
            "Second retry backoff must reflect exponential doubling with stagger (2ms..3ms). Actual: ${secondInterval}ms"
        )

        // Total virtual time elapsed across all attempts must match cumulative staggered delay
        val totalElapsed = scope.testScheduler.currentTime
        assertTrue(
            totalElapsed in 3L..5L,
            "Total cumulative delay must remain within 3ms..5ms window. Actual: ${totalElapsed}ms"
        )
    }

    // -----------------------------------------------------------
    // CONCURRENCY
    // -----------------------------------------------------------
    @Test
    fun should_haltImmediatelyAndNotRetry_when_coroutineIsCancelledDuringBackoffDelay() =
        runEnv { scope ->
            var attempts = 0

            val job = scope.launch {
                executor.execute("test.db.cancellation") {
                    attempts++
                    throw Exception("SQLITE_BUSY: database is locked")
                }
            }

            // Advance scheduler to trigger attempt 1 and enter the backoff delay
            scope.testScheduler.runCurrent()
            assertEquals(1, attempts, "Initial attempt must execute before entering backoff delay")
            assertTrue(job.isActive, "Job must remain active suspended inside backoff delay")

            // Cancel while suspended inside kotlinx.coroutines.delay()
            job.cancelAndJoin()

            // Advance all virtual time to verify no deferred retry tasks run
            scope.testScheduler.advanceUntilIdle()

            assertEquals(
                1,
                attempts,
                "Retry loop must terminate immediately upon cancellation without executing subsequent attempts"
            )
            assertTrue(job.isCancelled, "Parent coroutine must transition to cancelled state")
        }

    @Test
    fun should_resolveContentionWithoutDeadlock_when_multipleCoroutinesCompeteConcurrently() =
        runEnv { scope ->
            var isLocked = false // simulates database lock
            val completedTasks = mutableListOf<Int>()
            val totalContendingTasks = 3

            val jobs = List(totalContendingTasks) { taskId ->
                scope.launch {
                    val result = executor.execute("test.db.contention.tx$taskId") {
                        if (isLocked) {
                            throw Exception("SQLITE_BUSY: table locked by concurrent write")
                        }
                        isLocked = true
                        try {
                            // Hold simulated exclusive write transaction across a suspension boundary
                            delay(1.milliseconds)
                            completedTasks.add(taskId)
                            "SUCCESS_$taskId"
                        } finally {
                            isLocked = false
                        }
                    }
                    assertEquals("SUCCESS_$taskId", result)
                }
            }

            scope.testScheduler.advanceUntilIdle()

            jobs.forEach { job ->
                assertTrue(
                    job.isCompleted && !job.isCancelled,
                    "All contending coroutines must resolve successfully"
                )
            }
            assertEquals(
                totalContendingTasks,
                completedTasks.size,
                "All competing transactions must complete execution"
            )
            assertEquals(
                totalContendingTasks,
                completedTasks.toSet().size,
                "Every contending task must have executed without starvation"
            )
        }

    // -----------------------------------------------------------
    // NON-IDEMPOTENCY DEMONSTRATION
    // -----------------------------------------------------------

    @Test
    fun should_demonstrateMultiExecutionOfSideEffects_as_blockIsNotIdempotent() = runEnv {
        var externalUncommittedCounter = 0
        var attempts = 0

        val exception = assertFailsWith<MochaException> {
            executor.execute("test.db.non_idempotent") {
                attempts++
                // Unprotected side-effect executed prior to transient database failure
                externalUncommittedCounter += 10
                throw Exception("SQLITE_BUSY")
            }
        }

        assertIs<MochaException.Transient.DatabaseBusy>(exception)
        assertEquals(TestStaggerConfig.MAX_ATTEMPTS, attempts)
        assertEquals(
            TestStaggerConfig.MAX_ATTEMPTS * 10,
            externalUncommittedCounter,
            "Non-idempotent operations outside SQLite transaction isolation accumulate side-effects per retry"
        )
    }

    @Test
    fun should_isolateStateMutations_when_blockEncapsulatesAtomicRollback() = runEnv {
        var persistentState = 0
        var attempts = 0

        val result = executor.execute("test.db.atomic_isolation") {
            attempts++
            // Simulated transaction block: local staging rolled back upon failure
            var stagingState = persistentState
            stagingState += 5

            if (attempts < failureBoundary) {
                throw Exception("SQLITE_BUSY: temporary lock")
            }
            persistentState = stagingState
            "COMMITTED"
        }

        assertEquals("COMMITTED", result)
        assertEquals(failureBoundary, attempts)
        assertEquals(
            5,
            persistentState,
            "State should only mutate once upon transaction commit, proving necessity of atomic boundaries"
        )
    }
}

