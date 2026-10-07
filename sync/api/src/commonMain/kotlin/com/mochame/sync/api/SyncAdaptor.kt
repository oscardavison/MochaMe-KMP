package com.mochame.sync.api

import com.mochame.sync.api.metadata.FeatureContext
import com.mochame.sync.api.models.LocalFirstEntity
import com.mochame.sync.api.codec.FeatureCodec
import com.mochame.sync.api.codec.CodecResolver

/**
 * Features define their query logic within the provided pipeline of [SyncAdaptorFactory].
 */
interface SyncAdaptor<T : LocalFirstEntity<T>> {
    suspend fun upsert(candidateKey: Long, computeChange: suspend (existing: T?) -> T): Long
    /**
     * Defaults computeChange to call [LocalFirstEntity.withDeleteState] passing true.
     * Intended behavior is for features to nullify their domain fields if isDeleted,
     * else copy their state and set isDeleted.
     */
    suspend fun delete(candidateKey: Long, computeChange: (suspend (existing: T?) -> T)? = null): Long
}

/**
 * Provides infrastructural dependencies to ensure the synchronization system
 * can act on both inbound and outbound intents for a given feature.
 */
interface SyncAdaptorFactory {
    operator fun <T : LocalFirstEntity<T>> invoke(
        featureContext: FeatureContext,
        codec: CodecResolver<T, FeatureCodec<T>>,
        fetchById: suspend (id: Long) -> T?,
        save: suspend (entity: T) -> Long
    ): SyncAdaptor<T>
}