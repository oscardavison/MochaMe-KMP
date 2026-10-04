package com.mochame.sync.api.repository

import co.touchlab.kermit.Logger
import com.mochame.sync.api.hlc.HLC
import com.mochame.sync.api.metadata.FeatureContext
import com.mochame.sync.api.metadata.MutationOp
import com.mochame.sync.api.models.LocalFirstEntity
import com.mochame.sync.spi.infrastructure.SyncReceiver
import com.mochame.sync.spi.infrastructure.serialization.FeatureCodec
import com.mochame.sync.spi.infrastructure.serialization.FeatureCodecRouter
import com.mochame.sync.spi.models.DecodeContext

/**
 * Base repository abstraction for feature modules executing local-first data mutations.
 * Delegates HLC stamping, lock synchronization, and sync intent logging to [LocalFirstEngine].
 *
 * @param T Entity type implementing [LocalFirstEntity].
 * @param featureContext Feature domain context identifier.
 * @param engine The [LocalFirstEngine] coordinating state persistence and sync pipelines.
 * @param codec Feature codec router handling state serialization and tag tracking.
 * @param logger Kermit logger instance.
 */
abstract class LocalFirstRepository<T : LocalFirstEntity<T>>(
    val featureContext: FeatureContext,
    private val engine: LocalFirstEngine,
    protected val codec: FeatureCodecRouter<T, FeatureCodec<T>>,
    protected val logger: Logger
) {

    /**
     * Convenience overload providing default upsert logic.
     */
    protected suspend fun syncUpsert(
        candidateKey: Long,
        incomingHlc: HLC? = null,
        computeChange: suspend (existing: T?) -> T,
    ): Long = syncUpsert(
        candidateKey = candidateKey,
        incomingHlc = incomingHlc,
        op = MutationOp.UPSERT,
        fetchExistingState = { fetchAny(it) },
        computeChange = computeChange,
        persist = { save(it) },
        onSkip = { 0L.also { logger.v { "Skipping Upsert [ID:$candidateKey]" } } }
    )

    /**
     * Persists feature state updates, delegating intent pipeline execution to [LocalFirstEngine].
     */
    protected suspend fun syncUpsert(
        candidateKey: Long,
        incomingHlc: HLC? = null,
        op: MutationOp = MutationOp.UPSERT,
        fetchExistingState: suspend (id: Long) -> T?,
        computeChange: suspend (existing: T?) -> T,
        persist: suspend (stamped: T) -> Long,
        onSkip: (fallback: T?) -> Long
    ): Long = engine.processIntent(
        featureContext = featureContext,
        codec = codec,
        candidateKey = candidateKey,
        incomingHlc = incomingHlc,
        op = op,
        fetchExistingState = fetchExistingState,
        computeChange = computeChange,
        persist = persist,
        onSkip = onSkip
    )

    /**
     * Convenience overload providing default soft-delete logic.
     */
    protected suspend fun syncDelete(
        candidateKey: Long,
        incomingHlc: HLC? = null,
    ): Long = syncDelete(
        candidateKey = candidateKey,
        incomingHlc = incomingHlc,
        fetchExistingState = { fetchAny(it) },
        computeChange = { it!!.withDeleteState(true) },
        persist = { save(it) },
        onSkip = { 0L }
    )

    /**
     * Persists feature soft deletions, delegating intent pipeline execution to [LocalFirstEngine].
     */
    protected suspend fun syncDelete(
        candidateKey: Long,
        incomingHlc: HLC? = null,
        fetchExistingState: suspend (id: Long) -> T?,
        computeChange: suspend (existing: T?) -> T,
        persist: suspend (stamped: T) -> Long,
        onSkip: (fallback: T?) -> Long
    ): Long = engine.processIntent(
        featureContext = featureContext,
        codec = codec,
        candidateKey = candidateKey,
        incomingHlc = incomingHlc,
        op = MutationOp.DELETE,
        fetchExistingState = fetchExistingState,
        computeChange = computeChange,
        persist = persist,
        onSkip = onSkip
    )

    /**
     * A database fetch for an existing model that includes soft deletes,
     * necessary for causality and conflict resolution at the synchronization stage.
     * UI should not utilize the implementation of this method.
     */
    protected abstract suspend fun fetchAny(id: Long): T?
    protected abstract suspend fun save(entity: T): Long
    protected abstract suspend fun compactState(newState: T, existing: T?): T

    /**
     * To be wired using Koin for use as a SyncReceiver.
     */
    fun asSyncReceiver(): SyncReceiver = object : SyncReceiver {
        override val featureContext: FeatureContext = this@LocalFirstRepository.featureContext
        override suspend fun processRemoteIntent(context: DecodeContext, payload: ByteArray?) {
            engine.processRemoteIntent(
                featureContext = featureContext,
                codec = codec,
                decodeContext = context,
                payload = payload,
                fetchAny = { fetchAny(it) },
                save = { save(it) }
            )
        }
    }
}