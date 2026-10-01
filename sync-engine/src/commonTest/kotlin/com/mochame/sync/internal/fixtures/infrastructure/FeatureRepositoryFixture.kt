package com.mochame.sync.internal.fixtures.infrastructure

import co.touchlab.kermit.Logger
import com.mochame.logger.LogTags
import com.mochame.logger.withTags
import com.mochame.sync.api.metadata.FeatureContext
import com.mochame.sync.api.repository.LocalFirstDependencies
import com.mochame.sync.api.repository.LocalFirstRepository
import com.mochame.sync.internal.fixtures.serialization.FeatureEntity
import com.mochame.sync.spi.infrastructure.serialization.BaseFeatureCodecRouter
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock

internal class FeatureRepositoryFixture(
    featureContext: FeatureContext,
    deps: LocalFirstDependencies,
    codecRouter: BaseFeatureCodecRouter<FeatureEntity>,
    logger: Logger
) : LocalFirstRepository<FeatureEntity>(
    featureContext = featureContext,
    deps = deps,
    codec = codecRouter,
    logger = logger.withTags(LogTags.Layer.ORCH, LogTags.Domain.SYNC, "FeaRep")
) {
    private val lock = reentrantLock()

    // --- Test Utils ---
    private val memoryStore = mutableMapOf<Long, FeatureEntity>()

    val storedEntities: Map<Long, FeatureEntity>
        get() = lock.withLock { memoryStore.toMap() }

    fun seed(entity: FeatureEntity) = lock.withLock { memoryStore[entity.id] = entity }
    fun clear() = lock.withLock { memoryStore.clear() }

    // --- Feature Implementation ---
    suspend fun upsert(
        candidateKey: Long,
        computeChange: suspend (FeatureEntity?) -> FeatureEntity
    ): Long =
        localUpsert(candidateKey = candidateKey) { existing -> computeChange(existing) }

    suspend fun delete(candidateKey: Long): Long = localDelete(candidateKey)

    override suspend fun fetch(id: Long): FeatureEntity? =
        lock.withLock { memoryStore[id] }

    override suspend fun save(entity: FeatureEntity): Long = lock.withLock {
        memoryStore[entity.id] = entity
        entity.id
    }

    override suspend fun compactState(
        newState: FeatureEntity,
        existing: FeatureEntity?
    ): FeatureEntity = newState
}