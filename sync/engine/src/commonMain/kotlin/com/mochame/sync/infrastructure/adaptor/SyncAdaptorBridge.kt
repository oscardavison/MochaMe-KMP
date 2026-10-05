package com.mochame.sync.infrastructure.adaptor

import co.touchlab.kermit.Logger
import com.mochame.sync.api.SyncAdaptor
import com.mochame.sync.api.metadata.FeatureContext
import com.mochame.sync.api.metadata.MutationOp
import com.mochame.sync.api.models.LocalFirstEntity
import com.mochame.sync.domain.crdt.CrdtIntentResolver
import com.mochame.sync.domain.infrastructure.LocalFirstEngine
import com.mochame.sync.spi.infrastructure.SyncReceiver
import com.mochame.sync.spi.models.DecodeContext

/**
 * Adapts feature domain operations to the underlying [LocalFirstEngine].
 *
 * Bridges local [upsert] and [delete] requests into the local intent pipeline,
 * and routes incoming sync frames via [SyncReceiver] into the remote intent pipeline.
 *
 * @param featureContext Domain namespace of the target feature.
 * @param engine Local-first coordinator managing locking, clock, and persistence transactions.
 * @param resolver Mediates CRDT resolution, domain diffing, and wire delta serialization.
 * @param fetchAny Persistence fetcher for local records.
 * @param save Persistence writer for updated records.
 * @param logger Diagnostics and event logger.
 */
internal class SyncAdaptorBridge<T : LocalFirstEntity<T>>(
    override val featureContext: FeatureContext,
    private val engine: LocalFirstEngine,
    private val resolver: CrdtIntentResolver,
    private val fetchAny: suspend (id: Long) -> T?,
    private val save: suspend (entity: T) -> Long,
    private val logger: Logger
) : SyncAdaptor<T>, SyncReceiver {

    override suspend fun upsert(
        candidateKey: Long,
        computeChange: suspend (existing: T?) -> T
    ): Long = engine.processLocalIntent(
        featureContext = featureContext,
        resolver = resolver,
        candidateKey = candidateKey,
        op = MutationOp.UPSERT,
        fetchExistingState = fetchAny,
        computeChange = computeChange,
        persist = save,
        onSkip = {
            logger.v { "Skipping Upsert [Context:$featureContext, ID:$candidateKey]" }
            0L
        }
    )

    override suspend fun delete(
        candidateKey: Long,
        computeChange: (suspend (existing: T?) -> T)?
    ): Long = engine.processLocalIntent(
        featureContext = featureContext,
        resolver = resolver,
        candidateKey = candidateKey,
        op = MutationOp.DELETE,
        fetchExistingState = fetchAny,
        computeChange = computeChange ?: { existing ->
            requireNotNull(existing) { "Cannot delete non-existent entity with ID: $candidateKey" }
            existing.withDeleteState(true)
        },
        persist = save,
        onSkip = {
            logger.v { "Skipping Delete [Context:$featureContext, ID:$candidateKey]" }
            0L
        }
    )

    override suspend fun processRemoteIntent(
        context: DecodeContext,
        payload: ByteArray?
    ) {
        engine.processRemoteIntent(
            featureContext = featureContext,
            resolver = resolver,
            decodeContext = context,
            payload = payload,
            fetchExistingState = fetchAny,
            save = save
        )
    }
}