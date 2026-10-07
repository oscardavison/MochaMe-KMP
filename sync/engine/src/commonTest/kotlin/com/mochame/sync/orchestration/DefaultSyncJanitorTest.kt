@file:OptIn(ExperimentalKermitApi::class)

package com.mochame.sync.orchestration

import androidx.sqlite.SQLiteException
import app.cash.turbine.test
import co.touchlab.kermit.ExperimentalKermitApi
import com.mochame.support.MochaPlatformTest
import com.mochame.support.runUnitEnvironment
import com.mochame.sync.api.boot.BootState
import com.mochame.sync.api.exceptions.MochaException
import com.mochame.sync.api.models.NodeContext
import com.mochame.sync.di.janitor.JanitorTestEnv
import com.mochame.sync.di.janitor.SyncJanitorTestModule
import com.mochame.sync.domain.model.SyncStatus
import com.mochame.sync.internal.fixtures.createTestSyncIntent
import com.mochame.utils.fixtures.TestHlcFactory
import com.mochame.utils.fixtures.TestNodeId
import com.mochame.utils.fixtures.TestPayloads
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.io.Buffer
import kotlinx.io.IOException
import org.koin.plugin.module.dsl.modules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

// -----------------------------------------------------------
// SUT ENVIRONMENT
// -----------------------------------------------------------
private inline fun runEnv(crossinline block: suspend JanitorTestEnv.(TestScope) -> Unit) =
    runUnitEnvironment<JanitorTestEnv>(
        koinSetup = { modules(SyncJanitorTestModule::class) },
        block = block
    )


@ExperimentalCoroutinesApi
class DefaultSyncJanitorTest : MochaPlatformTest() {

    // -----------------------------------------------------------
    // BOOT LIFECYCLE & EXCEPTIONS / STATE GUARDS (HLC/BOOT)
    // -----------------------------------------------------------

    @Test
    fun yay_or_nay_onNodeEstablishmentCall() = runEnv { scope ->
        janitor.startupChecks()

        scope.advanceUntilIdle()

        assertNotNull(nodeManager.getOrEstablishContext())
    }

    @Test
    fun should_cancelJobAndAbortBoot_when_cancellationExceptionThrownDuringStaleIntentRec() =
        runEnv {
            transactor.shouldThrow = CancellationException("User closed app at blob reconciliation")
            bootUpdater.updateState(BootState.Init)
            val intentToQuarantine = createTestSyncIntent(
                status = SyncStatus.SYNCING,
                retryCount = config.retryThreshold - 1
            )
            intentStore.seedIntents(intentToQuarantine)

            val job = janitor.startupChecks()
            job.join()

            assertTrue(job.isCancelled, "Job cancellation")
            assertEquals(BootState.Init, bootUpdater.bootState.value)
            val finalIntentState = intentStore.intents.last()
            assertEquals(SyncStatus.SYNCING, finalIntentState.syncStatus)
            assertEquals(config.retryThreshold - 1, finalIntentState.retryCount)
        }

    @Test
    fun should_transitionBootStateAndHydrateHlcFactory_when_executingAgainstValidStartupState() =
        runEnv { scope ->
            // Given
            bootUpdater.updateState(BootState.Init)
            nodeManager.forcedNextNodeId = TestNodeId.A

            // When
            janitor.startupChecks()
            scope.advanceUntilIdle()

            // Then
            assertEquals(
                listOf(BootState.Idle, BootState.Init),
                bootUpdater.history
            )
            assertEquals(
                1,
                hlcFactory.hydrateCallCount,
                "HLC factory must be hydrated exactly once during startup."
            )
            assertEquals(
                TestNodeId.A,
                hlcFactory.lastHydratedNodeId,
                "HLC factory must be hydrated with the current node ID."
            )
        }

    @Test
    fun should_abortStartupChecksAndSkipHydration_when_bootStateIsIdle() =
        runEnv { scope ->
            // Given
            bootUpdater.updateState(BootState.Idle)

            // When
            janitor.startupChecks()
            scope.advanceUntilIdle()

            // Then
            assertEquals(
                0,
                hlcFactory.hydrateCallCount,
                "HLC Factory must not be hydrated if boot checks short-circuit."
            )

            val skipLog = writer.logs.find { it.message.contains("Skipping startup") }
            assertNotNull(
                skipLog,
                "Janitor must log skipping startup when in an invalid boot state."
            )
        }

