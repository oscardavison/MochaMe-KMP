package com.mochame.sync.api.repository

import com.mochame.sync.api.hlc.HLC
import com.mochame.sync.api.metadata.FeatureContext
import com.mochame.sync.api.metadata.MutationOp
import com.mochame.sync.api.models.LocalFirstEntity
import com.mochame.sync.spi.infrastructure.serialization.FeatureCodec
import com.mochame.sync.spi.infrastructure.serialization.FeatureCodecRouter
import com.mochame.sync.spi.models.DecodeContext

/**
 * Engine contract handling local-first data mutation pipelines, HLC stamping,
 * transactional commits, blob staging, and intent recording.
 */
interface LocalFirstEngine {
    /**
     * Processes a local or remote mutation intent, coordinating concurrency locking,
     * HLC timestamp assignment, state persistence, and sync intent logging.
     *
     * @param featureContext Target feature domain context.
     * @param codec Router used for feature state encoding and field tag tracking.
     * @param candidateKey Primary identifier key of the entity being mutated.
     * @param incomingHlc Optional incoming HLC when driven by remote sync processing.
     * @param op Mutation operation type (UPSERT or DELETE).
     * @param fetchExistingState Lambda fetching current persisted entity state.
     * @param computeChange Lambda returning desired target entity state.
     * @param persist Lambda writing the updated entity to local storage.
     * @param onSkip Lambda handling early-exit skip scenarios (returns fallback result).
     * @return Result identifier or operation outcome code.
     */
    suspend fun <T: LocalFirstEntity<T>> processIntent(
        featureContext: FeatureContext,
        codec: FeatureCodecRouter<T, FeatureCodec<T>>,
        candidateKey: Long,
        incomingHlc: HLC? = null,
        op: MutationOp,
        fetchExistingState: suspend (id: Long) -> T?,
        computeChange: suspend (existing: T?) -> T,
        persist: suspend (stamped: T) -> Long,
        onSkip: (fallback: T?) -> Long
    ): Long

    /**
     * Processes an inbound remote intent payload, executing decoding, state merging,
     * and local atomic commit.
     *
     * @param featureContext Target feature domain context.
     * @param codec Router used for decoding the remote payload.
     * @param decodeContext Remote decode context containing candidate key, HLC, and operation.
     * @param payload Raw binary payload received from remote transport.
     * @param fetchAny Lambda fetching current persisted entity state.
     * @param save Lambda writing the updated entity to local storage.
     */
    suspend fun <T: LocalFirstEntity<T>> processRemoteIntent(
        featureContext: FeatureContext,
        codec: FeatureCodecRouter<T, FeatureCodec<T>>,
        decodeContext: DecodeContext,
        payload: ByteArray?,
        fetchAny: suspend (id: Long) -> T?,
        save: suspend (entity: T) -> Long
    )
}
