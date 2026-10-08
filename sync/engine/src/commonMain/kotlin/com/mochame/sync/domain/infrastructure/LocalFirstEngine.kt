package com.mochame.sync.domain.infrastructure

import com.mochame.sync.api.metadata.FeatureContext
import com.mochame.sync.api.metadata.MutationOp
import com.mochame.sync.api.models.LocalFirstEntity
import com.mochame.sync.domain.crdt.CrdtReconciler
import com.mochame.sync.api.codec.CodecResolver
import com.mochame.sync.api.codec.FeatureCodec
import com.mochame.sync.domain.model.DecodeContext

/**
 * Engine contract handling local-first data mutation pipelines, HLC stamping,
 * transactional commits, blob staging, and intent recording.
 */
internal interface LocalFirstEngine {

    /**
     * Processes a local mutation intent, coordinating concurrency locking,
     * HLC timestamp assignment, state persistence, and sync intent logging.
     *
     * @param featureContext Target feature domain context.
     * @param reconciler CRDT mediator handling field diffing, stamping, and wire encoding.
     * @param candidateKey Primary identifier key of the entity being mutated.
     * @param op Mutation operation type (UPSERT or DELETE).
     * @param fetchExistingState Lambda fetching current persisted entity state.
     * @param computeChange Lambda returning desired target entity state.
     * @param save Lambda writing the updated entity to local storage.
     * @param onSkip Lambda handling early-exit skip scenarios (returns fallback result).
     * @return Result identifier or operation outcome code.
     */
    suspend fun <T : LocalFirstEntity<T>> processLocalIntent(
        featureContext: FeatureContext,
        codecResolver: CodecResolver<T, FeatureCodec<T>>,
        reconciler: CrdtReconciler,
        candidateKey: Long,
        op: MutationOp,
        fetchExistingState: suspend (id: Long) -> T?,
        computeChange: suspend (existing: T?) -> T,
        save: suspend (stamped: T) -> Long,
        onSkip: (fallback: T?) -> Long
    ): Long

    /**
     * Processes an inbound remote intent payload, executing decoding, state merging,
     * and local atomic commit.
     *
     * @param featureContext Target feature domain context.
     * @param reconciler CRDT mediator handling delta deserialization and LWW merge resolution.
     * @param decodeContext Remote decode context containing candidate key, HLC, and operation.
     * @param payload Raw binary payload received from remote transport.
     * @param fetchExistingState Lambda fetching current persisted entity state.
     * @param save Lambda writing the updated entity to local storage.
     */
    suspend fun <T : LocalFirstEntity<T>> processRemoteIntent(
        featureContext: FeatureContext,
        codecResolver: CodecResolver<T, FeatureCodec<T>>,
        reconciler: CrdtReconciler,
        decodeContext: DecodeContext,
        payload: ByteArray?,
        fetchExistingState: suspend (id: Long) -> T?,
        save: suspend (entity: T) -> Long
    )
}