package com.mochame.sync.data

import com.mochame.support.MochaPlatformTest
import com.mochame.utils.fixtures.TestHlcFactory
import com.mochame.support.runDatabaseEnvironment
import com.mochame.sync.api.metadata.SyncStatus
import com.mochame.sync.di.data.SyncPersistenceTestModule
import com.mochame.sync.internal.fixtures.createTestIntentEntity
import kotlinx.coroutines.test.TestScope
import org.koin.plugin.module.dsl.modules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue


// -----------------------------------------------------------
// SUT ENVIRONMENT
// -----------------------------------------------------------
private inline fun runEnv(crossinline block: suspend SyncIntentDao.(TestScope) -> Unit) =
    runDatabaseEnvironment<SyncMicroSchema, SyncIntentDao>(
        constructor = SyncMicroSchemaConstructor,
        koinSetup = { modules(SyncPersistenceTestModule::class) },
        block = block,
    )


private const val testId = 1L


class SyncIntentDaoTest : MochaPlatformTest() {

    // -----------------------------------------------------------
    // BATCH ALLOCATION
    // -----------------------------------------------------------
    @Test
    fun should_claimBatchInStrictChronologicalOrder_when_insertedOutSequence() = runEnv {
        // Given
        val (hlc1, hlc2, hlc3) = TestHlcFactory.chronologicalSequence(size = 3)

        // Intentionally upserting out of order to verify database index sorting
        val intentLater = createTestIntentEntity(hlc = hlc3, candidateKey = 1)
        val intentEarlier = createTestIntentEntity(hlc = hlc1, candidateKey = 2)
        val intentMiddle = createTestIntentEntity(hlc = hlc2, candidateKey = 3)

        upsert(intentLater)
        upsert(intentEarlier)
        upsert(intentMiddle)

        // When
        val claimedBatch = claimAndGetBatch(id = testId, limit = 3)

        // Then
        assertEquals(3, claimedBatch.size)

        assertEquals(hlc1.toString(), claimedBatch[0].hlc)
        assertEquals(hlc2.toString(), claimedBatch[1].hlc)
        assertEquals(hlc3.toString(), claimedBatch[2].hlc)
    }

    @Test
    fun should_limitClaimedBatchSize_when_backlogExceedsLimit() = runEnv {
        // Given unordered sequence of intents against causal HLC's
        val hlcs = TestHlcFactory.chronologicalSequence(size = 5)
        val entities = hlcs.mapIndexed { index, hlc ->
            createTestIntentEntity(hlc = hlc, candidateKey = index.toLong())
        }
        entities.shuffled().forEach { entity ->
            upsert(entity)
        }

        val maxBatchLimit = 3
        val nextTestId = 2L

        // When
        val claimedBatch = claimAndGetBatch(id = testId, limit = maxBatchLimit)

        // Then
        assertEquals(maxBatchLimit, claimedBatch.size)
        assertEquals(hlcs[0].toString(), claimedBatch[0].hlc)
        assertEquals(hlcs[1].toString(), claimedBatch[1].hlc)
        assertEquals(hlcs[2].toString(), claimedBatch[2].hlc)

        // When - Fetch the next client from the remaining backlog
        val nextClaimedBatch = claimAndGetBatch(id = nextTestId, limit = maxBatchLimit)

        // Then - Verify it captures the remainder of the items chronologically
        assertEquals(2, nextClaimedBatch.size)
        assertEquals(hlcs[3].toString(), nextClaimedBatch[0].hlc)
        assertEquals(hlcs[4].toString(), nextClaimedBatch[1].hlc)
    }

