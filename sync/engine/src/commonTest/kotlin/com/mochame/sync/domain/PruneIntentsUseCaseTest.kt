@file:OptIn(ExperimentalKermitApi::class)

package com.mochame.sync.domain

import co.touchlab.kermit.ExperimentalKermitApi
import com.mochame.support.MochaPlatformTest
import com.mochame.utils.fixtures.TestHlcFactory
import com.mochame.support.runUnitEnvironment
import com.mochame.sync.api.metadata.SyncStatus
import com.mochame.sync.di.domain.PruneIntentsTestEnv
import com.mochame.sync.di.domain.SyncPruneIntentsTestModule
import com.mochame.sync.internal.fixtures.createTestSyncIntent
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.yield
import org.koin.plugin.module.dsl.modules
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days

// -----------------------------------------------------------
// SUT ENVIRONMENT
// -----------------------------------------------------------
private inline fun runEnv(crossinline block: suspend PruneIntentsTestEnv.(TestScope) -> Unit) =
    runUnitEnvironment<PruneIntentsTestEnv>(
        koinSetup = { modules(SyncPruneIntentsTestModule::class) },
        block = block
    )

internal val TEST_PRUNE_DAYS = 30.days


class PruneIntentsUseCaseTest : MochaPlatformTest() {

    @Test
    fun should_returnZeroAndLeaveStoreEmpty_when_noEligibleEntriesExist() = runEnv {
        fakeStore.reset()

        val totalDeleted = useCase()

        assertEquals(0, totalDeleted, "The use case must return exactly 0 deletions.")
        assertTrue(
            fakeStore.intents.isEmpty(),
            "The intent store must remain completely empty."
        )

        val containsCompletionLog =
            logWriter.logs.any { it.message.contains("Prune Complete") }
        assertFalse(
            containsCompletionLog,
            "Completion summaries must not be logged when zero rows are removed."
        )
    }

    @Test
    fun should_executeExactlyOneLoop_when_entriesAreBelowLimit() =
        runEnv {
            // Arrange
            fakeStore.reset()
            fakeStore.seedIntents(
                createTestSyncIntent(
                    hlc = TestHlcFactory.create(),
                    status = SyncStatus.SUCCESS
                )
            )

            // Act
            val totalDeleted = useCase()

            // Assert
            assertTrue(
                fakeStore.intents.isEmpty(),
                "The intent store must be left empty after pruning completes."
            )

            val completionLog =
                logWriter.logs.find { it.message.contains("Prune Complete") }

            assertTrue(
                completionLog != null,
                "The final execution performance metric log must be present."
            )
            assertTrue(
                completionLog.message.contains("Total: 1"),
                "Log metrics must accurately capture the single deleted entry."
            )
            assertTrue(
                completionLog.message.contains("Chunks: 1"),
                "The log must confirm the loop ran exactly once (Chunks: 1)."
            )
        }

    @Test
    fun should_executeExactlyThreeLoopIterationsAndYieldTwoTimes_when_entriesExceedLimit() =
        runEnv {
            // Arrange
            fakeStore.reset()

            val targetCount = 5
            val chronologicalHlcs = TestHlcFactory.chronologicalSequence(targetCount)
            val eligibleIntents = chronologicalHlcs.map { hlc ->
                createTestSyncIntent(hlc = hlc, status = SyncStatus.SUCCESS)
            }
            fakeStore.seedIntents(eligibleIntents)

            // Act
            val totalDeleted = useCase()

            // Assert
            assertEquals(
                targetCount,
                totalDeleted,
                "The use case must return exactly 5 deletions."
            )
            assertTrue(
                fakeStore.intents.isEmpty(),
                "The intent store must be completely cleared after all chunks prune."
            )

            val completionLog =
                logWriter.logs.find { it.message.contains("Prune Complete") }

            assertTrue(
                completionLog != null,
                "The final execution metrics log must be generated."
            )
            assertTrue(
                completionLog.message.contains("Total: 5"),
                "Log metrics must report a total of 5 entries removed."
            )
            assertTrue(
                completionLog.message.contains("Chunks: 3"),
                "The log must confirm the loop executed exactly 3 times, validating 3 active cooperative yields."
            )
        }

    @Test
    fun should_pruneOnlyEntriesOlderThanCutoff_when_evaluatingTemporalEdgeBoundaries() =
        runEnv {
            // Arrange
            fakeStore.reset()

            // Calculate the exact millisecond cutoff value
            val cutoffThresholdMs = fakeClock.getMillisAgo(TEST_PRUNE_DAYS)

            // Distinct HLC identifiers
            val hlcs = TestHlcFactory.chronologicalSequence(2)
            val olderHlc = hlcs[0]
            val youngerHlc = hlcs[1]

            // Entry positioned exactly 1 millisecond before the boundary line (eligible)
            val olderIntent = createTestSyncIntent(
                hlc = olderHlc,
                createdAt = cutoffThresholdMs - 1L,
                status = SyncStatus.SUCCESS
            )

            // Entry positioned exactly 1 millisecond after the boundary line (ineligible)
            val youngerIntent = createTestSyncIntent(
                hlc = youngerHlc,
                status = SyncStatus.SUCCESS,
                createdAt = cutoffThresholdMs + 1L
            )

            fakeStore.seedIntents(olderIntent, youngerIntent)

            // Act
            val totalDeleted = useCase()

            // Assert
            assertEquals(
                1,
                totalDeleted,
                "The use case must return exactly 1 deletion for the expired entry."
            )

            val remainingIntents = fakeStore.intents
            assertEquals(
                1,
                remainingIntents.size,
                "The store must retain exactly one intent."
            )
            assertEquals(
                youngerHlc,
                remainingIntents.first().hlc,
                "The younger intent (cutoff + 1ms) must be strictly preserved to confirm no off-by-one errors."
            )
        }

    @Test
    fun should_abortExecutionAndThrowCancellationException_when_coroutineContextIsCancelledMidProcess() =
        runEnv { scope ->
            // Arrange
            fakeStore.reset()

            val targetCount = 5
            val chronologicalHlcs = TestHlcFactory.chronologicalSequence(targetCount)

            val eligibleIntents = chronologicalHlcs.map { hlc ->
                createTestSyncIntent(
                    hlc = hlc,
                    status = SyncStatus.SUCCESS,
                    createdAt = 0L
                )
            }
            fakeStore.seedIntents(eligibleIntents)

            // Act: Launch inside an async deferred block to capture its cancellation lifecycle
            val useCaseDeferred = scope.async {
                useCase()
            }

            // Launch a concurrent monitoring coroutine on the same virtual test scheduler.
            // This monitor waits for the first chunk (100 items) to be wiped out, then cancels the parent job.
            scope.launch {
                while (fakeStore.intents.size == 5) {
                    yield() // Cooperatively hand execution back to the use case until the first pass completes
                }
                // The first chunk has processed (size dropped to 3). Trigger mid-execution cancellation.
                useCaseDeferred.cancel()
            }

            assertFailsWith<CancellationException> {
                useCaseDeferred.await()
            }

            assertEquals(
                3,
                fakeStore.intents.size,
                "The orchestrator must immediately stop processing further chunks, leaving exactly 150 items remaining."
            )
        }
}
