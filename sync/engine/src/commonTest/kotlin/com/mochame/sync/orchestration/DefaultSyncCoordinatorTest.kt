@file:OptIn(InternalTestApi::class)

package com.mochame.sync.orchestration

import com.mochame.annotations.AppBackgroundScope
import com.mochame.annotations.InternalTestApi
import com.mochame.annotations.IoContext
import com.mochame.support.MochaPlatformTest
import com.mochame.support.awaitCondition
import com.mochame.support.runUnitEnvironment
import com.mochame.sync.api.boot.BootState
import com.mochame.sync.api.exceptions.MochaException
import com.mochame.sync.api.metadata.FeatureContext
import com.mochame.sync.api.metadata.MutationOp
import com.mochame.sync.api.network.SendResult
import com.mochame.sync.di.coordinator.CoordinatorTestModule
import com.mochame.sync.di.coordinator.SyncCoordinatorTestEnv
import com.mochame.sync.domain.model.SyncStatus
import com.mochame.sync.internal.fixtures.createTestSyncIntent
import com.mochame.sync.internal.fixtures.infrastructure.ReceivedIntent
import com.mochame.sync.utils.deriveContext
import com.mochame.utils.fixtures.TestHlcFactory
import com.mochame.utils.fixtures.TestNodeId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.io.IOException
import org.koin.core.KoinApplication
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.plugin.module.dsl.modules
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private inline fun runEnv(
    bindTestScope: Boolean = true,
    crossinline overrideSetup: KoinApplication.() -> Unit = {},
    crossinline block: suspend SyncCoordinatorTestEnv.(TestScope) -> Unit
) = runUnitEnvironment<SyncCoordinatorTestEnv>(
    bindTestScope = bindTestScope,
    koinSetup = {
        modules(CoordinatorTestModule::class)
        overrideSetup()
    },
    block = block
)

@ExperimentalCoroutinesApi
class DefaultSyncCoordinatorTest : MochaPlatformTest() {

    // -------------------------------------------------------------------------
    // Initialization & Production Receiver Validation
    // -------------------------------------------------------------------------

//  This does work as intended I just cant capture the instance creation error without it breaking my build
//    @Test
//    fun should_throwInternalException_when_productionSyncReceiversAreMissingOnInit() = runEnv(
//        overrideSetup = {
//            modules(
//                module {
//                    single<SyncReceiverRegistry> { SyncReceiverRegistry() }
//                }
//            )
//        }
//    ) {
//        // Given: SyncReceiverRegistry provided without production sync receivers
//
//        // When / Then
//        assertFailsWith<MochaException.Persistent.Internal> {
//            coordinator
//        }
//    }

    @Test
    fun should_initializeSuccessfully_when_allProductionSyncReceiversAreRegistered() = runEnv {
        // Given: CoordinatorTestModule provides SyncReceiverRegistry with all production receivers

        // When / Then
        assertNotNull(coordinator)
    }

    // -------------------------------------------------------------------------
    // Boot Readiness
    // -------------------------------------------------------------------------

    @Test
    fun should_abortInboundProcessing_when_bootReadinessFails() = runEnv {
        bootManager.updateState(
            BootState.LockOut(
                "Critical boot failure",
                IllegalStateException("Node boot corrupted")
            )
        )

        coordinator.handleInboundBytes(0L, ByteArray(0))

        assertEquals(0, payloadCodec.decodeCallCount)
        assertEquals(0, stubA.invocationCount)
        assertEquals(0, stubB.invocationCount)
        assertEquals(0, nodeManager.updatedHlcFloors.size)
    }

    @Test
    fun should_abortInboundProcessingAndKeepScopeAlive_when_payloadDecodingFails() = runEnv {
        bootManager.updateState(BootState.Ready)
        payloadCodec.decodeError = IllegalStateException("Malformed protobuf payload")

        coordinator.handleInboundBytes(0L, ByteArray(0))

        assertEquals(0, stubA.invocationCount)
        assertEquals(0, stubB.invocationCount)
        assertEquals(0, nodeManager.updatedHlcFloors.size)
    }

    @Test
    fun should_earlyExitCleanly_when_decodedBatchIsEmpty() = runEnv {
        bootManager.updateState(BootState.Ready)
        coordinator.handleInboundBytes(0L, ByteArray(0))

        assertEquals(1, payloadCodec.decodeCallCount)
        assertEquals(0, stubA.invocationCount)
        assertEquals(0, stubB.invocationCount)
        assertEquals(0, nodeManager.updatedHlcFloors.size)
    }

    @Test
    fun should_abortOutboundPipeline_when_bootReadinessFails() = runEnv { scope ->
        bootManager.updateState(
            BootState.LockOut(
                "Critical boot failure",
                IllegalStateException("Node boot corrupted")
            )
        )
        val job = coordinator.startOutboundListener()
        scope.runCurrent()

        assertFalse(job.isActive)
        workerHook.invalidate()
        scope.runCurrent()

        assertEquals(0, payloadCodec.encodeCallCount)
        assertEquals(0, intentStore.claimedBatchCallCount)
    }

    // -------------------------------------------------------------------------
    // Receiver Routing
    // -------------------------------------------------------------------------

