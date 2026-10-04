package com.mochame.sync.infrastructure

import co.touchlab.kermit.Logger
import com.mochame.annotations.IoContext
import com.mochame.logger.withTimer
import com.mochame.sync.api.boot.BootStatusProvider
import com.mochame.sync.api.exceptions.MochaException
import com.mochame.sync.api.exceptions.toMochaException
import com.mochame.sync.api.hlc.HLC
import com.mochame.sync.api.hlc.HlcFactory
import com.mochame.sync.api.metadata.FeatureContext
import com.mochame.sync.api.metadata.MutationOp
import com.mochame.sync.api.metadata.SyncStatus
import com.mochame.sync.api.models.LocalFirstEntity
import com.mochame.sync.api.repository.LocalFirstEngine
import com.mochame.sync.common.toBitmask
import com.mochame.sync.common.toTagSummary
import com.mochame.sync.domain.stores.BlobStore
import com.mochame.sync.domain.infrastructure.KeyedLocker
import com.mochame.sync.domain.stores.SyncIntentStore
import com.mochame.sync.domain.infrastructure.SyncWorkerHook
import com.mochame.sync.spi.infrastructure.TransactionProvider
import com.mochame.sync.spi.infrastructure.serialization.FeatureCodec
import com.mochame.sync.spi.infrastructure.serialization.FeatureCodecRouter
import com.mochame.sync.spi.models.DecodeContext
import com.mochame.sync.domain.model.SyncIntent
import com.mochame.sync.spi.node.NodeContextManager
import com.mochame.sync.spi.policy.ExecutionPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.io.Buffer
import org.koin.core.annotation.Single
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock
import kotlin.time.TimeSource

/**
 * Default implementation of [LocalFirstEngine].
 * Handles local-first data mutation pipelines, HLC stamping, lock synchronization,
 * transactional commits, blob staging, and intent recording.
 */
