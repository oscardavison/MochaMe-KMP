package com.mochame.sync.api.repository

import co.touchlab.kermit.Logger
import com.mochame.logger.withTimer
import com.mochame.sync.api.exceptions.MochaException
import com.mochame.sync.api.exceptions.toMochaException
import com.mochame.sync.api.hlc.HLC
import com.mochame.sync.api.metadata.FeatureContext
import com.mochame.sync.api.metadata.MutationOp
import com.mochame.sync.api.metadata.SyncStatus
import com.mochame.sync.api.models.LocalFirstEntity
import com.mochame.sync.common.toBitmask
import com.mochame.sync.common.toTagSummary
import com.mochame.sync.spi.infrastructure.SyncReceiver
import com.mochame.sync.spi.infrastructure.serialization.FeatureCodec
import com.mochame.sync.spi.infrastructure.serialization.FeatureCodecRouter
import com.mochame.sync.spi.infrastructure.serialization.FieldHlcMap
import com.mochame.sync.spi.models.DecodeContext
import com.mochame.sync.spi.models.SyncIntent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlin.time.Clock
import kotlin.time.TimeSource

/**
 * The default logic for local-first data mutations.
 * Ensures any change to local state is atomically bound to a [SyncIntent].
 *
 * @param T The entity type, adhering to the [LocalFirstEntity] contract.
 */
