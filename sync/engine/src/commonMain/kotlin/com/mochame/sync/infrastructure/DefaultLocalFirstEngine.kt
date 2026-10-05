package com.mochame.sync.infrastructure

import co.touchlab.kermit.Logger
import com.mochame.annotations.IoContext
import com.mochame.logger.withTimer
import com.mochame.sync.api.boot.BootStatusProvider
import com.mochame.sync.api.exceptions.MochaException
import com.mochame.sync.api.exceptions.toMochaException
import com.mochame.sync.api.metadata.FeatureContext
import com.mochame.sync.api.metadata.MutationOp
import com.mochame.sync.api.metadata.SyncStatus
import com.mochame.sync.api.models.HLC
import com.mochame.sync.api.models.LocalFirstEntity
import com.mochame.sync.domain.crdt.CrdtIntentResolver
import com.mochame.sync.domain.crdt.OutboundCommitPackage
import com.mochame.sync.domain.hlc.HlcFactory
import com.mochame.sync.domain.infrastructure.KeyedLocker
import com.mochame.sync.domain.infrastructure.LocalFirstEngine
import com.mochame.sync.domain.infrastructure.SyncWorkerHook
import com.mochame.sync.domain.model.SyncIntent
import com.mochame.sync.domain.stores.BlobStore
import com.mochame.sync.domain.stores.SyncIntentStore
import com.mochame.sync.spi.infrastructure.TransactionProvider
import com.mochame.sync.spi.models.DecodeContext
import com.mochame.sync.spi.node.NodeContextManager
import com.mochame.sync.spi.policy.ExecutionPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.io.Buffer
import org.koin.core.annotation.Single
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock
import kotlin.time.TimeSource

@Single(binds = [LocalFirstEngine::class])
internal class DefaultLocalFirstEngine(
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

    override suspend fun <T : LocalFirstEntity<T>> processLocalIntent(
        featureContext: FeatureContext,
        resolver: CrdtIntentResolver<T>,
        candidateKey: Long,
        op: MutationOp,
        fetchExistingState: suspend (id: Long) -> T?,
        computeChange: suspend (existing: T?) -> T,
        persist: suspend (stamped: T) -> Long,
        onSkip: (fallback: T?) -> Long
    ): Long {
        bootProvider.awaitReady()

        return locker.withLock(featureContext, candidateKey) {
            executor.execute("[$featureContext-$op-$candidateKey]") {
                val existingState = fetchExistingState(candidateKey)
                if (shouldRejectLocal(existingState, op, candidateKey)) {
                    return@execute onSkip(existingState)
                }

                val candidateState = computeChange(existingState)
                val hlc = hlcFactory.getNextHlc()

                val commitPackage = resolver.prepareOutbound(
                    candidateState = candidateState,
                    existingState = existingState,
                    hlc = hlc,
                    op = op
                ) ?: return@execute onSkip(existingState)

                handleLocalCommit(
                    featureContext = featureContext,
                    featureSchemaVersion = resolver.schemaVersion,
                    candidateKey = candidateKey,
                    op = op,
                    commitPackage = commitPackage,
                    persistAction = { persist(commitPackage.stampedState) }
                )
            }
        }
    }

    override suspend fun <T : LocalFirstEntity<T>> processRemoteIntent(
        featureContext: FeatureContext,
        resolver: CrdtIntentResolver<T>,
        decodeContext: DecodeContext,
        payload: ByteArray?,
        fetchExistingState: suspend (id: Long) -> T?,
        save: suspend (entity: T) -> Long
    ) {
        if (payload == null) {
            val blobId = decodeContext.overflowBlobId ?: throw MochaException.Transient.StateIssue(
                "Null payload received without overflowBlobId for key ${decodeContext.candidateKey}"
            )
            logger.d { "Handling overflow payload [Key: ${decodeContext.candidateKey}, Blob: $blobId]" }
            return
        }

        bootProvider.awaitReady()

        locker.withLock(featureContext, decodeContext.candidateKey) {
            val existingState = fetchExistingState(decodeContext.candidateKey)
            if (shouldRejectRemote(existingState, decodeContext.hlc, decodeContext.candidateKey)) {
                return@withLock
            }

            val mergedState = resolver.resolveInbound(
                payload = payload,
                context = decodeContext,
                existingState = existingState
            )

            save(mergedState)
        }
    }

    private suspend fun <T : LocalFirstEntity<T>> handleLocalCommit(
        featureContext: FeatureContext,
        featureSchemaVersion: Int,
        candidateKey: Long,
        op: MutationOp,
        commitPackage: OutboundCommitPackage<T>,
        persistAction: suspend () -> Long
    ): Long {
        val payload = commitPackage.payload
        val hlc = commitPackage.hlc
        val tMark = TimeSource.Monotonic.markNow()

        var blobId: String? = null
        var dbCommitted = false

        try {
            if (payload.size > 65_536L) {
                blobId = blobStore.stage(Buffer().also { it.write(payload) })
            }

            val result = transactor.runImmediateTransaction {
                val localResult = persistAction()
                recordIntent(
                    featureContext = featureContext,
                    featureSchemaVersion = featureSchemaVersion,
                    candidateKey = candidateKey,
                    op = op,
                    hlc = hlc,
                    payload = if (blobId == null) payload else null,
                    blobId = blobId,
                    changedMask = commitPackage.changedMask,
                    diagnosticSummary = commitPackage.diagnosticSummary
                )
                nodeManager.updateHlcFloor(hlc)
                localResult
            }.also {
                dbCommitted = true
                workerHook.invalidate()
            }

            blobId?.also {
                blobStore.commit(it)
                logger.i { "Intent staged to blob [Key: $candidateKey]".withTimer(tMark) }
            }

            return result
        } catch (e: Exception) {
            if (blobId != null) {
                if (!dbCommitted) {
                    blobStore.abort(blobId)
                } else {
                    if (e is CancellationException) throw e
                    throw MochaException.Transient.BlobResolutionPending(blobId)
                }
            }
            throw e.toMochaException(e.message)
        }
    }

    private fun <T : LocalFirstEntity<T>> shouldRejectLocal(
        existing: T?,
        op: MutationOp,
        candidateKey: Long
    ): Boolean {
        if (existing == null && op == MutationOp.DELETE) {
            logger.v { "Skipping local delete on non-existent record: $candidateKey" }
            return true
        }
        if (existing != null) {
            hlcFactory.assertValid(existing.hlc, candidateKey)
            if (op == MutationOp.DELETE && existing.isDeleted) return true
        }
        return false
    }

    private fun <T : LocalFirstEntity<T>> shouldRejectRemote(
        existing: T?,
        incomingHlc: HLC,
        candidateKey: Long
    ): Boolean {
        if (existing == null) return false
        hlcFactory.assertValid(existing.hlc, candidateKey)
        return false
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
}