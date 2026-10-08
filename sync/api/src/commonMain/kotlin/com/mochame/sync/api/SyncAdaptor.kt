package com.mochame.sync.api

import com.mochame.sync.api.metadata.FeatureContext
import com.mochame.sync.api.models.LocalFirstEntity
import com.mochame.sync.api.codec.FeatureCodec
import com.mochame.sync.api.codec.CodecResolver

/**
 * Adaptor for feature database actions to delegate synchronization metadata, transport handling,
 * and conflict resolution to the local-first sync system.
 */
interface SyncAdaptor<T : LocalFirstEntity<T>> {
    /**
     * Inserts or updates an entity, tracking modified fields for synchronization.
     *
     * @param candidateKey Local primary key.
     * @param computeChange Produces the entity to persist, comparing against any existing state.
     * @return The persisted primary key (once metadata is handled).
     */
    suspend fun upsert(candidateKey: Long, computeChange: suspend (existing: T?) -> T): Long
    /**
     * Marks an entity as deleted.
     *
     * @param candidateKey The primary key to delete.
     * @param computeChange Optional mutation block. Defaults to calling [LocalFirstEntity.withDeleteState], providing true.
     * @return The updated primary key.
     */
    suspend fun delete(candidateKey: Long, computeChange: (suspend (existing: T?) -> T)? = null): Long
}

/**
 * Creates [SyncAdaptor] instances wired to a feature's local persistence environment and codecs.
 */
interface SyncAdaptorFactory {
    operator fun <T : LocalFirstEntity<T>> invoke(
        featureContext: FeatureContext,
        codec: CodecResolver<T, FeatureCodec<T>>,
        fetchById: suspend (id: Long) -> T?,
        save: suspend (entity: T) -> Long
    ): SyncAdaptor<T>
}