    @Test
    fun should_routeIntentsToCorrectReceivers_and_deriveExactDecodeContext() = runEnv {
        bootManager.updateState(BootState.Ready)

        val hlcA = TestHlcFactory.create(ts = 1000L, count = 0)
        val hlcB = TestHlcFactory.create(ts = 1000L, count = 1)
        val payloadA = byteArrayOf(0x10, 0x20)
        val payloadB = byteArrayOf(0x30, 0x40)

        val intentA = createTestSyncIntent(
            hlc = hlcA,
            candidateKey = 101L,
            featureContext = FeatureContext.TEST_STUB_A,
            payload = payloadA,
            op = MutationOp.UPSERT,
            featureSchemaVersion = 2,
            changedMask = 0b101L
        )
        val intentB = createTestSyncIntent(
            hlc = hlcB,
            candidateKey = 202L,
            featureContext = FeatureContext.TEST_STUB_B,
            payload = payloadB,
            op = MutationOp.DELETE,
            featureSchemaVersion = 1,
            changedMask = 0b010L
        )
        val contextA = intentA.deriveContext()
        val contextB = intentB.deriveContext()

        payloadCodec.nextDecodeResult = listOf(intentA, intentB)
        coordinator.handleInboundBytes(0L, ByteArray(0))

        assertEquals(ReceivedIntent(contextA, payloadA), stubA.lastInvocation)
        assertEquals(ReceivedIntent(contextB, payloadB), stubB.lastInvocation)
        assertEquals(1, stubA.invocationCount)
        assertEquals(1, stubB.invocationCount)
    }

    // -------------------------------------------------------------------------
    // Inbound Processing
    // -------------------------------------------------------------------------

    @Test
    fun should_isolateStateIssue_and_allowSubsequentIntentsToSucceed() = runEnv {
        bootManager.updateState(BootState.Ready)

        val hlcA = TestHlcFactory.create(ts = 1000L, count = 0)
        val hlcB = TestHlcFactory.create(ts = 2000L, count = 0)

        val failingIntent = createTestSyncIntent(
            hlc = hlcA,
            candidateKey = 401L,
            featureContext = FeatureContext.TEST_STUB_A,
            payload = byteArrayOf(0x01)
        )
        val successfulIntent = createTestSyncIntent(
            hlc = hlcB,
            candidateKey = 402L,
            featureContext = FeatureContext.TEST_STUB_B,
            payload = byteArrayOf(0x02)
        )
        payloadCodec.nextDecodeResult = listOf(failingIntent, successfulIntent)
        stubA.shouldFail =
            MochaException.Transient.StateIssue("Database constraint violation in Receiver A")

        coordinator.handleInboundBytes(0L, ByteArray(0))

        assertEquals(1, stubA.invocationCount)
        assertEquals(1, stubB.invocationCount)
        assertEquals(listOf(hlcB), nodeManager.updatedHlcFloors)
        assertTrue(hlcFactory.witnessedHlcs.contains(hlcB))
        assertFalse(hlcFactory.witnessedHlcs.contains(hlcA))
        val finalState = intentStore.intents.first()
        assertEquals(401L, finalState.candidateKey)
        assertEquals(SyncStatus.QUARANTINED, finalState.syncStatus)
    }

    @Test
    fun should_quarantinePayloadIncrementWatermarkGracefully_onInitialDecodeFailure() = runEnv {
        bootManager.updateState(BootState.Ready)
        payloadCodec.decodeError = NullPointerException()
        val testPayload = byteArrayOf(0x01, 0x02)

        coordinator.handleInboundBytes(1L, testPayload)

        val quarantinedRecords = quarantineStore.getAll()
        assertEquals(1, quarantinedRecords.size)
        val quarantinedPayload = quarantinedRecords.first()
        assertEquals(1L, quarantinedPayload.watermark)
        assertContentEquals(testPayload, quarantinedPayload.rawPayload)

        assertEquals(1L, nodeManager.getLastInboundWatermark())
        assertEquals(null, nodeManager.getMaxHlc())
    }

    @Test
    fun should_abortBatch_onUnexpectedException() = runEnv {
        bootManager.updateState(BootState.Ready)

        val hlcA = TestHlcFactory.create(ts = 1000L, count = 0)
        val hlcB = TestHlcFactory.create(ts = 2000L, count = 0)

        val bombIntent = createTestSyncIntent(
            hlc = hlcA,
            candidateKey = 401L,
            featureContext = FeatureContext.TEST_STUB_A,
            payload = byteArrayOf(0x01)
        )
        val innocentIntent = createTestSyncIntent(
            hlc = hlcB,
            candidateKey = 402L,
            featureContext = FeatureContext.TEST_STUB_B,
            payload = byteArrayOf(0x02)
        )

        payloadCodec.nextDecodeResult = listOf(bombIntent, innocentIntent)
        stubA.shouldFail = MochaException.Persistent.Uncategorized("Fire in the cockpit")

        assertFailsWith<MochaException.Persistent.Uncategorized> {
            coordinator.handleInboundBytes(0L, ByteArray(0))
        }

        assertEquals(1, stubA.invocationCount)
        assertEquals(0, stubB.invocationCount)
        assertEquals(0, nodeManager.updatedHlcFloors.size)
        assertEquals(0, hlcFactory.witnessedHlcs.size)
    }

    @Test
    fun should_isolateUnregisteredFeatureContext_withoutPoisoningBatch() = runEnv {
        bootManager.updateState(BootState.Ready)

        val hlcB = TestHlcFactory.create(ts = 5000L, count = 0)

        val unroutableIntent = createTestSyncIntent(
            candidateKey = 501L,
            featureContext = FeatureContext.UNRECOGNIZED_MODEL,
            payload = byteArrayOf(0x01)
        )
        val validIntent = createTestSyncIntent(
            hlc = hlcB,
            candidateKey = 502L,
            featureContext = FeatureContext.TEST_STUB_B,
            payload = byteArrayOf(0x02)
        )
        payloadCodec.nextDecodeResult = listOf(unroutableIntent, validIntent)

        coordinator.handleInboundBytes(0L, ByteArray(0))

        assertEquals(0, stubA.invocationCount)
        assertEquals(1, stubB.invocationCount)
        assertEquals(listOf(hlcB), nodeManager.updatedHlcFloors)
    }

