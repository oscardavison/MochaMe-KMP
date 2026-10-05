package com.mochame.sync.infrastructure.stores


import com.mochame.sync.api.models.HLC
import com.mochame.sync.api.metadata.FeatureContext
import com.mochame.sync.api.metadata.SyncStatus
import com.mochame.sync.data.SyncIntentDao
import com.mochame.sync.data.toDomain
import com.mochame.sync.data.toEntity
import com.mochame.sync.domain.stores.SyncIntentMaintenanceStore
import com.mochame.sync.domain.stores.SyncIntentStore
import com.mochame.sync.domain.model.ClaimedBatch
import com.mochame.sync.spi.models.QuarantinedFeatureSummary
import com.mochame.sync.domain.model.SyncIntent
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.koin.core.annotation.Single

/**
 * An implementation coupled to Jetpack Room, bridging the orchestration layer of synchronization logic
 * to the database infrastructure. This store covers the split domains - record maintenance and
 * direct recording logic.
 *
 * The store handles the model transitions from domain to data, expecting to receive domain
 * models and pass back domain models. Verifying the integrity of this component means
 * asserting the parity of that mapping logic, providing a seamless bridge between orchestration
 * and data persistence.
 */
@Single(binds = [SyncIntentStore::class, SyncIntentMaintenanceStore::class])
internal class DefaultSyncIntentStore(
    private val intentDao: SyncIntentDao,
) : SyncIntentStore, SyncIntentMaintenanceStore {

    private val batchCounter = atomic(-1L)
    private val initMutex = Mutex()

    private suspend fun ensureInitialized() {
        if (batchCounter.value >= 0) return
        initMutex.withLock {
            if (batchCounter.value < 0) {
                val maxExisting = intentDao.getMaxBatchId() ?: 0L
                batchCounter.value = maxExisting
            }
        }
    }

    override suspend fun getPendingByCandidateKey(candidateKey: Long) =
        intentDao.getPendingByKey(candidateKey)?.toDomain()

    override suspend fun getPendingByFeature(feature: FeatureContext): List<SyncIntent?> =
        intentDao.getPendingByFeature(feature.featureName).map { it.toDomain() }

    override suspend fun recordIntent(entry: SyncIntent) = intentDao.upsert(entry.toEntity())

    override suspend fun claimNextBatch(limit: Int): ClaimedBatch? {
        ensureInitialized()
        val nextBatchId = batchCounter.incrementAndGet()

        val entities = intentDao.claimAndGetBatch(
            id = nextBatchId,
            limit = limit
        )

        if (entities.isEmpty()) return null

        return ClaimedBatch(
            batchId = nextBatchId,
            intents = entities.map { it.toDomain() }
        )
    }

    override suspend fun acknowledgeSuccess(batchId: Long): Int =
        intentDao.updateBatchStatus(
            batchId = batchId,
            status = SyncStatus.SUCCESS,
            expectedCurrentStatus = SyncStatus.SYNCING
        )

    override suspend fun stampLastError(batchId: Long, message: String) =
        intentDao.stampLastError(batchId, message)

    // -----------------------------------------------------------
    // MAINTENANCE
    // -----------------------------------------------------------

    override suspend fun resetStaleLeases(
        cutOff: Long,
        retryThreshold: Int,
        shouldIncrementRetry: Boolean
    ) =
        intentDao.resetStaleLeases(cutOff, retryThreshold, shouldIncrementRetry)

    override suspend fun quarantineStaleLeases(cutOff: Long, retryThreshold: Int) =
        intentDao.quarantineStaleLeases(cutOff, retryThreshold)

    override suspend fun quarantineIntent(
        hlc: HLC,
        candidateKey: Long,
        errorMessage: String
    ) {
        intentDao.quarantineIntent(
            hlc = hlc.toString(),
            candidateKey = candidateKey,
            errorMessage = errorMessage
        )
    }

    override suspend fun releaseIntents(batchId: Long): Int = intentDao.releaseByBatch(batchId)

    override suspend fun releaseIntents(hlcs: List<HLC>): Int =
        intentDao.releaseByHlc(hlcs.map { it.toString() })

    override suspend fun cascadeQuarantine(): Int = intentDao.cascadeQuarantine()

    override suspend fun pruneAgedIntents(pruneAfter: Long, limit: Int) =
        intentDao.pruneByCutOff(
            cutoffMs = pruneAfter,
            limit = limit
        )

    override suspend fun observeQuarantinedCountByModule(): Flow<List<QuarantinedFeatureSummary>> =
        intentDao.observeQuarantinedCountByFeature()

    override suspend fun existsForBlob(blobId: String) =
        intentDao.existsForBlobId(blobId)

}