package com.mochame.sync.infrastructure.stores

import com.mochame.support.MochaPlatformTest
import com.mochame.support.runDatabaseEnvironment
import com.mochame.sync.data.QuarantinedPayloadEntity
import com.mochame.sync.data.SyncMicroSchema
import com.mochame.sync.data.SyncMicroSchemaConstructor
import com.mochame.sync.di.infrastructure.QuarantinedPayloadStoreTestModule
import com.mochame.sync.di.infrastructure.QuarantinedPayloadTestEnv
import com.mochame.utils.fixtures.TestPayloads
import kotlinx.coroutines.test.TestScope
import org.koin.plugin.module.dsl.modules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant


// -----------------------------------------------------------
// SUT ENVIRONMENT
// -----------------------------------------------------------
private inline fun runEnv(crossinline block: suspend QuarantinedPayloadTestEnv.(TestScope) -> Unit) =
    runDatabaseEnvironment<SyncMicroSchema, QuarantinedPayloadTestEnv>(
        constructor = SyncMicroSchemaConstructor,
        koinSetup = { modules(QuarantinedPayloadStoreTestModule::class) },
        block = block
    )


internal class DefaultQuarantinedPayloadStoreTest : MochaPlatformTest() {

    // -----------------------------------------------------------
    // INBOUND / OUTBOUND MAPPING
    // -----------------------------------------------------------
    @Test
    fun should_preserveExactDataFields_when_mappingToEntityAndBackToDomain() = runEnv {
        // Given
        fakeTimeUtils.setTime(Instant.fromEpochMilliseconds(1_000_000L))
        val watermark = 42L
        val rawPayload = TestPayloads.DEFAULT
        val failureReason = "SCHEMA_DESERIALIZATION_FAILURE"

        // When
        quarantinedPayloadStore.record(
            watermark = watermark,
            rawPayload = rawPayload,
            failureReason = failureReason
        )
        val retrieved = quarantinedPayloadStore.getByWatermark(watermark)

        // Then
        assertNotNull(retrieved)
        assertEquals(watermark, retrieved.watermark)
        assertEquals(failureReason, retrieved.failureReason)
        assertEquals(1_000_000L, retrieved.receivedAt)
        assertTrue(rawPayload.contentEquals(retrieved.rawPayload))
    }

    // -----------------------------------------------------------
    // COLLECTIONS / MAPPING
    // -----------------------------------------------------------
    @Test
    fun should_maintainCollectionSizeAndOrdering_when_retrievingAll() = runEnv {
        // Given - Out of order seeding via DAO to isolate store sorting and domain mapping
        quarantinedPayloadDao.insert(
            QuarantinedPayloadEntity(
                watermark = 300L,
                rawPayload = TestPayloads.DEFAULT,
                failureReason = "Failure 300",
                receivedAt = 3000L
            )
        )
        quarantinedPayloadDao.insert(
            QuarantinedPayloadEntity(
                watermark = 100L,
                rawPayload = TestPayloads.DEFAULT,
                failureReason = "Failure 100",
                receivedAt = 1000L
            )
        )
        quarantinedPayloadDao.insert(
            QuarantinedPayloadEntity(
                watermark = 200L,
                rawPayload = TestPayloads.DEFAULT,
                failureReason = "Failure 200",
                receivedAt = 2000L
            )
        )

        // When
        val allPayloads = quarantinedPayloadStore.getAll()

        // Then
        assertEquals(3, allPayloads.size)
        assertEquals(100L, allPayloads[0].watermark)
        assertEquals(200L, allPayloads[1].watermark)
        assertEquals(300L, allPayloads[2].watermark)

        assertEquals("Failure 100", allPayloads[0].failureReason)
        assertEquals("Failure 200", allPayloads[1].failureReason)
        assertEquals("Failure 300", allPayloads[2].failureReason)
    }

}