    @Test
    fun should_retryAndRecover_when_transactorThrowsDatabaseBusy() = runEnv { scope ->
        bootManager.updateState(BootState.Ready)

        val hlc = TestHlcFactory.create(ts = 1000L)
        val intent = createTestSyncIntent(
            hlc = hlc,
            candidateKey = 101L,
            featureContext = FeatureContext.TEST_STUB_A
        )
        payloadCodec.nextDecodeResult = listOf(intent)
        transactor.shouldThrow = MochaException.Transient.DatabaseBusy("SQLite database locked")
        val initialTime = scope.currentTime

        coordinator.handleInboundBytes(0L, byteArrayOf(0x01))

        assertEquals(2, transactor.executionCount, "Retry plus Success")
        assertTrue(scope.currentTime > initialTime, "Staggered Retry")
        assertEquals(1, stubA.invocationCount)
        assertEquals(listOf(hlc), nodeManager.updatedHlcFloors)
        assertEquals(1, payloadCodec.decodeCallCount)
    }

    @Test
    fun should_processConcurrentInboundCalls_and_advanceAllHlcFloorsWithoutLoss() =
        runEnv { scope ->
            bootManager.updateState(BootState.Ready)
            hlcFactory.hydrate(null, TestNodeId.A)

            val hlc1 = TestHlcFactory.createWithOffset(1.seconds)
            val hlc2 = TestHlcFactory.createWithOffset(2.seconds)

            // Given: simulation of suspending during intent1 processRemoteIntent
            val intent1 = createTestSyncIntent(
                hlc = hlc1,
                candidateKey = 10L,
                featureContext = FeatureContext.TEST_STUB_A
            )
            val intent2 = createTestSyncIntent(
                hlc = hlc2,
                candidateKey = 20L,
                featureContext = FeatureContext.TEST_STUB_A
            )
            payloadCodec.nextDecodeResult = listOf(intent1)

            val firstCallSuspended = CompletableDeferred<Unit>()
            val releaseFirstCall = CompletableDeferred<Unit>()

            stubA.onProcessHook = { _, _ ->
                firstCallSuspended.complete(Unit)
                releaseFirstCall.await()
            }

            scope.launch {
                coordinator.handleInboundBytes(0L, byteArrayOf(0))
            }
            firstCallSuspended.await()

            // Given: Execution context for intent2
            stubA.onProcessHook = null
            payloadCodec.nextDecodeResult = listOf(intent2)

            scope.launch {
                coordinator.handleInboundBytes(0L, byteArrayOf(0))
            }
            scope.runCurrent()

            // When: concurrent inbound calls are processed
            releaseFirstCall.complete(Unit)
            scope.advanceUntilIdle()

            // Then: both intents processed sequentially
            assertEquals(2, stubA.invocationCount)
            assertEquals(2, payloadCodec.decodeCallCount)
            assertEquals(hlc2, hlcFactory.getCurrentHlc())
            assertTrue(nodeManager.updatedHlcFloors.contains(hlc1))
            assertTrue(nodeManager.updatedHlcFloors.contains(hlc2))
        }

    // --- State Guards ---

    @Test
    fun should_rejectIntent_when_bothPayloadAndOverflowBlobIdAreNull() = runEnv {
        bootManager.updateState(BootState.Ready)

        val invalidIntent = createTestSyncIntent(
            candidateKey = 301L,
            featureContext = FeatureContext.TEST_STUB_A,
            payload = null,
            overflowBlobId = null
        )

        payloadCodec.nextDecodeResult = listOf(invalidIntent)
        coordinator.handleInboundBytes(0L, ByteArray(0))

        assertEquals(0, stubA.invocationCount)
        assertEquals(1, intentStore.intents.size, "Quarantined Intent")
        assertEquals(0, nodeManager.updatedHlcFloors.size)
    }

    @Test
    fun should_rejectIntent_when_bothPayloadAndOverflowBlobIdArePresent() = runEnv {
        bootManager.updateState(BootState.Ready)

        val invalidIntent = createTestSyncIntent(
            candidateKey = 302L,
            featureContext = FeatureContext.TEST_STUB_A,
            payload = byteArrayOf(0x05),
            overflowBlobId = "blob_overflow_123"
        )

        payloadCodec.nextDecodeResult = listOf(invalidIntent)
        coordinator.handleInboundBytes(0L, ByteArray(0))

        assertEquals(0, stubA.invocationCount)
        assertEquals(1, intentStore.intents.size, "Quarantined Intent")
        assertEquals(0, nodeManager.updatedHlcFloors.size)
    }

    @Test
    fun should_stageInStore_and_dispatchToReceiver_when_intentIsLegitimateOverflow() = runEnv {
        bootManager.updateState(BootState.Ready)

        val overflowIntent = createTestSyncIntent(
            candidateKey = 303L,
            featureContext = FeatureContext.TEST_STUB_A,
            payload = null,
            overflowBlobId = "blob_valid_999"
        )

        payloadCodec.nextDecodeResult = listOf(overflowIntent)
        coordinator.handleInboundBytes(0L, ByteArray(0))

        assertEquals(1, intentStore.intents.size)
        assertEquals(303L, intentStore.intents.first().candidateKey)

        assertEquals(1, stubA.invocationCount)
        val received = stubA.lastInvocation!!
        assertNull(received.payload)
        assertEquals("blob_valid_999", received.context.overflowBlobId)
    }

