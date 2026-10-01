package com.mochame.bio.data

import app.cash.turbine.test
import com.mochame.bio.di.BioInfraTestModule
import com.mochame.bio.di.BioTestEnv
import com.mochame.bio.domain.DailyContext
import com.mochame.bio.infrastructure.DailyContextCodecV1.Companion.TAG_IS_NAPPED
import com.mochame.bio.infrastructure.DailyContextCodecV1.Companion.TAG_READINESS_SCORE
import com.mochame.bio.infrastructure.DailyContextCodecV1.Companion.TAG_SLEEP_HOURS
import com.mochame.support.MochaPlatformTest
import com.mochame.support.runDatabaseEnvironment
import com.mochame.sync.api.boot.BootState
import com.mochame.sync.api.metadata.MutationOp
import com.mochame.sync.common.bitmaskOf
import com.mochame.sync.spi.infrastructure.serialization.BaseFeatureCodec.Companion.TAG_CREATED_AT
import com.mochame.sync.spi.infrastructure.serialization.BaseFeatureCodec.Companion.TAG_PRIMARY_KEY
import kotlinx.coroutines.test.TestScope
import org.koin.plugin.module.dsl.modules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private inline fun runEnv(
    readyUp: Boolean = true,
    crossinline block: suspend BioTestEnv.(TestScope) -> Unit
) = runDatabaseEnvironment<BioMicroSchema, BioTestEnv>(
    constructor = BioMicroSchemaConstructor,
    koinSetup = { modules(BioInfraTestModule::class) },
    block = { testScope ->
        if (readyUp) bootProvider.updateState(BootState.Ready)
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

        val fetched = contextDao.getContextById(rowId)
        assertNotNull(fetched)
        assertEquals(context.id, fetched.id)
        assertEquals(8.5, fetched.sleepHours)
        assertEquals(90, fetched.readinessScore)
        assertEquals(true, fetched.isNapped)
        assertNotNull(fetched.hlc)
    }

    @Test
    fun shouldIndexByEpochDayAndLinkToIntent() = runEnv {
        val context = getTestContext()

        contextRepo.upsertContext(context)

        val entity = contextDao.getContextById(context.id)
        assertNotNull(entity)
        assertEquals(context.id, entity.id)
        assertEquals(false, entity.isDeleted)

        val intents = intentStore.intents
        assertTrue(intents.isNotEmpty())
        val intent = intents.last()
        assertEquals(context.id, intent.candidateKey)
        assertEquals(MutationOp.UPSERT, intent.operation)
        assertEquals(
            intent.changedMask,
            bitmaskOf(
                TAG_PRIMARY_KEY,
                TAG_CREATED_AT,
                TAG_SLEEP_HOURS,
                TAG_READINESS_SCORE,
                TAG_IS_NAPPED
            )
        )
    }

    @Test
    fun shouldMergeAndUnsetFieldLevelMutationsWithoutClobbering() = runEnv { // no clobber
        val originalContext = getTestContext()

        contextRepo.upsertContext(originalContext)

        val initialEntity = contextDao.getContextById(originalContext.id)
        assertNotNull(initialEntity)
        val initialCreatedAt = initialEntity.createdAt

        contextRepo.upsertContext(originalContext.copy(sleepHours = null, readinessScore = 75))

        val updatedFetched = contextDao.getContextById(originalContext.id)
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

        val initialIntentCount = intentStore.intents.size

        val secondResult = contextRepo.upsertContext(context)

        // Assert: Skipped and no extra intent appended
        assertEquals(0L, secondResult)
        assertEquals(initialIntentCount, intentStore.intents.size)
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
        val entity = contextDao.getSoftDeletedContextById(context.id)
        assertNotNull(entity)
        assertTrue(entity.isDeleted)

        val deletionIntent = intentStore.intents.last()
        assertEquals(context.id, deletionIntent.candidateKey)
        assertEquals(MutationOp.DELETE, deletionIntent.operation)
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