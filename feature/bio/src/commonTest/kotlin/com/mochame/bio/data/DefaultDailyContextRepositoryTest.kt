package com.mochame.bio.data

import app.cash.turbine.test
import com.mochame.bio.di.BioInfraTestModule
import com.mochame.bio.di.BioTestEnv
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
    koinSetup = { modules(BioInfraTestModule::class) },
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
    fun shouldPersistAndRoundtripLosslessTelemetry() = runEnv {
        // August 27, 2026 (00:00:00 UTC)
        val context = getTestContext()
        val rowId = contextRepo.upsertContext(context)
        // 4am rule
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
    fun shouldIndexByEpochDayAndSaveRecord() = runEnv {
        val context = getTestContext()

        contextRepo.upsertContext(context)

        val entity = contextDao.getActiveContextById(context.id)
        assertNotNull(entity)
        assertEquals(context.id, entity.id)
        assertEquals(false, entity.isDeleted)
    }

    @Test
    fun shouldMergeAndUnsetFieldLevelMutationsWithoutClobbering() = runEnv {
        val originalContext = getTestContext()

        contextRepo.upsertContext(originalContext)

        val initialEntity = contextDao.getActiveContextById(originalContext.id)
        assertNotNull(initialEntity)
        val initialCreatedAt = initialEntity.createdAt

        contextRepo.upsertContext(originalContext.copy(sleepHours = null, readinessScore = 75))

        val updatedFetched = contextDao.getActiveContextById(originalContext.id)
        assertNotNull(updatedFetched)
        assertEquals(null, updatedFetched.sleepHours)
        assertEquals(75, updatedFetched.readinessScore)
        assertEquals(true, updatedFetched.isNapped)
        assertEquals(initialCreatedAt, updatedFetched.createdAt)
    }

    @Test
    fun shouldSuppressRedundantWrites_on_noopDelta() = runEnv {
        val context = getTestContext()
        contextRepo.upsertContext(context)

        val secondResult = contextRepo.upsertContext(context)

        // Assert: Skipped
        assertEquals(0L, secondResult)
    }

    // -----------------------------------------------------------
    // DELETION PIPELINE
    // -----------------------------------------------------------

    @Test
    fun shouldSetIsDeleted_on_softDeletionFlow() = runEnv {
        val context = getTestContext()

        contextRepo.upsertContext(context)
        val deleteResult = contextRepo.softDeleteContext(context.id)
        assertTrue(deleteResult != 0L)

        // Assert
        val entity = contextDao.getContextById(context.id)
        assertNotNull(entity)
        assertTrue(entity.isDeleted)
    }

    @Test
    fun shouldOmitDeletedRecords_on_uiInvalidationOnDelete() = runEnv {
        val context = getTestContext()

        contextRepo.upsertContext(context)

        contextRepo.observeContext(context.id).test {
            val initial = awaitItem()
            assertNotNull(initial)
            assertFalse(initial.isDeleted)

            contextRepo.softDeleteContext(context.id)

            val deletedEmission = awaitItem()
            assertNull(deletedEmission)
        }
    }

}