    // --- HLC Floor Progression ---

    @Test
    fun should_advanceHlcFloorToMaxSuccessfulTimestamp_when_orderIsInterleaved() = runEnv {
        bootManager.updateState(BootState.Ready)

        val hlc1 = TestHlcFactory.create(ts = 1000L, count = 0)
        val hlc2Failing = TestHlcFactory.create(ts = 5000L, count = 0)
        val hlc3 = TestHlcFactory.create(ts = 3000L, count = 0)

        val intent1 = createTestSyncIntent(
            hlc = hlc1,
            candidateKey = 601L,
            featureContext = FeatureContext.TEST_STUB_A,
            payload = byteArrayOf(0x01)
        )
        val intent2 = createTestSyncIntent(
            hlc = hlc2Failing,
            candidateKey = 602L,
            featureContext = FeatureContext.TEST_STUB_A,
            payload = null,
            overflowBlobId = null
        )
        val intent3 = createTestSyncIntent(
            hlc = hlc3,
            candidateKey = 603L,
            featureContext = FeatureContext.TEST_STUB_B,
            payload = byteArrayOf(0x03)
        )
        payloadCodec.nextDecodeResult = listOf(intent1, intent2, intent3)

        coordinator.handleInboundBytes(0L, ByteArray(0))

        assertEquals(listOf(hlc3), nodeManager.updatedHlcFloors)
        assertEquals(listOf(hlc3), hlcFactory.witnessedHlcs)
    }

    @Test
    fun should_notAdvanceHlcFloor_when_allIntentsInBatchFail() = runEnv {
        bootManager.updateState(BootState.Ready)

        val hlcA = TestHlcFactory.create(ts = 1000L, count = 0)
        val invalidIntent = createTestSyncIntent(
            hlc = hlcA,
            candidateKey = 701L,
            featureContext = FeatureContext.TEST_STUB_A,
            payload = null,
            overflowBlobId = null
        )
        payloadCodec.nextDecodeResult = listOf(invalidIntent)

        coordinator.handleInboundBytes(0L, ByteArray(0))

        assertEquals(0, nodeManager.updatedHlcFloors.size)
        assertEquals(0, hlcFactory.witnessedHlcs.size)
    }

    // -------------------------------------------------------------------------
    // Outbound Queue
    // -------------------------------------------------------------------------

    @Test
    fun should_exitCleanly_withoutEncoding_when_claimBatchReturnsZeroRows() = runEnv { scope ->
        bootManager.updateState(BootState.Ready)

        val outboundJob = coordinator.startOutboundListener()
        scope.runCurrent()

        workerHook.invalidate()
        scope.runCurrent()

        assertEquals(1, intentStore.claimedBatchCallCount, "One invalidation")
        assertEquals(0, payloadCodec.encodeCallCount)

        outboundJob.cancel()
    }

    @Test
    fun should_processAllAvailableBatches_until_queueIsEmpty() = runEnv {
        // Given
        bootManager.updateState(BootState.Ready)
        val hlc1 = TestHlcFactory.create(ts = 100)
        val hlc2 = TestHlcFactory.create(ts = 200)

        val intent1 = createTestSyncIntent(candidateKey = 1L, hlc = hlc1)
        val intent2 = createTestSyncIntent(candidateKey = 2L, hlc = hlc2)
        intentStore.seedIntents(intent1, intent2)

        syncTransport.registerInboundAckHandler { batchId, watermark ->
            coordinator.handleInboundAck(batchId, watermark)
        }
        syncTransport.autoAck = true

        // When
        coordinator.processQueueUntilExhausted()

        // Iteration 1: Claims all pending rows (2), encodes client
        // Iteration 2: Queue empty, claims 0 rows, breaks while-loop
        val encodedBatch = payloadCodec.encodedInvocations.first()
        assertEquals(1, payloadCodec.encodeCallCount)
        assertEquals(2, encodedBatch.size, "client size")
        assertEquals(SyncStatus.SYNCING, encodedBatch.first().syncStatus)
        assertEquals(SyncStatus.SYNCING, encodedBatch.last().syncStatus)
        assertEquals(2, intentStore.claimedBatchCallCount, "client call count")
    }

