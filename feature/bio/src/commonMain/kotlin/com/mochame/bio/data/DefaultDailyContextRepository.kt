package com.mochame.bio.data

import com.mochame.bio.domain.DailyContext
import com.mochame.bio.domain.DailyContextRepository
import com.mochame.sync.api.SyncAdaptor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.koin.core.annotation.Single

@Single(binds = [DailyContextRepository::class])
class DefaultDailyContextRepository(
    private val dailyContextDao: DailyContextDao,
    private val adaptor: SyncAdaptor<DailyContext>,
) : DailyContextRepository {

    override suspend fun upsertContext(context: DailyContext) =
        adaptor.upsert(context.id) { existing ->
            compactState(context, existing)
        }

    override suspend fun softDeleteContext(epochDay: Long) = adaptor.delete(candidateKey = epochDay)

    override fun observeContext(epochDay: Long): Flow<DailyContext?> =
        dailyContextDao.observeContext(epochDay).map { it?.toDomain() }

    override suspend fun getActiveContextById(epochDay: Long): DailyContext? =
        dailyContextDao.getActiveContextById(epochDay)?.toDomain()

    override suspend fun getContextById(epochDay: Long): DailyContext? =
        dailyContextDao.getContextById(epochDay)?.toDomain()

    // --- MAINTENANCE / SYNC ---
    override suspend fun hardDeleteContexts(cutoff: Long) =
        dailyContextDao.hardDeletePruning(cutoff)

    override suspend fun countSoftDeleted() = dailyContextDao.countSoftDeleted()

    fun compactState(
        newState: DailyContext,
        existing: DailyContext?
    ): DailyContext = existing?.copy(
        sleepHours = newState.sleepHours,
        readinessScore = newState.readinessScore,
        isNapped = newState.isNapped,
        notes = newState.notes,
        lastModified = newState.lastModified,
        isDeleted = newState.isDeleted
    ) ?: newState
}
