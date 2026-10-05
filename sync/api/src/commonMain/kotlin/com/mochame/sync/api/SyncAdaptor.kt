package com.mochame.sync.api

import com.mochame.sync.api.metadata.FeatureContext
import com.mochame.sync.api.models.LocalFirstEntity
import com.mochame.sync.spi.infrastructure.serialization.FeatureCodec
import com.mochame.sync.spi.infrastructure.serialization.FeatureCodecResolver

interface SyncAdaptor<T : LocalFirstEntity<T>> {
    suspend fun upsert(candidateKey: Long, computeChange: suspend (existing: T?) -> T): Long
    suspend fun delete(candidateKey: Long, computeChange: (suspend (existing: T?) -> T)? = null): Long
}

interface SyncAdaptorFactory {
    operator fun <T : LocalFirstEntity<T>> invoke(
        featureContext: FeatureContext,
        codec: FeatureCodecResolver<T, FeatureCodec<T>>,
        fetchById: suspend (id: Long) -> T?,
        save: suspend (entity: T) -> Long
    ): SyncAdaptor<T>
}