    @Test
    fun should_coalesceInvalidationBurst_and_processNewlyAddedIntentsInSameSweep() =
        runEnv { scope ->
            // Given
            bootManager.updateState(BootState.Ready)
            syncTransport.registerInboundAckHandler { batchId, watermark ->
                coordinator.handleInboundAck(batchId, watermark)
            }
            syncTransport.autoAck = true

            val outboundJob = coordinator.startOutboundListener()
            scope.runCurrent()

            // Arrange Batch 1
            val batch1Intent = createTestSyncIntent(candidateKey = 1L)
            intentStore.seedIntents(batch1Intent)

            val batch1Claimed = CompletableDeferred<Unit>()
            val releaseBatch1 = CompletableDeferred<Unit>()

            intentStore.onClaimHook = {
                batch1Claimed.complete(Unit)
                releaseBatch1.await()
            }

            // Act Batch 1
            workerHook.invalidate()
            scope.runCurrent()
            batch1Claimed.await() // ensure Coordinator is suspended processing claim

            // Arrange Batch 2 and invalidation
            val batch2Intent =
                createTestSyncIntent(candidateKey = 2L, hlc = TestHlcFactory.create(ts = 100))
            intentStore.seedIntents(batch2Intent)
            intentStore.onClaimHook = null

            repeat(5) {
                workerHook.invalidate()
            }
            scope.runCurrent()

            // Unblock the initial sweep
            releaseBatch1.complete(Unit)
            scope.advanceUntilIdle()

            // Assert Batch 1 transition: state mutated to SYNCING with a generated batch ID
            val encodedBatch1 = payloadCodec.encodedInvocations[0]
            assertEquals(1, encodedBatch1.size, "Batch 1 size")
            val claimed1 = encodedBatch1.first()
            assertEquals(batch1Intent.candidateKey, claimed1.candidateKey)
            assertEquals(batch1Intent.hlc, claimed1.hlc)
            assertEquals(SyncStatus.SYNCING, claimed1.syncStatus)
            assertNotNull(claimed1.batchId)

            // Assert Batch 2 transition: state mutated to SYNCING with a distinct batch ID
            val encodedBatch2 = payloadCodec.encodedInvocations[1]
            assertEquals(1, encodedBatch2.size, "Batch 2 size")
            val claimed2 = encodedBatch2.first()
            assertEquals(batch2Intent.candidateKey, claimed2.candidateKey)
            assertEquals(batch2Intent.hlc, claimed2.hlc)
            assertEquals(SyncStatus.SYNCING, claimed2.syncStatus)
            assertNotNull(claimed2.batchId)

            assertTrue(claimed1.batchId != claimed2.batchId, "Claimed batch must be distinct")

            // Assert Side-Effects
            assertEquals(2, payloadCodec.encodeCallCount)
            assertEquals(6, workerHook.invalidationCount)

            outboundJob.cancel()
        }

    @Test
    fun should_stampLastErrorAndRetainSyncingLease_when_transportFailsOnSend() = runEnv {
        // Given SyncTransport set to fail on sending the Frame
        val failureMessage = "Simulated transmission failure"
        val testIntent = createTestSyncIntent(
            hlc = TestHlcFactory.create(),
            candidateKey = 42L,
            status = SyncStatus.PENDING
        )
        intentStore.seedIntents(testIntent)
        syncTransport.failWith = IOException(failureMessage)

        // When
        coordinator.processQueueUntilExhausted()

        // Then
        val intents = intentStore.intents
        assertEquals(1, intents.size)

        val processedIntent = intents.first()
        assertEquals(SyncStatus.SYNCING, processedIntent.syncStatus)
        assertNotNull(processedIntent.batchId)
        assertNotNull(processedIntent.leasedAt)
        assertEquals(failureMessage, processedIntent.lastErrorMessage)
    }

    @Test
    fun should_isolateDownstreamException_and_preserveStreamLifecycleForSubsequentInvalidations() =
        runEnv { scope ->
            bootManager.updateState(BootState.Ready)
            syncTransport.registerInboundAckHandler { batchId, watermark ->
                coordinator.handleInboundAck(batchId, watermark)
            }
            syncTransport.autoAck = true

            val outboundJob = coordinator.startOutboundListener()
            scope.runCurrent()
            assertEquals(0, intentStore.claimedBatchCallCount)
            intentStore.claimedBatchCallCount = 0
            assertTrue(outboundJob.isActive, "Outbound pipeline collector must be active")

            // Given a failed intent
            val failedIntent = createTestSyncIntent(candidateKey = 1L)
            intentStore.seedIntents(failedIntent)
            intentStore.failWith = IllegalStateException("Disk I/O failure during claim")

            // When invalidation triggers
            workerHook.invalidate()
            scope.runCurrent()

            // Then a one time failure did not terminate the outbound pipeline
            assertTrue(outboundJob.isActive, "Collector job must remain active")
            assertEquals(1, intentStore.claimedBatchCallCount)
            assertEquals(0, payloadCodec.encodeCallCount)


            // Given a valid execution environment
            intentStore.failWith = null
            val recoveryIntent =
                createTestSyncIntent(candidateKey = 2L, hlc = TestHlcFactory.create(100L))
            intentStore.seedIntents(recoveryIntent)

            // When invalidation triggers
            workerHook.invalidate()
            scope.advanceUntilIdle()

            // Then pipeline recovered all intents
            assertTrue(outboundJob.isActive, "Collector job must still be running after recovery")
            assertEquals(3, intentStore.claimedBatchCallCount)
            assertEquals(1, payloadCodec.encodeCallCount)

            val encodedBatch = payloadCodec.encodedInvocations.first()
            assertEquals(2, encodedBatch.size)
            val claimedExample = encodedBatch.first()
            assertEquals(recoveryIntent.candidateKey, claimedExample.candidateKey)
            assertEquals(SyncStatus.SYNCING, claimedExample.syncStatus)
            assertNotNull(claimedExample.batchId)

            outboundJob.cancel()
        }