abstract class LocalFirstRepository<T : LocalFirstEntity<T>>(
    override val featureContext: FeatureContext,
    @PublishedApi internal val deps: LocalFirstDependencies,
    @PublishedApi internal val codec: FeatureCodecRouter<T, FeatureCodec<T>>,
    protected val logger: Logger
) : SyncReceiver {

    /**
     * All local persistence performed by any feature's repository funnels through this method,
     * whether that be as a result of an outbound or an inbound intent.
     * * Ensures database lockouts are handled gracefully.
     * * A locker is used to ensure that any single candidate key operation is sequential
     * in the case that a local operation is processing an intent at the same moment as a remote intent comes in.
     * The current design holds any potential delay of the executor within the mutex lock.
     * * Remote processing expects a client process, and therefore requires the execution policy and
     * any Transactor declarations to be declared at the client orchestration level.
     * @param candidateKey the item (either fetched remotely, or from a local UI event) to be persisted locally.
     * @param incomingHlc used when the SyncCoordinator is calling to process an intent. Forks how we process the intent.
     * @param op the DML operation for the intent. Required for metadata, logging, and state verification.
     * @param fetchExistingState used to perform a backup causality check, and possible ghost deleteBlobByHash (?).
     * @param computeChange requires the feature to assert the state change they wish to make. T is nullable in the case of deletions where a remote intent is made to delete state that does not exist locally.
     * @param persist after verifying and stamping the feature state change, the finalized state is persisted atomically alongside sync payloads/metadata.
     * @param onSkip offers a type-safe way to return R. Potential case of multiple concurrent requests to processing the same intent -
     * these will fail when accessing the database write lock, causing duplicate intents to [FeatureCodecRouter.routedEncode] a state that already existsInCommitted, triggering onSkip.
     *
     * * [R] Return type of any persistence definition, allowing differentiation between
     * the type of data being processed and the type to be returned (e.g. a delete count).
     * * [T] The defined [LocalFirstEntity] involved in processing existing model confirmation, compaction, [HLC] stamping, and local persistence.
     *
     *
     * @return Long representing the result of the final SQLite operation:
     * * onSkip - 0L
     * * upserts/deletes on existing - -1L (throws [MochaException.Transient.StateIssue] if locally deleting non-existent record)
     * * inserts - [T.id]
     */
    @PublishedApi
    internal suspend inline fun processIntent(
        candidateKey: Long,
        incomingHlc: HLC? = null,
        op: MutationOp,
        crossinline fetchExistingState: suspend (id: Long) -> T?,
        crossinline computeChange: suspend (existing: T?) -> T,
        crossinline persist: suspend (stamped: T) -> Long,
        crossinline onSkip: (fallback: T?) -> Long
    ): Long {
        deps.bootProvider.awaitReady()

        return deps.locker.withLock(featureContext, candidateKey) {
            if (incomingHlc != null) {
                executeIntentPipeline(
                    candidateKey = candidateKey,
                    incomingHlc = incomingHlc,
                    op = op,
                    fetchExistingState = fetchExistingState,
                    computeChange = computeChange,
                    persist = persist,
                    onSkip = onSkip
                )
            } else {
                deps.executor.execute("[${featureContext}_$op-$candidateKey]") {
                    executeIntentPipeline(
                        candidateKey = candidateKey,
                        incomingHlc = incomingHlc,
                        op = op,
                        fetchExistingState = fetchExistingState,
                        computeChange = computeChange,
                        persist = persist,
                        onSkip = onSkip
                    )
                }
            }
        }
    }


    @PublishedApi
    internal suspend inline fun executeIntentPipeline(
        candidateKey: Long,
        incomingHlc: HLC?,
        op: MutationOp,
        crossinline fetchExistingState: suspend (id: Long) -> T?,
        crossinline computeChange: suspend (existing: T?) -> T,
        crossinline persist: suspend (stamped: T) -> Long,
        crossinline onSkip: (fallback: T?) -> Long
    ): Long {
        val existingState = fetchExistingState(candidateKey)

        if (shouldRejectIntent(existingState, incomingHlc, op, candidateKey))
            return onSkip(existingState)

        val candidateState = computeChange(existingState)

        return if (incomingHlc != null) {
            persist(candidateState)
        } else {
            val hlc = deps.hlcFactory.getNextHlc()
            var fieldHlcMap = FieldHlcMap(existingState?.fieldHlcs ?: ByteArray(0))
            val changedTags = codec.routedComputeChangedTags(candidateState, existingState)
            val changedMask = changedTags.toBitmask()

            if (changedMask == 0L) {
                return onSkip(existingState)
            }

            changedTags.forEach { tag -> fieldHlcMap = fieldHlcMap.updateTag(tag, hlc) }

            val stampedState = candidateState.withHlcMetadata(hlc, fieldHlcMap.bytes)

            handleLocalCommit(
                candidateKey = candidateKey,
                op = op,
                stampedState = stampedState,
                existingState = existingState,
                changedMask = changedMask,
                persistAction = { persist(stampedState) },
            )
        }
    }

    // -----------------------------------------------------------
    // ACCESS
    // -----------------------------------------------------------
    /**
     * Convenience overload providing the default upsert logic.
     * Suspend functional parameters with default values are not yet supported in inline functions.
     */
    protected suspend inline fun localUpsert(
        candidateKey: Long,
        incomingHlc: HLC? = null,
        crossinline computeChange: suspend (existing: T?) -> T,
    ) = localUpsert(
        candidateKey = candidateKey,
        incomingHlc = incomingHlc,
        op = MutationOp.UPSERT,
        fetchExistingState = { fetch(it) },
        computeChange = computeChange,
        persist = { save(it) },
        onSkip = { 0L.also { logger.v { "Skipping Upsert [ID:$candidateKey]" } } }
    )

    protected suspend inline fun localUpsert(
        candidateKey: Long,
        incomingHlc: HLC? = null,
        op: MutationOp = MutationOp.UPSERT,
        crossinline fetchExistingState: suspend (id: Long) -> T?,
        crossinline computeChange: suspend (existing: T?) -> T,
        crossinline persist: suspend (stamped: T) -> Long,
        crossinline onSkip: (fallback: T?) -> Long
    ) = withContext(deps.ioContext) {
        processIntent(
            candidateKey,
            incomingHlc,
            op,
            fetchExistingState,
            computeChange,
            persist,
            onSkip
        )
    }


    /**
     * Convenience overload providing the default soft-delete logic.
     * Suspend functional parameters with default values are not yet supported in inline functions.
     */
    protected suspend inline fun localDelete(
        candidateKey: Long,
        incomingHlc: HLC? = null,
    ) = localDelete(
        candidateKey = candidateKey,
        incomingHlc = incomingHlc,
        fetchExistingState = { fetch(it) },
        computeChange = { it!!.withDeleteState(true) },
        persist = { save(it) },
        onSkip = { 0L }
    )

    protected suspend inline fun localDelete(
        candidateKey: Long,
        incomingHlc: HLC? = null,
        crossinline fetchExistingState: suspend (id: Long) -> T?,
        crossinline computeChange: suspend (existing: T?) -> T,
        crossinline persist: suspend (stamped: T) -> Long,
        crossinline onSkip: (fallback: T?) -> Long
    ) = withContext(deps.ioContext) {
        processIntent(
            candidateKey = candidateKey,
            incomingHlc = incomingHlc,
            op = MutationOp.DELETE,
            fetchExistingState = fetchExistingState,
            computeChange = computeChange,
            persist = persist,
            onSkip = onSkip
        )
    }

    /**
     * The inheritance pattern exposes this to the feature repositories.
     * Intended use is for a synchronization component to communicate with feature
     * pipelines on inbound payloads, and not to be used by features themselves.
     * Caller to define the context.
     */
    override suspend fun processRemoteIntent(context: DecodeContext, payload: ByteArray?) {
        if (payload == null) {
            val blobId = context.overflowBlobId ?: throw MochaException.Transient.StateIssue(
                "Received null payload with no overflowId for ${context.candidateKey}"
            )

            logger.d { "Branching to overflow processing. [Key: ${context.candidateKey}] [blobId: $blobId]." }
            // TODO: This road ends here...
            return
        }

        processIntent(
            candidateKey = context.candidateKey,
            incomingHlc = context.hlc,
            op = context.op,
            fetchExistingState = { fetch(context.candidateKey) },
            computeChange = { codec.routedDecode(payload, context, it) },
            persist = { stamped -> save(stamped) },
            onSkip = { 0L }
        )
    }

    // --- Features required to implement these methods ---
    protected abstract suspend fun fetch(id: Long): T?
    protected abstract suspend fun save(entity: T): Long
    protected abstract suspend fun compactState(newState: T, existing: T?): T

    // -----------------------------------------------------------
    // HELPERS
    // -----------------------------------------------------------

    /**
     * Returns true if the entity is rejected.
     */
    @PublishedApi
    internal fun shouldRejectIntent(
        existing: T?,
        incomingHlc: HLC?,
        op: MutationOp,
        candidateKey: Long
    ): Boolean {
        if (existing == null) {
            // Local/Remote Upserts always require Field-Level diffing
            if (op == MutationOp.UPSERT) return false

            if (incomingHlc != null)
                return reject(candidateKey) { "Non-existent local record (remote HLC: $incomingHlc)" }

            throw MochaException.Transient.StateIssue("Local Delete attempt against non-existent record: $candidateKey.")
        }

        deps.hlcFactory.assertValid(existing.hlc, candidateKey)

        if (op == MutationOp.DELETE) {
            when {
                existing.isDeleted ->
                    return reject(candidateKey) { "Local record is already deleted (HLC: ${existing.hlc})" }

                incomingHlc != null && incomingHlc <= existing.hlc ->
                    return reject(candidateKey) { "Obsolete Remote Delete: local(${existing.hlc}) > remote($incomingHlc)" }
            }
        }

        return false
    }

    /** Logs the rejection reason and returns `true` to signal intent rejection. */
    private inline fun reject(candidateKey: Long, crossinline message: () -> String): Boolean {
        logger.v { "Skipping operation [ID:$candidateKey] -> ${message()}" }
        return true
    }

    @PublishedApi
    internal suspend fun handleLocalCommit(
        candidateKey: Long,
        op: MutationOp,
        stampedState: T,
        existingState: T?,
        changedMask: Long,
        persistAction: suspend () -> Long,
    ): Long {
        val payload = codec.routedEncode(stampedState, existingState)

        val summary = changedMask.toTagSummary(op)
            .also { logger.d { "In-Memory Summary [key=$candidateKey]: $it" } }
        val hlc = stampedState.hlc
        val tMark = TimeSource.Monotonic.markNow()

        var blobId: String? = null
        var dbCommitted = false

        try {
            if (payload.size > 65_536L) {
                blobId = deps.blobStore.stage(Buffer().also { it.write(payload) })
                logger.v { "Required staged payload: [${payload.size / 1024}KB | Key: $candidateKey]" }
            }

            val mark = TimeSource.Monotonic.markNow()

            val result = deps.transactor.runImmediateTransaction {
                val localResult = persistAction()
                recordIntent(
                    candidateKey = candidateKey,
                    op = op,
                    hlc = hlc,
                    payload = if (blobId == null) payload else null,
                    blobId = blobId,
                    changedMask = changedMask,
                    diagnosticSummary = summary
                )
                deps.nodeManager.updateHlcFloor(hlc)
                localResult
            }.also {
                dbCommitted = true
                deps.workerHook.invalidate() // could use a channel (will benchmark flow usage first to compare)
                logger.v {
                    "Local DB Transaction Committed [key: $candidateKey] [hlc: $hlc]".withTimer(
                        mark
                    )
                }
            }

            blobId?.also {
                deps.blobStore.commit(it)
                logger.i {
                    "Intent Dispatched | Op: $op | Key: $candidateKey".withTimer(tMark)
                }
            }

            return result
        } catch (e: Exception) {
            if (blobId != null) {
                if (!dbCommitted) {
                    deps.blobStore.abort(blobId).also {
                        logger.e { "Intent Failed: Blob Aborted | Key: $candidateKey | Reason: ${e.message}" }
                    }
                } else {
                    logger.w(e) { "Post-Commit IO Failure: Blob $blobId in /pending. Janitor will reconcile [${e.message}]." }
                    if (e is CancellationException) throw e
                    throw MochaException.Transient.BlobResolutionPending(blobId)
                }
            }

            throw e.toMochaException(e.message)
        }
    }

    private suspend fun recordIntent(
        candidateKey: Long,
        op: MutationOp,
        hlc: HLC,
        payload: ByteArray?,
        blobId: String?,
        changedMask: Long,
        diagnosticSummary: String
    ) {
        deps.intentStore.recordIntent(
            SyncIntent(
                featureSchemaVersion = codec.latestVersion, // guaranteed to not have changed
                hlc = hlc,
                candidateKey = candidateKey,
                featureContext = featureContext,
                operation = op,
                syncStatus = SyncStatus.PENDING,
                createdAt = Clock.System.now().toEpochMilliseconds(),
                payload = payload,
                overflowBlobId = blobId,
                changedMask = changedMask,
                diagnosticSummary = diagnosticSummary
            )
        )
    }

    @Deprecated(
        "Originally existed for compaction of SyncIntent models, but " +
                "as each intent only holds the implicit change in an opaque payload, " +
                "current design does not try to compact pending intents before sync " +
                "and will sync all changes causally."
    )
    private fun resolvePruningTimestamp(
        pending: SyncIntent?,
        currentOp: MutationOp,
        now: Long
    ): Long {
        return if (pending != null && currentOp == MutationOp.DELETE && pending.operation == MutationOp.DELETE) {
            // Don't reset the pruning clock for double-deletes
            pending.createdAt
        } else {
            now
        }
    }

}