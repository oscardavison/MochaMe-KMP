package com.mochame.sync.data

import com.mochame.support.MochaPlatformTest
import com.mochame.support.runDatabaseEnvironment
import com.mochame.sync.di.data.SyncPersistenceTestModule
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
private inline fun runEnv(crossinline block: suspend QuarantinedPayloadDao.(TestScope) -> Unit) =
    runDatabaseEnvironment<SyncMicroSchema, QuarantinedPayloadDao>(
        constructor = SyncMicroSchemaConstructor,
        koinSetup = { modules(SyncPersistenceTestModule::class) },
        block = block,
    )


private fun createTestQuarantinedEntity(
    watermark: Long = 100L,
    rawPayload: ByteArray = TestPayloads.DEFAULT,
    failureReason: String = "Test failure reason",
    receivedAt: Long = 1000L
) = QuarantinedPayloadEntity(
    watermark = watermark,
    rawPayload = rawPayload,
    failureReason = failureReason,
    receivedAt = receivedAt
)


class QuarantinedPayloadDaoTest : MochaPlatformTest() {

    // -----------------------------------------------------------
    // INSERT & ON-CONFLICT RESOLUTION
    // -----------------------------------------------------------
    @Test
    fun should_insertPayloadEntity_when_watermarkIsUnique() = runEnv {
        // Given
        val entity = createTestQuarantinedEntity(
            watermark = 100L,
            rawPayload = TestPayloads.DEFAULT,
            failureReason = "Deserialization error",
            receivedAt = 5000L
        )

        // When
        insert(entity)

        // Then
        val retrieved = getByWatermark(100L)
        assertNotNull(retrieved)
        assertEquals(100L, retrieved.watermark)
        assertEquals("Deserialization error", retrieved.failureReason)
        assertEquals(5000L, retrieved.receivedAt)
        assertTrue(TestPayloads.DEFAULT.contentEquals(retrieved.rawPayload))
    }

    @Test
    fun should_replaceExistingEntity_when_insertingDuplicateWatermark() = runEnv {
        // Given
        val originalEntity = createTestQuarantinedEntity(
            watermark = 50L,
            failureReason = "Initial failure",
            receivedAt = 1000L
        )
        insert(originalEntity)

        val updatedPayload = "updated payload data".encodeToByteArray()
        val replacementEntity = createTestQuarantinedEntity(
            watermark = 50L,
            rawPayload = updatedPayload,
            failureReason = "Updated failure",
            receivedAt = 2000L
        )

        // When
        insert(replacementEntity)

        // Then
        val retrieved = getByWatermark(50L)
        assertNotNull(retrieved)
        assertEquals(50L, retrieved.watermark)
        assertEquals("Updated failure", retrieved.failureReason)
        assertEquals(2000L, retrieved.receivedAt)
        assertTrue(updatedPayload.contentEquals(retrieved.rawPayload))

        assertEquals(1, getAll().size)
    }

    // -----------------------------------------------------------
    // QUERYING & SORTING
    // -----------------------------------------------------------
    @Test
    fun should_getAllInAscendingWatermarkOrder_when_insertedOutSequence() = runEnv {
        // Given - Out of sequence insertion
        val entityLater = createTestQuarantinedEntity(watermark = 300L, failureReason = "F300")
        val entityEarlier = createTestQuarantinedEntity(watermark = 100L, failureReason = "F100")
        val entityMiddle = createTestQuarantinedEntity(watermark = 200L, failureReason = "F200")

        insert(entityLater)
        insert(entityEarlier)
        insert(entityMiddle)

        // When
        val results = getAll()

        // Then
        assertEquals(3, results.size)
        assertEquals(100L, results[0].watermark)
        assertEquals(200L, results[1].watermark)
        assertEquals(300L, results[2].watermark)

        assertEquals("F100", results[0].failureReason)
        assertEquals("F200", results[1].failureReason)
        assertEquals("F300", results[2].failureReason)
    }

    @Test
    fun should_returnNull_when_getByWatermarkNotFound() = runEnv {
        // Given empty table

        // When
        val result = getByWatermark(999L)

        // Then
        assertNull(result)
    }

    @Test
    fun should_returnEmptyList_when_noQuarantinedPayloadsExist() = runEnv {
        // Given empty table

        // When
        val result = getAll()

        // Then
        assertNotNull(result)
        assertTrue(result.isEmpty())
    }

    // -----------------------------------------------------------
    // DELETION & RETENTION / PRUNING
    // -----------------------------------------------------------
    @Test
    fun should_deletePayload_when_matchingWatermarkProvided() = runEnv {
        // Given
        insert(createTestQuarantinedEntity(watermark = 1L))
        insert(createTestQuarantinedEntity(watermark = 2L))

        // When
        deleteByWatermark(1L)

        // Then
        assertNull(getByWatermark(1L))
        assertNotNull(getByWatermark(2L))

        val remaining = getAll()
        assertEquals(1, remaining.size)
        assertEquals(2L, remaining.first().watermark)
    }

    @Test
    fun should_pruneOnlyRecordsReceivedBeforeCutoff_when_pruneOlderThanInvoked() = runEnv {
        // Given
        val entityOld = createTestQuarantinedEntity(watermark = 1L, receivedAt = 1000L)
        val entityBoundary = createTestQuarantinedEntity(watermark = 2L, receivedAt = 2000L)
        val entityNew = createTestQuarantinedEntity(watermark = 3L, receivedAt = 3000L)

        insert(entityOld)
        insert(entityBoundary)
        insert(entityNew)

        // When - Cutoff at 2000L (records with receivedAt < 2000L should be deleted)
        val deletedCount = pruneOlderThan(cutoff = 2000L)

        // Then
        assertEquals(1, deletedCount)

        val remaining = getAll()
        assertEquals(2, remaining.size)
        assertEquals(2L, remaining[0].watermark)
        assertEquals(3L, remaining[1].watermark)
    }

}