    @Test
    fun should_isolateCodecSerializationError_and_drainPendingQueueOnNextSignal() =
        runEnv { scope ->
            bootManager.updateState(BootState.Ready)

            val outboundJob = coordinator.startOutboundListener()
            scope.runCurrent()

            // Given unrecoverable intent
            val intent1 = createTestSyncIntent(candidateKey = 10L)
            intentStore.seedIntents(intent1)
            payloadCodec.encodeError = RuntimeException("Something something ones and zeroes")
            intentCodec.encodeError = RuntimeException("Something something ones and zeroes again")

            // When worker triggered
            workerHook.invalidate()
            scope.runCurrent()

            // Then pipeline remains active and intent state is recoverable by Janitor
            assertTrue(outboundJob.isActive, "Stream must not crash on codec error")
            assertEquals(1, payloadCodec.encodeCallCount, "Initial Encode")
            assertEquals(1, intentStore.intents.size)
            val unprocessedIntent = intentStore.intents.first()
            assertEquals(intent1.candidateKey, unprocessedIntent.candidateKey)
            assertEquals(SyncStatus.QUARANTINED, unprocessedIntent.syncStatus)
            val initialLeased = unprocessedIntent.leasedAt
            assertNotNull(unprocessedIntent.leasedAt, "Should persist leasedAt for debugging.")

            // Given consequential valid execution context
            val intent2 =
                createTestSyncIntent(candidateKey = 20L, hlc = TestHlcFactory.create(ts = 100L))
            intentStore.seedIntents(intent2)

            // When worker triggered
            workerHook.invalidate()
            scope.advanceUntilIdle()

            // Then pipeline recovered second intent
            assertTrue(outboundJob.isActive)
            assertEquals(2, payloadCodec.encodeCallCount)
            val processedIntent = payloadCodec.encodedInvocations[1].first()
            assertEquals(intent2.candidateKey, processedIntent.candidateKey)
            assertEquals(SyncStatus.SYNCING, processedIntent.syncStatus)
            assertEquals(initialLeased, payloadCodec.encodedInvocations[1].first().leasedAt)

            outboundJob.cancel()
        }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Test
    fun should_abortInFlightBatchCleanly_when_jobCancelledDuringExecution() = runEnv { scope ->
        bootManager.updateState(BootState.Ready)

        // Given Coordinator is suspended on outbound pipeline
        val outboundJob = coordinator.startOutboundListener()
        scope.runCurrent()
        intentStore.claimedBatchCallCount = 0

        val intent = createTestSyncIntent(candidateKey = 20L)
        intentStore.seedIntents(intent)

        val batchClaimed = CompletableDeferred<Unit>()
        val releaseBatch = CompletableDeferred<Unit>()

        intentStore.onClaimHook = {
            batchClaimed.complete(Unit)
            releaseBatch.await()
        }

        workerHook.invalidate()
        scope.runCurrent()
        batchClaimed.await()

        // When Job is canceled
        outboundJob.cancel()
        scope.advanceUntilIdle()

        // Then outbound pipeline is canceled
        assertTrue(outboundJob.isCancelled)
        assertEquals(1, intentStore.claimedBatchCallCount)
        assertEquals(0, payloadCodec.encodeCallCount)
        releaseBatch.complete(Unit)
    }

    @Test
    fun should_stopCollectingSignals_immediatelyAfterJobCancellation() = runEnv { scope ->
        bootManager.updateState(BootState.Ready)

        // Given canceled job after boot process
        val outboundJob = coordinator.startOutboundListener()
        scope.runCurrent()
        assertEquals(0, intentStore.claimedBatchCallCount)
        intentStore.claimedBatchCallCount = 0

        outboundJob.cancel()
        scope.runCurrent()

        // When invalidation triggers
        repeat(3) {
            workerHook.invalidate()
        }
        scope.advanceUntilIdle()

        // Then
        assertEquals(0, intentStore.claimedBatchCallCount)
        assertEquals(0, payloadCodec.encodeCallCount)
        assertEquals(0, workerHook.totalCollects)
    }

    // -------------------------------------------------------------------------
    // Contention
    // -------------------------------------------------------------------------