    @Test
    fun should_isolateDataBatches_when_multipleSessionsClaimSequentially() = runEnv {
        // Given
        val hlcs = TestHlcFactory.chronologicalSequence(size = 4)
        hlcs.forEachIndexed { index, hlc ->
            upsert(createTestIntentEntity(hlc = hlc, candidateKey = index.toLong()))
        }

        val sessionAlpha = 10L
        val sessionBeta = 20L

        // When
        val batchAlpha = claimAndGetBatch(id = sessionAlpha, limit = 2)
        val batchBeta = claimAndGetBatch(id = sessionBeta, limit = 2)

        // Then
        // Verify Alpha
        assertEquals(2, batchAlpha.size)
        assertEquals(hlcs[0].toString(), batchAlpha[0].hlc)
        assertEquals(hlcs[1].toString(), batchAlpha[1].hlc)

        // Verify Beta
        assertEquals(2, batchBeta.size)
        assertEquals(hlcs[2].toString(), batchBeta[0].hlc)
        assertEquals(hlcs[3].toString(), batchBeta[1].hlc)

        // Confirm isolation
        val alphaHlcs = batchAlpha.map { it.hlc }.toSet()
        val betaHlcs = batchBeta.map { it.hlc }.toSet()
        val intersection = alphaHlcs.intersect(betaHlcs)

        assertTrue(intersection.isEmpty(), "Sessions contain overlapping HLC records")
    }

    @Test
    fun should_skipQuarantinedIntents_when_claimingFreshBatch() = runEnv {
        // Given
        val (hlcOldestQuarantined, hlcNewerPending) = TestHlcFactory.chronologicalSequence(size = 2)

        val quarantinedIntent = createTestIntentEntity(
            hlc = hlcOldestQuarantined,
            candidateKey = 1L,
            status = SyncStatus.QUARANTINED
        )

        val healthyIntent = createTestIntentEntity(
            hlc = hlcNewerPending,
            candidateKey = 2L,
            status = SyncStatus.PENDING
        )

        upsert(quarantinedIntent)
        upsert(healthyIntent)

        // When
        val claimedBatch = claimAndGetBatch(id = testId, limit = 50)

        // Then
        assertEquals(1, claimedBatch.size)
        assertEquals(hlcNewerPending.toString(), claimedBatch[0].hlc)
        assertEquals(2L, claimedBatch[0].candidateKey)
    }

    @Test
    fun should_returnEmptyBatch_when_backlogIsEmptyOrFullyLeased() = runEnv {
        // Scenario A: Backlog is empty
        val batchAlpha = claimAndGetBatch(id = testId, limit = 10)
        assertTrue(batchAlpha.isEmpty())

        // Scenario B: Items exist, but are already leased under another batchId
        val targetHlc = TestHlcFactory.create()
        val activelyLeasedIntent = createTestIntentEntity(
            hlc = targetHlc,
            status = SyncStatus.SYNCING,
            batchId = 99L
        )

        upsert(activelyLeasedIntent)

        // When
        val batchBeta = claimAndGetBatch(id = 88L, limit = 10)

        // Then
        assertTrue(batchBeta.isEmpty())
    }

    @Test
    fun should_filterCorrectRowsOnly_when_ledgerContainsMixedStatuses() = runEnv {
        // Given
        val hlcs = TestHlcFactory.chronologicalSequence(size = 4)

        val intentSuccess = createTestIntentEntity(
            hlc = hlcs[0],
            status = SyncStatus.SUCCESS,
            batchId = 101L,
            leasedAt = 1000L
        )
        val intentLeased = createTestIntentEntity(
            hlc = hlcs[1],
            status = SyncStatus.SYNCING,
            batchId = 102L,
            leasedAt = 1001L
        )
        val intentPending = createTestIntentEntity(
            hlc = hlcs[2],
            candidateKey = 5L,
            status = SyncStatus.PENDING,
            leasedAt = null,
            batchId = null
        )
        val intentQuarantined = createTestIntentEntity(
            hlc = hlcs[3],
            status = SyncStatus.QUARANTINED,
            batchId = 103L,
            leasedAt = 999L
        )

        upsert(intentSuccess)
        upsert(intentLeased)
        upsert(intentPending)
        upsert(intentQuarantined)

        val currentSession = 30L

        // When
        val claimedBatch = claimAndGetBatch(id = currentSession, limit = 10)

        // Then
        assertEquals(1, claimedBatch.size)
        assertEquals(hlcs[2].toString(), claimedBatch[0].hlc)
        assertEquals(5L, claimedBatch[0].candidateKey)
    }

