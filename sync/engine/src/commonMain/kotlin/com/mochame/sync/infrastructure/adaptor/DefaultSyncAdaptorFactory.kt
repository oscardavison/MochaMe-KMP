package com.mochame.sync.infrastructure.adaptor

import co.touchlab.kermit.Logger
import com.mochame.sync.api.SyncAdaptor
import com.mochame.sync.api.SyncAdaptorFactory
import com.mochame.sync.api.metadata.FeatureContext
import com.mochame.sync.api.models.LocalFirstEntity
import com.mochame.sync.domain.crdt.CrdtReconciler
import com.mochame.sync.domain.infrastructure.LocalFirstEngine
import com.mochame.sync.api.codec.CodecResolver
import com.mochame.sync.api.codec.FeatureCodec
import org.koin.core.annotation.Single

@Single(binds = [SyncAdaptorFactory::class])
internal class DefaultSyncAdaptorFactory(
    private val engine: LocalFirstEngine,
    private val registry: SyncReceiverRegistry,
    private val resolver: CrdtReconciler,
    private val logger: Logger
) : SyncAdaptorFactory {

    override operator fun <T : LocalFirstEntity<T>> invoke(
        featureContext: FeatureContext,
        codec: CodecResolver<T, FeatureCodec<T>>,
        fetchById: suspend (id: Long) -> T?,
        save: suspend (entity: T) -> Long
    ): SyncAdaptor<T> {
        val bridge = SyncAdaptorBridge(
            featureContext = featureContext,
            codec = codec,
            resolver = resolver,
            engine = engine,
            fetchById = fetchById,
            save = save,
            logger = logger
        )
        registry.register(bridge)
        return bridge
    }
}