    @Test
    fun should_recoverAndPersistCorrectly_on_concurrentInboundAndOutboundWithTransientDbBusy() =
        runEnv(
            bindTestScope = false,
            overrideSetup = {
                modules(
                    module {
                        single<CoroutineContext>(qualifier = named<IoContext>()) {
                            Dispatchers.Default
                        }

                        single<CoroutineScope>(qualifier = named<AppBackgroundScope>()) {
                            CoroutineScope(SupervisorJob() + Dispatchers.Default)
                        }
                    }
                )
            }
        ) { scope ->
            bootManager.updateState(BootState.Ready)
            hlcFactory.hydrate(null, TestNodeId.A)
            syncTransport.registerInboundAckHandler { batchId, watermark ->
                coordinator.handleInboundAck(batchId, watermark)
            }
            syncTransport.autoAck = true
            intentStore.failWith = MochaException.Transient.DatabaseBusy("Database busy")
            val outboundJob = coordinator.startOutboundListener()

            // Given: 3 inbound workers, 3 outbound workers - all staged
            val inboundWorkers = 3
            val outboundWorkers = 3
            val totalWorkers = inboundWorkers + outboundWorkers
            val operationsPerWorker = 3

            val readySignals = List(totalWorkers) { CompletableDeferred<Unit>() }
            val startGate = CompletableDeferred<Unit>()

            val inboundIntent = createTestSyncIntent(
                hlc = TestHlcFactory.createWithOffset(1.milliseconds),
                candidateKey = 999L,
                featureContext = FeatureContext.TEST_STUB_A
            )
            payloadCodec.nextDecodeResult = listOf(inboundIntent)

            val inboundJobs = List(inboundWorkers) { workerId ->
                scope.launch(Dispatchers.Default) {
                    readySignals[workerId].complete(Unit)
                    startGate.await()

                    repeat(operationsPerWorker) { opIndex ->
                        val payload = byteArrayOf(workerId.toByte(), opIndex.toByte())
                        coordinator.handleInboundBytes(0L, payload)
                    }
                }
            }

            val expectedOutboundKeys = (0 until outboundWorkers).flatMap { workerId ->
                (0 until operationsPerWorker).map { opIndex ->
                    (1000L * (workerId + 1)) + opIndex
                }
            }.toSet()

            val outboundJobs = List(outboundWorkers) { workerId ->
                scope.launch(Dispatchers.Default) {
                    readySignals[inboundWorkers + workerId].complete(Unit)
                    startGate.await()

                    repeat(operationsPerWorker) { opIndex ->
                        val uniqueOffset = ((workerId * operationsPerWorker) + opIndex + 1).seconds
                        val candidateKey = (1000L * (workerId + 1)) + opIndex
                        val hlc = TestHlcFactory.createWithOffset(uniqueOffset)

                        val intent = createTestSyncIntent(
                            candidateKey = candidateKey,
                            featureContext = FeatureContext.TEST_STUB_A,
                            hlc = hlc
                        )

                        hlcFactory.witness(hlc)
                        intentStore.seedIntents(intent)
                        workerHook.invalidate()
                    }
                }
            }

            // When: all workers execute in parallel
            readySignals.awaitAll()
            startGate.complete(Unit)
            (inboundJobs + outboundJobs).joinAll()

            awaitCondition(
                message = "Outbound consumer did not finish encoding all 9 intents in time"
            ) {
                payloadCodec.encodedInvocations.flatten().size >= expectedOutboundKeys.size
            }

            // Then: Inbound
            val totalInboundOperations = inboundWorkers * operationsPerWorker
            assertEquals(totalInboundOperations, stubA.invocationCount, "All inbound transactions.")
            assertTrue(nodeManager.updatedHlcFloors.contains(inboundIntent.hlc), "HLC floor.")

            // Then: Outbound
            assertIntentsProperlyBatched(expectedKeys = expectedOutboundKeys)

            // Then: Side-Effects
            val expectedMaxHlc =
                TestHlcFactory.createWithOffset((outboundWorkers * operationsPerWorker).seconds)
            assertEquals(expectedMaxHlc, hlcFactory.getCurrentHlc())
            assertEquals(9, intentStore.intents.size)
            assertEquals(9, workerHook.invalidationCount)

            outboundJob.cancelAndJoin()
        }

    // -------------------------------------------------------------------------
    // Transport & SendResult Interaction
    // -------------------------------------------------------------------------

    @Test
    fun should_skipProcessing_when_transportDisconnected() = runEnv {
        // Given
        val testIntent = createTestSyncIntent(
            hlc = TestHlcFactory.create(),
            candidateKey = 1L,
            status = SyncStatus.PENDING
        )
        intentStore.seedIntents(testIntent)
        syncTransport.isConnected = false

        // When
        coordinator.processQueueUntilExhausted()

        // Then
        assertEquals(0, intentStore.claimedBatchCallCount)
        assertTrue(syncTransport.sentBatches.isEmpty())
    }

    @Test
    fun should_releaseIntentsAndHalt_when_sendReturnsNoConnection() = runEnv {
        // Given
        val testIntent = createTestSyncIntent(
            hlc = TestHlcFactory.create(),
            candidateKey = 2L,
            status = SyncStatus.PENDING
        )
        intentStore.seedIntents(testIntent)
        syncTransport.nextSendResult = SendResult.NoConnection

        // When
        coordinator.processQueueUntilExhausted()

        // Then
        assertEquals(1, intentStore.claimedBatchCallCount)
        val processedIntent = intentStore.intents.first()
        assertEquals(SyncStatus.PENDING, processedIntent.syncStatus)
        assertNull(processedIntent.batchId)
        assertNull(processedIntent.leasedAt)
        assertTrue(syncTransport.sentBatches.isEmpty())
    }

    @Test
    fun should_stampErrorAndHalt_when_sendReturnsTransientFailure() = runEnv {
        // Given
        val failureMessage = "Transient Issue"
        val transientError = MochaException.Transient.StateIssue(failureMessage)
        val testIntent = createTestSyncIntent(
            hlc = TestHlcFactory.create(),
            candidateKey = 3L,
            status = SyncStatus.PENDING
        )
        intentStore.seedIntents(testIntent)
        syncTransport.nextSendResult = SendResult.Failure(transientError)

        // When
        coordinator.processQueueUntilExhausted()

        // Then
        assertEquals(1, intentStore.claimedBatchCallCount)
        val processedIntent = intentStore.intents.first()
        assertEquals(SyncStatus.SYNCING, processedIntent.syncStatus)
        assertNotNull(processedIntent.batchId)
        assertEquals(failureMessage, processedIntent.lastErrorMessage)
    }

    @Test
    fun should_rethrowAndTerminate_when_sendReturnsPersistentFailure() = runEnv {
        // Given
        val failureMessage = "Unrecoverable serialization payload corruption"
        val persistentError = MochaException.Persistent.Uncategorized(failureMessage)
        val testIntent = createTestSyncIntent(
            hlc = TestHlcFactory.create(),
            candidateKey = 4L,
            status = SyncStatus.PENDING
        )
        intentStore.seedIntents(testIntent)
        syncTransport.nextSendResult = SendResult.Failure(persistentError)

        // When / Then
        val thrown = assertFailsWith<MochaException.Persistent.Uncategorized> {
            coordinator.processQueueUntilExhausted()
        }
        assertEquals(failureMessage, thrown.message)

        val processedIntent = intentStore.intents.first()
        assertEquals(SyncStatus.SYNCING, processedIntent.syncStatus)
        assertEquals(failureMessage, processedIntent.lastErrorMessage)
        assertEquals(1, intentStore.claimedBatchCallCount)
    }