    // -----------------------------------------------------------
    // STATE MANAGEMENT
    // -----------------------------------------------------------
    @Test
    fun should_stampDiagnosticMessageAcrossBatch_when_batchFailureOccurs() = runEnv {
        // Given
        val hlcs = TestHlcFactory.chronologicalSequence(size = 3)
        upsert(createTestIntentEntity(hlc = hlcs[0], candidateKey = 1))
        upsert(createTestIntentEntity(hlc = hlcs[1], candidateKey = 2))
        upsert(createTestIntentEntity(hlc = hlcs[2], candidateKey = 3))

        val verificationSession = 40L
        val untouchedSession = 50L

        // Claim two into verificationSession and one into untouchedSession
        claimAndGetBatch(id = verificationSession, limit = 2)
        claimAndGetBatch(id = untouchedSession, limit = 1)

        val failureMessage = "HTTP 502: Bad Gateway Gateway Timeout"

        // When
        stampLastError(batchId = verificationSession, message = failureMessage)

        // Then
        val targetBatch = getClaimedBatch(id = verificationSession)
        val otherBatch = getClaimedBatch(id = untouchedSession)

        assertEquals(2, targetBatch.size)
        targetBatch.forEach { entity ->
            assertEquals(failureMessage, entity.lastErrorMessage)
        }

        assertEquals(1, otherBatch.size)
        assertNull(otherBatch.first().lastErrorMessage)
    }

    @Test
    fun should_updateBatchStatus_when_sessionAndExpectedStatusMatch() = runEnv {
        // Given
        val hlc = TestHlcFactory.create()
        upsert(createTestIntentEntity(hlc = hlc))

        claimAndGetBatch(id = testId, limit = 1)

        // When
        val updatedRows = updateBatchStatus(
            batchId = testId,
            status = SyncStatus.SUCCESS,
            expectedCurrentStatus = SyncStatus.SYNCING
        )

        // Then
        assertEquals(1, updatedRows)

        val records = getClaimedBatch(id = testId)
        assertEquals(1, records.size)
        assertEquals(SyncStatus.SUCCESS, records.first().syncStatus)
        assertNotNull(records.first().batchId)
        assertNotNull(records.first().leasedAt)
    }

    @Test
    fun should_accuratelyTrackBlobExistence_when_payloadIsOverflowed() = runEnv {
        // Given
        val targetBlobId = "blob-large-payload-789"
        val hlc = TestHlcFactory.create()

        val intentWithBlob = createTestIntentEntity(hlc = hlc, overflowBlobId = targetBlobId)

        assertFalse(existsForBlobId(targetBlobId))

        // When
        upsert(intentWithBlob)

        // Then
        assertTrue(existsForBlobId(targetBlobId))
        assertFalse(existsForBlobId("non-existent-blob-id"))
    }

    // -----------------------------------------------------------
    // MAINTENANCE
    // -----------------------------------------------------------
    @Test
    fun should_resetStaleLeasesBackToPending_when_leasedAtBeforeCutoffAndBelowRetryThreshold() =
        runEnv {
            // Given
            val hlcs = TestHlcFactory.chronologicalSequence(size = 2)
            val cutoffTime = TestHlcFactory.BASE_TEST_TIME

            // Stale lease: below retry threshold
            val staleLease = createTestIntentEntity(
                hlc = hlcs[0],
                status = SyncStatus.SYNCING,
                batchId = 201L,
                leasedAt = cutoffTime - 1000L,
                retryCount = 0
            )
            // Active lease: leased after the cutoff
            val activeLease = createTestIntentEntity(
                hlc = hlcs[1],
                status = SyncStatus.SYNCING,
                batchId = 202L,
                leasedAt = cutoffTime + 1000L,
                retryCount = 0
            )

            upsert(staleLease)
            upsert(activeLease)

            // When
            val resetCount = resetStaleLeases(
                cutOff = cutoffTime,
                retryThreshold = 3,
                shouldIncrementRetry = true
            )

            // Then
            assertEquals(1, resetCount)

            // Verify the reset lease can now be claimed
            val freshSession = 300L
            val recoveredBatch = claimAndGetBatch(id = freshSession, limit = 10)

            assertEquals(1, recoveredBatch.size)
            val recoveredRecord = recoveredBatch.first()
            assertEquals(hlcs[0].toString(), recoveredRecord.hlc)
            assertEquals(1, recoveredRecord.retryCount)
            assertEquals(freshSession, recoveredRecord.batchId)
            assertEquals(SyncStatus.SYNCING, recoveredRecord.syncStatus)
        }

