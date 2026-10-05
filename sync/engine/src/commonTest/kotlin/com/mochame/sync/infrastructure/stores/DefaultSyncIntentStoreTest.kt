package com.mochame.sync.infrastructure.stores

import app.cash.turbine.test
import com.mochame.support.MochaPlatformTest
import com.mochame.utils.fixtures.TestHlcFactory
import com.mochame.support.runDatabaseEnvironment
import com.mochame.sync.api.metadata.FeatureContext
import com.mochame.sync.api.metadata.SyncStatus
import com.mochame.sync.data.SyncMicroSchema
import com.mochame.sync.data.SyncMicroSchemaConstructor
import com.mochame.sync.di.infrastructure.SyncIntentStoreTestModule
import com.mochame.sync.di.infrastructure.SyncIntentTestEnv
import com.mochame.sync.internal.fixtures.createTestIntentEntity
import com.mochame.sync.internal.fixtures.createTestSyncIntent
import com.mochame.utils.fixtures.TestPayloads
import kotlinx.coroutines.test.TestScope
import org.koin.plugin.module.dsl.modules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue


// -----------------------------------------------------------
// SUT ENVIRONMENT
// -----------------------------------------------------------
private inline fun runEnv(crossinline block: suspend SyncIntentTestEnv.(TestScope) -> Unit) =
    runDatabaseEnvironment<SyncMicroSchema, SyncIntentTestEnv>(
        constructor = SyncMicroSchemaConstructor,
        koinSetup = { modules(SyncIntentStoreTestModule::class) },
        block = block
    )


internal class DefaultSyncIntentStoreTest : MochaPlatformTest() {

    // -----------------------------------------------------------
    // INBOUND / OUTBOUND INTEGRITY
    // -----------------------------------------------------------
    @Test
    fun should_preserveExactDataFields_when_mappingToEntityAndBackToDomain() = runEnv {
        // Given
        val hlc = TestHlcFactory.create()
        val candidateKey = 1L

        val originalIntent = createTestSyncIntent(
            hlc = hlc,
            candidateKey = candidateKey,
            payload = TestPayloads.DEFAULT
        )

        // When
        intentStore.recordIntent(originalIntent)
        val retrievedIntent = intentStore.getPendingByCandidateKey(candidateKey)

        // Then
        assertNotNull(retrievedIntent)

        assertEquals(originalIntent.hlc, retrievedIntent.hlc)
        assertEquals(
            originalIntent.featureSchemaVersion,
            retrievedIntent.featureSchemaVersion
        )
        assertEquals(originalIntent.candidateKey, retrievedIntent.candidateKey)
        assertEquals(originalIntent.featureContext, retrievedIntent.featureContext)
        assertEquals(originalIntent.operation, retrievedIntent.operation)
        assertEquals(originalIntent.syncStatus, retrievedIntent.syncStatus)
        assertEquals(originalIntent.createdAt, retrievedIntent.createdAt)

        // Validate nullability preservation
        assertNull(retrievedIntent.batchId)
        assertNull(retrievedIntent.overflowBlobId)
        assertNull(retrievedIntent.leasedAt)
        assertNull(retrievedIntent.lastErrorMessage)
        assertNull(retrievedIntent.diagnosticSummary)

        // Verify payload byte consistency
        assertNotNull(retrievedIntent.payload)
        assertTrue(TestPayloads.DEFAULT.contentEquals(retrievedIntent.payload!!))
    }

    // -----------------------------------------------------------
    // COLLECTIONS / EMISSIONS
    // -----------------------------------------------------------
    @Test
    fun should_maintainCollectionSizeAndOrdering_when_retrievingClaimedBatch() = runEnv {
        // Given
        val hlcs = TestHlcFactory.chronologicalSequence(size = 3)

        // Unordered seeding of the database via the DAO to isolate the store's retrieval mapper
        intentDao.upsert(createTestIntentEntity(hlc = hlcs[2], candidateKey = 0L))
        intentDao.upsert(createTestIntentEntity(hlc = hlcs[0], candidateKey = 1L))
        intentDao.upsert(createTestIntentEntity(hlc = hlcs[1], candidateKey = 2L))

        // When
        val claimedBatch = intentStore.claimNextBatch(limit = 10)
        assertNotNull(claimedBatch)
        val claimedDomainBatch = claimedBatch.intents

        // Then
        assertEquals(3, claimedDomainBatch.size)

        // Confirm Chronology
        assertEquals(hlcs[0], claimedDomainBatch[0].hlc)
        assertEquals(hlcs[1], claimedDomainBatch[1].hlc)
        assertEquals(hlcs[2], claimedDomainBatch[2].hlc)

        assertEquals(1L, claimedDomainBatch[0].candidateKey)
        assertEquals(2L, claimedDomainBatch[1].candidateKey)
        assertEquals(0L, claimedDomainBatch[2].candidateKey)
    }

    @Test
    fun should_returnEmptyList_when_noPendingIntentsExistForModule() = runEnv {
        // Given
        val targetFeature = FeatureContext.UNRECOGNIZED_MODEL

        // When
        val result = intentStore.getPendingByFeature(targetFeature)

        // Then
        assertNotNull(result)
        assertTrue(result.isEmpty(), "Expected an empty list, but received elements!")
    }

    @Test
    fun should_emitUpdatedSummary_when_intentIsQuarantined() = runEnv {
        // Given
        val targetModule = FeatureContext.UNRECOGNIZED_MODEL
        val hlc = TestHlcFactory.create()

        // When
        intentStore.observeQuarantinedCountByModule().test {
            val initialItem = awaitItem()
            assertTrue(initialItem.isEmpty())

            val quarantinedEntity = createTestIntentEntity(
                hlc = hlc,
                status = SyncStatus.QUARANTINED
            )
            intentDao.upsert(quarantinedEntity)

            // Then
            val updatedList = awaitItem()
            assertEquals(1, updatedList.size)

            val summary = updatedList.first()
            assertEquals(targetModule, summary.featureContext)
            assertEquals(1, summary.count)

            cancelAndIgnoreRemainingEvents()
        }
    }

}

