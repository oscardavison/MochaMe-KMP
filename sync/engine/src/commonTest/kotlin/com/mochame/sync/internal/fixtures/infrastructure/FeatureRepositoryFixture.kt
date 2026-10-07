package com.mochame.sync.internal.fixtures.infrastructure

import co.touchlab.kermit.Logger
import com.mochame.logger.LogTags
import com.mochame.logger.withTags
import com.mochame.sync.api.codec.CodecResolver
import com.mochame.sync.api.codec.FeatureCodec
import com.mochame.sync.api.metadata.FeatureContext
import com.mochame.sync.domain.crdt.CrdtReconciler
import com.mochame.sync.domain.infrastructure.LocalFirstEngine
import com.mochame.sync.domain.model.DecodeContext
import com.mochame.sync.infrastructure.adaptor.SyncAdaptorBridge
import com.mochame.sync.internal.fixtures.serialization.FeatureEntity
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock

internal class FeatureRepositoryFixture(
    val featureContext: FeatureContext,
    val engine: LocalFirstEngine,
    val codec: CodecResolver<FeatureEntity, FeatureCodec<FeatureEntity>>,
    val reconciler: CrdtReconciler,
    logger: Logger
) {
    private val lock = reentrantLock()
    private val memoryStore = mutableMapOf<Long, FeatureEntity>()

    val bridge = SyncAdaptorBridge(
        featureContext = featureContext,
        codec = codec,
        engine = engine,
        reconciler = reconciler,
        fetchById = { fetchById(it) },
        save = { save(it) },
        logger = logger.withTags(LogTags.Layer.ORCH, LogTags.Domain.SYNC, "FeaRep")
    )

    val storedEntities: Map<Long, FeatureEntity>
        get() = lock.withLock { memoryStore.toMap() }

    fun seed(entity: FeatureEntity) = lock.withLock { memoryStore[entity.id] = entity }
    fun clear() = lock.withLock { memoryStore.clear() }

    suspend fun upsert(
        candidateKey: Long,
        computeChange: suspend (FeatureEntity?) -> FeatureEntity
    ): Long = bridge.upsert(candidateKey, computeChange)

    suspend fun delete(candidateKey: Long): Long = bridge.delete(candidateKey)

    suspend fun processRemoteIntent(context: DecodeContext, payload: ByteArray?) =
        bridge.processRemoteIntent(context, payload)

    fun fetchById(id: Long): FeatureEntity? =
        lock.withLock { memoryStore[id] }

    fun save(entity: FeatureEntity): Long = lock.withLock {
        memoryStore[entity.id] = entity
        entity.id
    }
}