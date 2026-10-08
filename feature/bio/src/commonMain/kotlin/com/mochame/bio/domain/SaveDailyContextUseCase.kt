package com.mochame.bio.domain

import com.mochame.utils.runCatchingCancellable
import com.mochame.utils.ui.Update
import com.mochame.utils.ui.resolve
import org.koin.core.annotation.Factory

@Factory
class SaveDailyContextUseCase(
    private val repository: DailyContextRepository
) {
    suspend operator fun invoke(
        epochDay: Long,
        sleepHours: Update<Double> = Update.Unchanged,
        readinessScore: Update<Int> = Update.Unchanged,
        notes: Update<String> = Update.Unchanged,
        isNapped: Update<Boolean> = Update.Unchanged
    ): Result<Unit> = runCatchingCancellable {
        val existing = repository.getContextById(epochDay)

        val updatedEntity = DailyContext(
            id = epochDay,
            sleepHours = sleepHours.resolve(existing?.sleepHours),
            readinessScore = readinessScore.resolve(existing?.readinessScore),
            notes = notes.resolve(existing?.notes),
            isNapped = isNapped.resolve(existing?.isNapped)
        )

        repository.upsertContext(updatedEntity)
    }
}
