package com.mochame.bio.data

import co.touchlab.kermit.Logger
import com.mochame.bio.domain.DailyContext
import com.mochame.bio.infrastructure.DailyContextCodecRouter
import com.mochame.bio.domain.DailyContextRepository
import com.mochame.logger.LogTags
import com.mochame.logger.withTags
import com.mochame.sync.api.metadata.FeatureContext
import com.mochame.sync.api.repository.LocalFirstDependencies
import com.mochame.sync.api.repository.LocalFirstRepository
import com.mochame.sync.spi.infrastructure.SyncReceiver
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.koin.core.annotation.Single


@Single([DailyContextRepository::class, SyncReceiver::class])
class DefaultDailyContextRepository(
    private val dailyContextDao: DailyContextDao,
    codecRouter: DailyContextCodecRouter,
    logger: Logger,
    deps: LocalFirstDependencies
) : LocalFirstRepository<DailyContext>(
    FeatureContext.BIO_DAILY_CONTEXT,
    deps,
    codecRouter,
    logger = logger.withTags(LogTags.Layer.REPO, LogTags.Domain.BIO, "BioRep")
), DailyContextRepository {

    override suspend fun upsertContext(context: DailyContext) =
        localUpsert(context.id) { existing ->
            compactState(context, existing)
        }

    override suspend fun softDeleteContext(epochDay: Long) = localDelete(candidateKey = epochDay)

    override fun observeContext(epochDay: Long): Flow<DailyContext?> =
        dailyContextDao.observeContext(epochDay).map { it?.toDomain() }

    override suspend fun getContext(epochDay: Long): DailyContext? =
        dailyContextDao.getActiveContextById(epochDay)?.toDomain()


    // --- MAINTENANCE / SYNC ---
    override suspend fun hardDeleteContexts(cutoff: Long) =
        dailyContextDao.hardDeletePruning(cutoff)

    override suspend fun countSoftDeleted() = dailyContextDao.countSoftDeleted()
    override suspend fun fetchAny(id: Long) = dailyContextDao.getAnyContextById(id)?.toDomain()
    override suspend fun save(entity: DailyContext) = dailyContextDao.upsert(entity.toEntity())
    override suspend fun compactState(
        newState: DailyContext,
        existing: DailyContext?
    ): DailyContext = existing?.copy(
        sleepHours = newState.sleepHours,
        readinessScore = newState.readinessScore,
        isNapped = newState.isNapped,
        notes = newState.notes,
        lastModified = newState.lastModified,
        isDeleted = newState.isDeleted
    ) ?: DailyContext(
        id = newState.id,
        sleepHours = newState.sleepHours,
        readinessScore = newState.readinessScore,
        isNapped = newState.isNapped,
        notes = newState.notes,
        lastModified = newState.lastModified,
        createdAt = newState.createdAt
    )
}