    @Test
    fun should_abortStartupChecks_when_bootStatIsInCriticalFailure() =
        runEnv { scope ->
            val failure = MochaException.Persistent.ClockSkew(5.seconds)
            bootUpdater.updateState(BootState.LockOut("Failed", failure))

            // When
            janitor.startupChecks()
            scope.advanceUntilIdle()

            // Then
            assertEquals(0, hlcFactory.hydrateCallCount)
        }

    @Test
    fun should_enterCriticalBootFailure_when_lastHlcIsFromTheFuture() = runEnv {
        // Arrange
        bootUpdater.updateState(BootState.Init)
        // Seed a Future HLC (2040-01-01...)
        val futureTs = fakeClock.now().plus(1.hours)
        val futureHlc = TestHlcFactory.create(ts = futureTs)

        nodeManager.updateHlcFloor(futureHlc)

        // Act
        janitor.startupChecks()

        // Assert
        bootUpdater.bootState.test {
            // Skip Initializing
            assertTrue(awaitItem() is BootState.Init)

            // Capture the Critical Failure
            val finalState = awaitItem()
            assertTrue(finalState is BootState.LockOut)

            assertTrue(finalState.cause is MochaException.Persistent.ClockSkew)

            // Verify the logs
            val log = writer.logs.find { it.message.contains("Clock Skew") }
            assertNotNull(log, "The clock skew log should have been recorded!")

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun should_reportTransientFailure_when_bootHydrationTimesOut() =
        runEnv { scope ->
            // Arrange - simulate the manager being locked
            bootUpdater.updateState(BootState.Init)
            nodeManager.simulatedDelay = 6.seconds

            // Act
            janitor.startupChecks()
            // janitor stalls on hydration, locked from fetching device context
            scope.runCurrent()
            assertEquals(BootState.Init, bootUpdater.bootState.value)
            scope.advanceTimeBy(5001.milliseconds)

            // Assert
            val finalState = bootUpdater.bootState.value
            assertTrue(
                finalState is BootState.TransientFailure,
                "Janitor should have failed on timeout. Got $finalState.."
            )
            assertTrue(finalState.cause is MochaException.Transient.Contention)
        }

    @Test
    fun should_setTransientBootFailure_when_janitorsOwnLockIsBusy() =
        runEnv { scope ->
            // Given
            janitorMutex.lock()

            // When
            janitor.startupChecks()

            // Then
            bootUpdater.bootState.test {
                assertEquals(BootState.Idle, awaitItem())
                expectNoEvents()

                scope.advanceTimeBy(config.startupTimeout - 1.seconds)
                expectNoEvents() // -- should not have hit internal timeout

                scope.advanceTimeBy(1.seconds)
                val failureState = awaitItem()

                assertTrue(failureState is BootState.TransientFailure, "Boot state")
                assertTrue(failureState.cause is MochaException.Transient.Contention, "Cause")
            }

            janitorMutex.unlock()
        }

    @Test
    fun should_catchAndTransitionToCriticalFailure_when_executionPolicyThrowsDatabaseErrorWithinBootTimeAllocation() =
        runEnv { scope ->
            // Given
            bootUpdater.updateState(BootState.Init)
            val dbLockException = IOException("disk full")
            executor.failConsecutively(count = 1, exception = dbLockException)

            // When
            janitor.startupChecks()
            scope.advanceUntilIdle()

            // Then
            assertEquals(1, executor.executionCount)
            assertTrue(executor.executionHistory.contains("[Startup Checks]"))

            val history = bootUpdater.history
            assertEquals(3, history.size)
            assertTrue(history[2] is BootState.LockOut)

            val errorLog =
                writer.logs.find { it.message.contains("Persistent boot failure") }
            assertNotNull(
                errorLog,
                "Janitor must log a persistent boot failure error."
            )
        }


    @Test
    fun should_pipeNodeContextToHlcFactory_when_hydrating() = runEnv { scope ->
        // Given
        bootUpdater.updateState(BootState.Init)
        val nodeId = TestNodeId.A

        val seededHlc = TestHlcFactory.create(
            ts = 1740787200000L,
            count = 2,
            nodeId = nodeId
        )
        nodeManager.seededContext = NodeContext(
            nodeId = nodeId,
            appVersion = 1,
            lastServerResponseTime = null,
            maxHlc = seededHlc,
            lastInboundWatermark = null,
        )

        // When
        janitor.startupChecks()
        scope.advanceUntilIdle()

        // Then
        assertEquals(1, hlcFactory.hydrateCallCount)
        assertEquals(seededHlc, hlcFactory.lastHydratedHlc)
        assertEquals(nodeId, hlcFactory.lastHydratedNodeId)
    }

    @Test
    fun should_transitionToTransientFailure_when_startupThrowsTransientMochaException() =
        runEnv { scope ->
            // Given
            bootUpdater.updateState(BootState.Init)
            intentStore.failWith = MochaException.Transient.DatabaseBusy()

            // When
            janitor.startupChecks()
            scope.advanceUntilIdle()

            // Then
            val currentState = bootUpdater.bootState.value
            assertTrue(
                currentState is BootState.TransientFailure,
                "Transient MochaException must route boot state to TransientFailure, but got: $currentState"
            )
        }

    @Test
    fun should_transitionToPersistentFailure_when_startupThrowsPersistentMochaException() =
        runEnv { scope ->
            // Given
            bootUpdater.updateState(BootState.Init)
            intentStore.failWith = MochaException.Persistent.DiskFull()

            // When
            janitor.startupChecks()
            scope.advanceUntilIdle()

            // Then
            val currentState = bootUpdater.bootState.value
            assertTrue(
                currentState is BootState.LockOut,
                "Persistent MochaException must route boot state to CriticalFailure, but got: $currentState"
            )
        }

    @Test
    fun should_wrapStartupChecks_inExecutionPolicyTag() = runEnv { scope ->
        // When
        janitor.startupChecks()
        scope.advanceUntilIdle()

        // Then
        val executedTags = executor.executionHistory
        assertTrue(
            executedTags.contains("[Startup Checks]"),
            "SyncJanitor startup logic must pass through executor with tag '[Startup Checks]'."
        )
    }

    // -----------------------------------------------------------
    // METADATA MAINTENANCE (SYNCINTENT)
    // -----------------------------------------------------------

    @Test
    fun should_resetToValidStatus_when_staleIntentsExistOnStartup() =
        runEnv { scope ->
            bootUpdater.updateState(BootState.Init)
            val baseTime = fakeClock.now()
            val staleTime = baseTime.minus(config.staleThreshold).toEpochMilliseconds()

            // Given: Intents stuck in SYNCING with active syncIds (simulating process crash)
            val hlc1 = TestHlcFactory.create(ts = 100L, count = 0)
            val hlc2 = TestHlcFactory.create(ts = 200L, count = 0)

            intentStore.seedIntents(
                createTestSyncIntent(
                    hlc = hlc1,
                    status = SyncStatus.SYNCING,
                    batchId = 100L,
                    leasedAt = staleTime,
                    retryCount = 0
                ),
                createTestSyncIntent(
                    hlc = hlc2,
                    status = SyncStatus.SYNCING,
                    batchId = 101L,
                    leasedAt = staleTime,
                    retryCount = 0
                )
            )

            // When
            janitor.startupChecks()
            scope.advanceUntilIdle()

            // Then
            val persistedIntents = intentStore.intents
            assertEquals(2, persistedIntents.size)
            assertTrue(
                persistedIntents.all { it.batchId == null && it.syncStatus == SyncStatus.PENDING },
                "State should be reconciled."
            )

            // Verify audit log for lock cleanup
            val cleanupLog = writer.logs.find { it.message.contains("2 stale intent") }
            assertNotNull(cleanupLog)
        }

    @Test
    fun should_notLogIntentCleanup_when_noStaleIntentsExistOnStartup() = runEnv { scope ->
        // Given
        val cleanIntent = createTestSyncIntent(
            hlc = TestHlcFactory.create(),
            status = SyncStatus.PENDING
        )
        intentStore.seedIntents(cleanIntent)

        // When
        janitor.startupChecks()
        scope.advanceUntilIdle()

        // Then
        val cleanupLog = writer.logs.find { it.message.contains("stale mutation locks") }
        assertEquals(
            null,
            cleanupLog,
            "Janitor must skip the lock warning log when zero locks are cleared."
        )
    }

    // -----------------------------------------------------------
    // BLOB RECOVERY
    // -----------------------------------------------------------

    @Test
    fun should_commitStrandedBlob_when_matchingMetadataExistsInIntentStore() =
        runEnv { scope ->
            // Given
            bootUpdater.updateState(BootState.Init)
            val blobId = blobStore.stage(TestPayloads.defaultSource())

            intentStore.seedIntents(
                createTestSyncIntent(
                    hlc = TestHlcFactory.create(),
                    status = SyncStatus.PENDING,
                    overflowBlobId = blobId
                )
            )

            // When
            janitor.startupChecks()
            scope.advanceUntilIdle()

            // Then
            assertTrue(
                blobStore.existsInCommitted(blobId),
                "Stranded blob with matching metadata must be committed."
            )
            assertFalse(
                blobStore.existsInPending(blobId),
                "Stranded blob with matching metadata must have been atomically moved out of pending."
            )

            val recoveryLog =
                writer.logs.find { it.message.contains("Recovering stranded blob: $blobId") }
            assertNotNull(recoveryLog, "Must log recovery when committing stranded blobs.")
        }

    @Test
    fun should_abortOrphanedBlob_when_noMatchingMetadataExistsInIntentStore() =
        runEnv { scope ->
            // Given
            bootUpdater.updateState(BootState.Init)
            val blobId = blobStore.stage(TestPayloads.defaultSource())

            // When
            janitor.startupChecks()
            scope.advanceUntilIdle()

            // Then
            assertFalse(
                blobStore.existsInPending(blobId),
                "Orphaned blob with no persisted metadata must not exist in committed chamber."
            )

            val purgeLog = writer.logs.find {
                it.message.contains("Found orphaned pending blob $blobId")
            }
            assertNotNull(
                purgeLog,
                "Janitor must log purging when aborting orphaned blobs."
            )
        }

    @Test
    fun should_continueReconciliationAndComplete_when_individualBlobReconciliationThrows() =
        runEnv { scope ->
            // Given: Stage two distinct blobs
            bootUpdater.updateState(BootState.Init)

            val payloadA = byteArrayOf(0x01, 0x02)
            val payloadB = byteArrayOf(0x03, 0x04)
            val blobA = blobStore.stage(Buffer().apply { write(payloadA) })
            val blobB = blobStore.stage(Buffer().apply { write(payloadB) })
            // Seed intent metadata only for blobB
            intentStore.seedIntents(
                createTestSyncIntent(
                    hlc = TestHlcFactory.create(),
                    status = SyncStatus.PENDING,
                    overflowBlobId = blobB
                )
            )
            intentStore.failOnBlobCheck(blobA)

            // When
            janitor.startupChecks()
            scope.advanceUntilIdle()

            val blobAFailureLog =
                writer.logs.find { it.message.contains("Failed to reconcile individual blob: $blobA") }
            assertNotNull(
                blobAFailureLog,
                "Janitor must catch and log exception for blobA instead of crashing."
            )

            assertTrue(blobStore.existsInCommitted(blobB))
            assertFalse(blobStore.existsInCommitted(blobA))
            assertTrue(blobStore.existsInPending(blobA))
        }

    // -----------------------------------------------------------
    // RUNTIME INTENT MAINTENANCE
    // -----------------------------------------------------------

    @Test
    fun should_executeMaintenancePeriodically_onConfiguredInterval() = runEnv { scope ->
        // Given
        val maintenanceJob = janitor.startRuntimeMaintenance()

        // When: Trigger first cycle
        scope.advanceTimeBy(config.maintenanceInterval)
        scope.runCurrent()

        val firstCycleLogs = writer.logs.count { it.message.contains("cycle finished") }
        assertEquals(
            1,
            firstCycleLogs,
            "Maintenance cycle execution interval not as expected."
        )

        // When: Trigger second cycle
        scope.advanceTimeBy(config.maintenanceInterval)
        scope.runCurrent()

        val secondCycleLogs =
            writer.logs.count { it.message.contains("cycle finished") }
        assertEquals(
            firstCycleLogs + 1,
            secondCycleLogs,
            "Maintenance cycle must execute again on the next interval."
        )

        maintenanceJob.cancel()
    }

    @Test
    fun should_resetLeaseAndIncrementRetryCount_when_leaseIsStaleAndBelowThreshold() =
        runEnv { scope ->
            // Given: Seed intent with status Syncing and an expired lease
            val now = fakeClock.now().toEpochMilliseconds()
            val timeoutMs = config.staleThreshold.inWholeMilliseconds
            val staleTimestamp = now - (timeoutMs + 1000L)
            val initialRetryCount = config.retryThreshold - 3

            val intent = createTestSyncIntent(
                hlc = TestHlcFactory.create(),
                status = SyncStatus.SYNCING,
                leasedAt = staleTimestamp,
                retryCount = initialRetryCount,
                batchId = 200L
            )
            intentStore.seedIntents(intent)

            // When
            val maintenanceJob = janitor.startRuntimeMaintenance()
            scope.advanceTimeBy(config.maintenanceInterval)
            scope.runCurrent()

            // Then
            val updatedIntent = intentStore.intents.firstOrNull()
            assertNotNull(updatedIntent)
            assertEquals(
                SyncStatus.PENDING,
                updatedIntent.syncStatus,
                "Stale lease must reset to PENDING state."
            )
            assertEquals(
                initialRetryCount + 1,
                updatedIntent.retryCount,
                "Retry count must increment by 1."
            )
            assertNull(
                updatedIntent.leasedAt,
                "leasedAt timestamp must be cleared on reset."
            )
            assertNull(
                updatedIntent.batchId,
                "syncId should be reset to null on lease reset."
            )

            maintenanceJob.cancel()
        }

    @Test
    fun should_escalateToQuarantine_when_staleLeaseReachesMaxRetries() = runEnv { scope ->
        // Given: Seed an intent with status syncing, and a retryCount requiring quarantine
        val now = fakeClock.now().toEpochMilliseconds()
        val timeoutMs = config.staleThreshold.inWholeMilliseconds
        val staleTimestamp = now - (timeoutMs + 1000L)
        val initialRetryCount = config.retryThreshold - 1

        val intent = createTestSyncIntent(
            hlc = TestHlcFactory.create(),
            status = SyncStatus.SYNCING,
            batchId = 300L,
            leasedAt = staleTimestamp,
            retryCount = initialRetryCount
        )
        intentStore.seedIntents(intent)

        // When
        val maintenanceJob = janitor.startRuntimeMaintenance()
        scope.advanceTimeBy(config.maintenanceInterval)
        scope.runCurrent()

        // Then
        val updatedIntent = intentStore.intents.firstOrNull()
        assertNotNull(updatedIntent)
        assertEquals(
            SyncStatus.QUARANTINED,
            updatedIntent.syncStatus,
            "Stale lease reaching max retries must escalate to quarantined."
        )
        assertEquals(config.retryThreshold, updatedIntent.retryCount)

        val quarantineLog = writer.logs.find { it.message.startsWith("Quarantined 1 stale") }
        assertNotNull(quarantineLog, "Janitor must log quarantine escalation events.")

        maintenanceJob.cancel()
    }

    @Test
    fun should_ignoreActiveLeases_when_withinTimeoutWindow() = runEnv { scope ->
        // Given: Seed an intent within lease window
        val leaseStamp = fakeClock.now().toEpochMilliseconds()

        val intent = createTestSyncIntent(
            hlc = TestHlcFactory.create(),
            status = SyncStatus.SYNCING,
            batchId = 300L,
            leasedAt = leaseStamp,
            retryCount = 1
        )
        intentStore.seedIntents(intent)

        // When
        val maintenanceJob = janitor.startRuntimeMaintenance()
        scope.advanceTimeBy(config.maintenanceInterval)
        scope.runCurrent()

        // Then
        val updatedIntent = intentStore.intents.firstOrNull()
        assertNotNull(updatedIntent)
        assertEquals(
            SyncStatus.SYNCING,
            updatedIntent.syncStatus,
            "Active lease must remain at a syncing status."
        )
        assertEquals(
            1,
            updatedIntent.retryCount,
            "Retry count must not change for active leases."
        )
        assertEquals(
            leaseStamp,
            updatedIntent.leasedAt,
            "leasedAt must remain intact for active leases."
        )

        maintenanceJob.cancel()
    }

    @Test
    fun should_processVariousStateValidationsCorrectly_inSingleMaintenancePass() =
        runEnv { scope ->
            val now = fakeClock.now().toEpochMilliseconds()
            val timeoutMs = config.staleThreshold.inWholeMilliseconds
            val staleTimestamp = now - (timeoutMs + 1000L)
            val activeTimestamp = now - 1000L
            val hlcs = TestHlcFactory.concurrentSequence(3)

            val quarantineIntent = createTestSyncIntent(
                hlc = hlcs[0],
                status = SyncStatus.SYNCING,
                batchId = 400L,
                leasedAt = staleTimestamp,
                retryCount = config.retryThreshold - 1
            )
            val resetIntent = createTestSyncIntent(
                hlc = hlcs[1],
                status = SyncStatus.SYNCING,
                batchId = 401L,
                leasedAt = staleTimestamp,
                retryCount = 0
            )
            val activeIntent = createTestSyncIntent(
                hlc = hlcs[2],
                status = SyncStatus.SYNCING,
                batchId = 402L,
                leasedAt = activeTimestamp,
                retryCount = 1
            )
            intentStore.seedIntents(quarantineIntent, resetIntent, activeIntent)

            // When
            val maintenanceJob = janitor.startRuntimeMaintenance()
            scope.advanceTimeBy(config.maintenanceInterval)
            scope.runCurrent()

            // Then 1
            val updatedQuarantine = intentStore.intents.find { it.hlc == hlcs[0] }
            assertNotNull(updatedQuarantine, "Quarantined intent must exist in store.")
            assertEquals(
                SyncStatus.QUARANTINED,
                updatedQuarantine.syncStatus,
                "Stale intent reaching threshold must transition to QUARANTINED."
            )
            assertEquals(config.retryThreshold, updatedQuarantine.retryCount)

            // Then 2
            val updatedReset = intentStore.intents.find { it.hlc == hlcs[1] }
            assertNotNull(updatedReset, "Reset intent must exist in store.")
            assertEquals(
                SyncStatus.PENDING,
                updatedReset.syncStatus,
                "Stale intent below threshold must reset to PENDING state."
            )
            assertEquals(
                1,
                updatedReset.retryCount,
                "Retry count must increment by 1."
            )
            assertNull(
                updatedReset.leasedAt,
                "LeasedAt timestamp must be cleared on reset."
            )

            // Then 3
            val updatedActive = intentStore.intents.find { it.hlc == hlcs[2] }
            assertNotNull(updatedActive, "Active intent must exist in store.")
            assertEquals(
                SyncStatus.SYNCING,
                updatedActive.syncStatus,
                "Active lease must remain in SYNCING state."
            )
            assertEquals(
                1,
                updatedActive.retryCount,
                "Active lease retry count must not change."
            )
            assertEquals(
                activeTimestamp,
                updatedActive.leasedAt,
                "Active lease timestamp must remain intact."
            )

            maintenanceJob.cancel()
        }


    // -----------------------------------------------------------
    // RUNTIME INTENT MAINTENANCE
    // -----------------------------------------------------------

    @Test
    fun should_triggerPruning_onMaintenanceTick() = runEnv { scope ->
        // Given
        val completedIntent = createTestSyncIntent(
            hlc = TestHlcFactory.create(),
            status = SyncStatus.SUCCESS
        )
        intentStore.seedIntents(completedIntent)

        // When
        val maintenanceJob = janitor.startRuntimeMaintenance()
        scope.advanceTimeBy(config.maintenanceInterval)
        scope.runCurrent()

        // Then
        val pruneLog = writer.logs.any { it.message.contains("Prune Complete") }
        assertNotNull(
            pruneLog,
            "Pruning should be visibly logged during Janitors runtime maintenance."
        )
        val remaining = intentStore.intents.find { it.batchId == completedIntent.batchId }
        assertNull(remaining, "Completed intents must be pruned during maintenance tick.")

        maintenanceJob.cancel()
    }

    @Test
    fun should_continueMaintenanceLoop_when_pruningThrowsException() = runEnv { scope ->
        // Given
        intentStore.failWith = SQLiteException("database is locked")
        val prunableIntent = createTestSyncIntent(
            hlc = TestHlcFactory.create(),
            status = SyncStatus.SUCCESS
        )
        intentStore.seedIntents(prunableIntent)

        // When 1: Pruning fails due to exception
        val maintenanceJob = janitor.startRuntimeMaintenance()
        scope.advanceTimeBy(config.maintenanceInterval)
        scope.runCurrent()

        val cycle1ErrorLog =
            writer.logs.find { it.message.contains("Intent reconciliation encountered error") }
        assertNotNull(cycle1ErrorLog, "Janitor must catch and log pruning exception on Cycle 1.")

        // When 2: Runtime maintenance restarts
        intentStore.failWith = null
        scope.advanceTimeBy(config.maintenanceInterval)
        scope.runCurrent()

        // Then
        val completionLogs =
            writer.logs.count { it.message.contains("Runtime maintenance cycle finished") }
        val pruneLogs = writer.logs.any { it.message.contains("Prune Complete | Total: 1") }
        assertEquals(
            completionLogs,
            2,
            "Maintenance loop must remain active and execute Cycle 2 despite Cycle 1 failure."
        )
        assertNotNull(
            pruneLogs,
            "Janitor runtime maintenance should handle error and delegate single prune."
        )

        maintenanceJob.cancel()
    }

}