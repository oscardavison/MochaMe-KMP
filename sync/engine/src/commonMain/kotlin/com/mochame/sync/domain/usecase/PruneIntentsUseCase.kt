package com.mochame.sync.domain.usecase

import co.touchlab.kermit.Logger
import com.mochame.utils.interfaces.TimeUtils
import com.mochame.sync.domain.stores.SyncIntentMaintenanceStore
import com.mochame.logger.LogTags
import com.mochame.logger.withTags
import com.mochame.logger.withTimer
import kotlinx.coroutines.yield
import org.koin.core.annotation.Single
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.TimeSource

/**
 * On invocation, pruning will chunk based on a [DEFAULT_LIMIT], yielding between
 * chunks. The cut-off is based on the provided [pruneDays] which defaults to
 * [DEFAULT_PRUNE_DAYS].
 *
 * The method responsible for the pruning process is [com.mochame.sync.data.SyncIntentDao.pruneByCutOff] -
 * defining that the cut-off is based on the [com.mochame.sync.domain.model.SyncIntent.createdAt] field, requiring
 * a status representing synchronization success provided by the server.
 */
@Single
internal class PruneIntentsUseCase(
    private val intentStore: SyncIntentMaintenanceStore,
    private val timeUtils: TimeUtils,
    private val pruneDays: Duration = DEFAULT_PRUNE_DAYS,
    private val limit: Int = DEFAULT_LIMIT,
    logger: Logger
) {
    companion object {
        private val DEFAULT_PRUNE_DAYS = 30.days
        private const val DEFAULT_LIMIT = 100
        private const val LOG_INTERVAL = 50
    }

    private val logger = logger.withTags(
        layer = LogTags.Layer.DOMAIN,
        domain = LogTags.Domain.SYNC,
        className = "MsPrun"
    )

    suspend operator fun invoke(): Int {
        val mark = TimeSource.Monotonic.markNow()
        var totalDeleted = 0
        var iterations = 0

        do {
            val deleted =
                intentStore.pruneAgedIntents(timeUtils.getMillisAgo(pruneDays), limit)
            totalDeleted += deleted
            iterations++

            if (iterations % LOG_INTERVAL == 0) {
                logger.v { "Pruning in progress... $totalDeleted entries removed." }
            }

            if (deleted < limit) break
            if (deleted > 0) yield()

        } while (deleted > 0)

        if (totalDeleted > 0) {
            logger.i {
                "Prune Complete | Total: $totalDeleted | Chunks: $iterations"
                    .withTimer(mark)
            }
        }

        return totalDeleted
    }
}