    // -------------------------------------------------------------------------
    // In-Flight Batch & Inbound ACK Coordination
    // -------------------------------------------------------------------------

    @Test
    fun should_advanceQueueSequentially_onCorrelatedInboundAck() = runEnv {
        // Given
        val intent = createTestSyncIntent(
            hlc = TestHlcFactory.create(ts = 100),
            candidateKey = 1L,
            status = SyncStatus.PENDING
        )
        intentStore.seedIntents(intent)
        syncTransport.onSendHook = { batchId, _ ->
            coordinator.handleInboundAck(batchId = batchId, watermark = 42L)
        }

        // When
        coordinator.processQueueUntilExhausted()

        // Then
        assertEquals(1, syncTransport.sentBatches.size)
        assertEquals(2, intentStore.claimedBatchCallCount)
        assertEquals(42L, nodeManager.getLastOutboundWatermark())
    }

    @Test
    fun should_releaseIntentsAndBreakLoop_onAwaitAckTimeout() = runEnv { scope ->
        // Given
        val testIntent = createTestSyncIntent(
            hlc = TestHlcFactory.create(),
            candidateKey = 6L,
            status = SyncStatus.PENDING
        )
        intentStore.seedIntents(testIntent)
        val initialTime = scope.currentTime

        // When
        coordinator.processQueueUntilExhausted()

        // Then
        assertEquals(initialTime + 15.seconds.inWholeMilliseconds, scope.currentTime)
        assertEquals(1, intentStore.claimedBatchCallCount)
        val processedIntent = intentStore.intents.first()
        assertEquals(SyncStatus.PENDING, processedIntent.syncStatus)
        assertNull(processedIntent.batchId)
        assertNull(processedIntent.leasedAt)
    }

    @Test
    fun should_releaseIntents_when_inFlightBatchAborted() = runEnv {
        // Given
        val testIntent = createTestSyncIntent(
            hlc = TestHlcFactory.create(),
            candidateKey = 7L,
            status = SyncStatus.PENDING
        )
        intentStore.seedIntents(testIntent)
        syncTransport.onSendHook = { _, _ ->
            assertEquals(SyncStatus.SYNCING, intentStore.intents.first().syncStatus)
            coordinator.abortInFlightBatch(
                MochaException.Transient.NetworkDisconnect("Socket connection lost during send")
            )
        }

        // When
        coordinator.processQueueUntilExhausted()

        // Then
        assertEquals(1, intentStore.claimedBatchCallCount)
        val processedIntent = intentStore.intents.first()
        assertEquals(SyncStatus.PENDING, processedIntent.syncStatus)
        assertNull(processedIntent.batchId)
        assertNull(processedIntent.leasedAt)
    }

    @Test
    fun should_safelySettleWatermark_when_ackArrivesWithNoInFlightBatch() = runEnv {
        // Given
        val batchId = 999L
        val watermark = 50L

        // When
        coordinator.handleInboundAck(batchId, watermark)

        // Then
        assertEquals(1, transactor.executionCount)
        assertEquals(watermark, nodeManager.getLastOutboundWatermark())
    }

    @Test
    fun should_ignoreMismatchedAck_and_retainActiveInFlightDeferred() = runEnv { scope ->
        // Given
        val testIntent = createTestSyncIntent(
            hlc = TestHlcFactory.create(),
            candidateKey = 9L,
            status = SyncStatus.PENDING
        )
        intentStore.seedIntents(testIntent)

        val mismatchedBatchId = 99L
        val mismatchedWatermark = 40L
        var capturedBatchId = 0L

        syncTransport.onSendHook = { batchId, _ ->
            capturedBatchId = batchId
            coordinator.handleInboundAck(
                batchId = mismatchedBatchId,
                watermark = mismatchedWatermark
            )
        }

        // When
        val outboundJob = scope.launch {
            coordinator.processQueueUntilExhausted()
        }
        scope.runCurrent()

        // Then
        assertTrue(outboundJob.isActive)
        assertEquals(mismatchedWatermark, nodeManager.getLastOutboundWatermark())

        // Settle active batch
        coordinator.handleInboundAck(batchId = capturedBatchId, watermark = 41L)
        scope.runCurrent()

        assertTrue(outboundJob.isCompleted)
        assertEquals(41L, nodeManager.getLastOutboundWatermark())
    }

    @Test
    fun should_settleWatermarkAndAcknowledgeSuccess_when_decoupledAckArrivesAfterOutboundCancelled() =
        runEnv { scope ->
            // Given
            val testIntent = createTestSyncIntent(
                hlc = TestHlcFactory.create(),
                candidateKey = 10L,
                status = SyncStatus.PENDING
            )
            intentStore.seedIntents(testIntent)

            var capturedBatchId = 0L
            syncTransport.onSendHook = { batchId, _ ->
                capturedBatchId = batchId
            }

            val outboundJob = scope.launch {
                coordinator.processQueueUntilExhausted()
            }
            scope.runCurrent()

            // When: Outbound sender is killed while waiting for ACK
            outboundJob.cancelAndJoin()
            assertTrue(outboundJob.isCancelled)

            // When: Network ACK arrives decoupled/late
            coordinator.handleInboundAck(batchId = capturedBatchId, watermark = 99L)

            // Then: Database commits watermark and marks intent successful despite dead outbound job
            assertEquals(99L, nodeManager.getLastOutboundWatermark())
            val processedIntent = intentStore.intents.first()
            assertEquals(SyncStatus.SUCCESS, processedIntent.syncStatus)
        }
}