@Single(binds = [LocalFirstEngine::class])
class DefaultLocalFirstEngine (
    private val hlcFactory: HlcFactory,
    private val transactor: TransactionProvider,
    private val blobStore: BlobStore,
    private val intentStore: SyncIntentStore,
    private val workerHook: SyncWorkerHook,
    private val executor: ExecutionPolicy,
    private val locker: KeyedLocker,
    private val logger: Logger,
    private val nodeManager: NodeContextManager,
    private val bootProvider: BootStatusProvider,
    @IoContext private val ioContext: CoroutineContext
) : LocalFirstEngine {

    override suspend fun <T: LocalFirstEntity<T>> processIntent(
        featureContext: FeatureContext,
        codec: FeatureCodecRouter<T, FeatureCodec<T>>,
        candidateKey: Long,
        incomingHlc: HLC?,
        op: MutationOp,
        fetchExistingState: suspend (id: Long) -> T?,
        computeChange: suspend (existing: T?) -> T,
        persist: suspend (stamped: T) -> Long,
        onSkip: (fallback: T?) -> Long
    ): Long {
        bootProvider.awaitReady()

        return locker.withLock(featureContext, candidateKey) {
            if (incomingHlc != null) {
                executeIntentPipeline(
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
            } else {
                executor.execute("[${featureContext}_$op-$candidateKey]") {
                    executeIntentPipeline(
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
                }
            }
        }
    }

    private suspend fun <T: LocalFirstEntity<T>> executeIntentPipeline(
        featureContext: FeatureContext,
        codec: FeatureCodecRouter<T, FeatureCodec<T>>,
        candidateKey: Long,
        incomingHlc: HLC?,
        op: MutationOp,
        fetchExistingState: suspend (id: Long) -> T?,
        computeChange: suspend (existing: T?) -> T,
        persist: suspend (stamped: T) -> Long,
        onSkip: (fallback: T?) -> Long
    ): Long {
        val existingState = fetchExistingState(candidateKey)

        if (shouldRejectIntent(existingState, incomingHlc, op, candidateKey))
            return onSkip(existingState)

        val candidateState = computeChange(existingState)

        return if (incomingHlc != null) {
            persist(candidateState)
        } else {
            val hlc = hlcFactory.getNextHlc()
            val changedTags = codec.routedComputeChangedTags(candidateState, existingState)
            val changedMask = changedTags.toBitmask()

            if (changedMask == 0L) {
                return onSkip(existingState)
            }

            val stampedState = codec.stampHlcMetadata(candidateState, existingState, changedTags, hlc)

            handleLocalCommit(
                featureContext = featureContext,
                codec = codec,
                candidateKey = candidateKey,
                op = op,
                stampedState = stampedState,
                existingState = existingState,
                changedMask = changedMask,
                persistAction = { persist(stampedState) },
            )
        }
    }

    private fun <T: LocalFirstEntity<T>> shouldRejectIntent(
        existing: T?,
        incomingHlc: HLC?,
        op: MutationOp,
        candidateKey: Long
    ): Boolean {
        if (existing == null) {
            if (op == MutationOp.UPSERT) return false

            if (incomingHlc != null)
                return reject(candidateKey) { "Non-existent local record (remote HLC: $incomingHlc)" }

            return reject(candidateKey) { "Local Delete attempt against non-existent record: $candidateKey." }
        }

        hlcFactory.assertValid(existing.hlc, candidateKey)

        if (incomingHlc == null && op == MutationOp.DELETE && existing.isDeleted) {
            return reject(candidateKey) { "Local record is already deleted (HLC: ${existing.hlc})" }
        }

        return false
    }

    private inline fun reject(candidateKey: Long, crossinline message: () -> String): Boolean {
        logger.v { "Skipping operation [ID:$candidateKey] -> ${message()}" }
        return true
    }

    private suspend fun <T: LocalFirstEntity<T>> handleLocalCommit(
        featureContext: FeatureContext,
        codec: FeatureCodecRouter<T, FeatureCodec<T>>,
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
                blobId = blobStore.stage(Buffer().also { it.write(payload) })
                logger.v { "Required staged payload: [${payload.size / 1024}KB | Key: $candidateKey]" }
            }

            val mark = TimeSource.Monotonic.markNow()

            val result = transactor.runImmediateTransaction {
                val localResult = persistAction()
                recordIntent(
                    featureContext = featureContext,
                    featureSchemaVersion = codec.latestVersion,
                    candidateKey = candidateKey,
                    op = op,
                    hlc = hlc,
                    payload = if (blobId == null) payload else null,
                    blobId = blobId,
                    changedMask = changedMask,
                    diagnosticSummary = summary
                )
                nodeManager.updateHlcFloor(hlc)
                localResult
            }.also {
                dbCommitted = true
                workerHook.invalidate()
                logger.v {
                    "Local DB Transaction Committed [key: $candidateKey] [hlc: $hlc]".withTimer(
                        mark
                    )
                }
            }

            blobId?.also {
                blobStore.commit(it)
                logger.i {
                    "Intent Dispatched | Op: $op | Key: $candidateKey".withTimer(tMark)
                }
            }

            return result
        } catch (e: Exception) {
            if (blobId != null) {
                if (!dbCommitted) {
                    blobStore.abort(blobId).also {
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
        featureContext: FeatureContext,
        featureSchemaVersion: Int,
        candidateKey: Long,
        op: MutationOp,
        hlc: HLC,
        payload: ByteArray?,
        blobId: String?,
        changedMask: Long,
        diagnosticSummary: String
    ) {
        intentStore.recordIntent(
            SyncIntent(
                featureSchemaVersion = featureSchemaVersion,
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

    override suspend fun <T: LocalFirstEntity<T>> processRemoteIntent(
        featureContext: FeatureContext,
        codec: FeatureCodecRouter<T, FeatureCodec<T>>,
        decodeContext: DecodeContext,
        payload: ByteArray?,
        fetchAny: suspend (id: Long) -> T?,
        save: suspend (entity: T) -> Long
    ) {
        if (payload == null) {
            val blobId = decodeContext.overflowBlobId ?: throw MochaException.Transient.StateIssue(
                "Received null payload with no overflowId for ${decodeContext.candidateKey}"
            )

            logger.d { "Branching to overflow processing. [Key: ${decodeContext.candidateKey}] [blobId: $blobId]." }
            // TODO: This road ends here...
            return
        }

        processIntent(
            featureContext = featureContext,
            codec = codec,
            candidateKey = decodeContext.candidateKey,
            incomingHlc = decodeContext.hlc,
            op = decodeContext.op,
            fetchExistingState = { fetchAny(decodeContext.candidateKey) },
            computeChange = { codec.routedDecode(payload, decodeContext, it) },
            persist = { stamped -> save(stamped) },
            onSkip = { 0L }
        )
    }
}
