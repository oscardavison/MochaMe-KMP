package com.mochame.bio.data

import app.cash.turbine.test
import com.mochame.bio.di.BioTestEnv
import com.mochame.bio.di.DailyContextDataTestModule
import com.mochame.bio.domain.DailyContext
import com.mochame.support.MochaPlatformTest
import com.mochame.support.runDatabaseEnvironment
import kotlinx.coroutines.test.TestScope
import org.koin.plugin.module.dsl.modules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private inline fun runEnv(
    crossinline block: suspend BioTestEnv.(TestScope) -> Unit
) = runDatabaseEnvironment<BioMicroSchema, BioTestEnv>(
    constructor = BioMicroSchemaConstructor,
    koinSetup = { modules(DailyContextDataTestModule::class) },
    block = { testScope ->
        block(testScope)
    }
)

/**
 * ID - Defaults to August 27, 2026 (00:00:00 UTC).
 * sleepHours - Defaults to 8.5
 * readinessScore - Defaults to 90
 * isNapped - Defaults to true
 */
private fun BioTestEnv.getTestContext(epochDay: Long? = null) = DailyContext(
    id = epochDay ?: fakeClock.wind(),
    sleepHours = 8.5,
    readinessScore = 90,
    isNapped = true
)


class DefaultDailyContextRepositoryTest : MochaPlatformTest() {

    // -----------------------------------------------------------
    // UPSERT PIPELINE
    // -----------------------------------------------------------

    @Test
    fun should_persistAndRoundtripLosslessTelemetry_when_upsertingContext() = runEnv {
        // Given
        val context = getTestContext()

        // When
        val rowId = contextRepo.upsertContext(context)

        // Then
        assertEquals(context.id, rowId)
        val fetched = contextDao.getActiveContextById(rowId)
        assertNotNull(fetched)
        assertEquals(context.id, fetched.id)
        assertEquals(8.5, fetched.sleepHours)
        assertEquals(90, fetched.readinessScore)
        assertEquals(true, fetched.isNapped)
        assertNotNull(fetched.hlc)
    }

    @Test
    fun should_indexByEpochDayAndSaveRecord_when_upsertingContext() = runEnv {
        // Given
        val context = getTestContext()

        // When
        contextRepo.upsertContext(context)

        // Then
        val entity = contextDao.getActiveContextById(context.id)
        assertNotNull(entity)
        assertEquals(context.id, entity.id)
        assertEquals(false, entity.isDeleted)
    }

    @Test
    fun should_mergeAndUnsetFieldLevelMutationsWithoutClobbering_when_fieldsAreUpdated() = runEnv {
        // Given
        val originalContext = getTestContext()
        contextRepo.upsertContext(originalContext)
        val initialEntity = contextDao.getActiveContextById(originalContext.id)
        assertNotNull(initialEntity)
        val initialCreatedAt = initialEntity.createdAt

        // When
        contextRepo.upsertContext(originalContext.copy(sleepHours = null, readinessScore = 75))

        // Then
        val updatedFetched = contextDao.getActiveContextById(originalContext.id)
        assertNotNull(updatedFetched)
        assertEquals(null, updatedFetched.sleepHours)
        assertEquals(75, updatedFetched.readinessScore)
        assertEquals(true, updatedFetched.isNapped)
        assertEquals(initialCreatedAt, updatedFetched.createdAt)
    }

    @Test
    fun should_suppressRedundantWrites_on_noopDelta() = runEnv {
        // Given
        val context = getTestContext()
        contextRepo.upsertContext(context)

        // When
        val secondResult = contextRepo.upsertContext(context)

        // Then
        assertEquals(0L, secondResult)
    }

    // -----------------------------------------------------------
    // DELETION PIPELINE
    // -----------------------------------------------------------

    @Test
    fun should_setIsDeleted_on_softDeletionFlow() = runEnv {
        // Given
        val context = getTestContext()
        contextRepo.upsertContext(context)

        // When
        val deleteResult = contextRepo.softDeleteContext(context.id)

        // Then
        assertTrue(deleteResult != 0L)
        val entity = contextDao.getContextById(context.id)
        assertNotNull(entity)
        assertTrue(entity.isDeleted)
    }

    @Test
    fun should_omitDeletedRecords_on_uiInvalidationOnDelete() = runEnv {
        // Given
        val context = getTestContext()
        contextRepo.upsertContext(context)

        contextRepo.observeContext(context.id).test {
            val initial = awaitItem()
            assertNotNull(initial)
            assertFalse(initial.isDeleted)

            // When
            contextRepo.softDeleteContext(context.id)

            // Then
            val deletedEmission = awaitItem()
            assertNull(deletedEmission)
        }
    }
}