    @Test
    fun should_quarantineStaleLeases_when_leasedAtBeforeCutoffAndRetryThresholdMet() = runEnv {
        // Given
        val hlcs = TestHlcFactory.chronologicalSequence(size = 2)
        val cutoffTime = TestHlcFactory.BASE_TEST_TIME

        // Reached retry threshold upon next failure
        val exhaustedLease = createTestIntentEntity(
            hlc = hlcs[0],
            status = SyncStatus.SYNCING,
            batchId = 203L,
            leasedAt = cutoffTime - 1000L,
            retryCount = 2
        )
        // Still below threshold
        val retryableLease = createTestIntentEntity(
            hlc = hlcs[1],
            status = SyncStatus.SYNCING,
            batchId = 204L,
            leasedAt = cutoffTime - 1000L,
            retryCount = 0
        )

        upsert(exhaustedLease)
        upsert(retryableLease)

        // When
        val quarantinedCount = quarantineStaleLeases(cutOff = cutoffTime, retryThreshold = 3)

        // Then
        assertEquals(1, quarantinedCount)

        // Verify the quarantined intent cannot be claimed by a fresh session
        val claimedBatch = claimAndGetBatch(id = 301L, limit = 10)
        assertTrue(claimedBatch.none { it.hlc == hlcs[0].toString() })
    }

    @Test
    fun should_deleteSuccessRecord_when_olderThanCutoff() = runEnv {
        // Given
        val hlc = TestHlcFactory.create()
        val cutoffTime = TestHlcFactory.BASE_TEST_TIME
        val targetBlobId = "blob-to-delete"

        val oldSuccess = createTestIntentEntity(
            hlc = hlc,
            status = SyncStatus.SUCCESS,
            overflowBlobId = targetBlobId,
            createdAt = cutoffTime - 1000L // 1 second older than cutoff
        )

        upsert(oldSuccess)

        // When
        val rowsDeleted = pruneByCutOff(cutoffTime, limit = 10)

        // Then
        assertEquals(1, rowsDeleted)
        assertFalse(existsForBlobId(targetBlobId))
    }

    @Test
    fun should_keepSuccessRecord_when_newerThanCutoff() = runEnv {
        // Given
        val hlc = TestHlcFactory.create()
        val cutoffTime = TestHlcFactory.BASE_TEST_TIME
        val targetBlobId = "blob-to-keep"

        val recentSuccess = createTestIntentEntity(
            hlc = hlc,
            status = SyncStatus.SUCCESS,
            overflowBlobId = targetBlobId,
            createdAt = cutoffTime + 1000L // 1 second newer than cutoff
        )

        upsert(recentSuccess)

        // When
        val rowsDeleted = pruneByCutOff(cutoffTime, limit = 10)

        // Then
        assertEquals(0, rowsDeleted)
        assertTrue(existsForBlobId(targetBlobId))
    }

    @Test
    fun should_keepPendingRecord_when_olderThanCutoff() = runEnv {
        // Given
        val hlc = TestHlcFactory.create()
        val cutoffTime = TestHlcFactory.BASE_TEST_TIME
        val targetBlobId = "blob-pending-safety"

        val oldPending = createTestIntentEntity(
            hlc = hlc,
            status = SyncStatus.PENDING,
            overflowBlobId = targetBlobId,
            createdAt = cutoffTime - 1000L // Chronologically old, but un-synced
        )

        upsert(oldPending)

        // When
        val rowsDeleted = pruneByCutOff(cutoffTime, limit = 10)

        // Then
        assertEquals(0, rowsDeleted)
        assertTrue(existsForBlobId(targetBlobId))
    }

}