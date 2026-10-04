package com.mochame.sync.orchestration

import co.touchlab.kermit.Logger
import com.mochame.annotations.AppBackgroundScope
import com.mochame.annotations.IoContext
import com.mochame.annotations.JanitorMutex
import com.mochame.logger.LogTags
import com.mochame.logger.withTags
import com.mochame.logger.withTimer
import com.mochame.sync.api.boot.BootState
import com.mochame.sync.api.exceptions.MochaException
import com.mochame.sync.api.exceptions.toMochaException
import com.mochame.sync.api.hlc.HLC
import com.mochame.sync.api.hlc.HlcFactory
import com.mochame.sync.api.metadata.SyncStatus
import com.mochame.sync.domain.config.JanitorMaintenanceConfig
import com.mochame.sync.domain.usecase.PruneIntentsUseCase
import com.mochame.sync.spi.boot.BootStatusUpdater
import com.mochame.sync.domain.stores.SyncIntentMaintenanceStore
import com.mochame.sync.domain.stores.BlobStore
import com.mochame.sync.domain.infrastructure.SyncWorkerHook
import com.mochame.sync.spi.infrastructure.TransactionProvider
import com.mochame.sync.domain.model.SyncIntent
import com.mochame.sync.spi.node.NodeContext
import com.mochame.sync.spi.node.NodeContextManager
import com.mochame.sync.spi.orchestration.SyncJanitor
import com.mochame.sync.spi.policy.ExecutionPolicy
import com.mochame.utils.interfaces.TimeUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.koin.core.annotation.Single
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource


/**
 * Orchestrator of state validity across different application
 * domains, all required for synchronization logic and metadata integrity.
 * Responsabilities cover recovery, initialization requirements,
 * the [SyncStatus] of intents and record pruning, and communicating system
 * stability with relevant components.
 * * [BootState]
 * * [SyncIntent]
 * * [NodeContext]
 * * [HLC]
 */
@Single(binds = [SyncJanitor::class])
internal class DefaultSyncJanitor(
    private val bootUpdater: BootStatusUpdater,
    private val transactor: TransactionProvider,
    private val pruneUseCase: PruneIntentsUseCase,
    private val hlcFactory: HlcFactory,
    private val executor: ExecutionPolicy,
    private val blobStore: BlobStore,
    private val nodeManager: NodeContextManager,
    private val intentStore: SyncIntentMaintenanceStore,
    private val config: JanitorMaintenanceConfig,
    private val timeUtils: TimeUtils,
    private val workerHook: SyncWorkerHook,
    @IoContext private val ioContext: CoroutineContext,
    @JanitorMutex private val mutex: Mutex,
    @AppBackgroundScope appBackgroundScope: CoroutineScope,
    logger: Logger
) : SyncJanitor, AutoCloseable {
    private val logger =
        logger.withTags(LogTags.Layer.ORCH, LogTags.Domain.SYNC, "DrJntr")

    private val janitorJob = SupervisorJob(appBackgroundScope.coroutineContext[Job])

    private val janitorScope = CoroutineScope(
        appBackgroundScope.coroutineContext + janitorJob + ioContext + CoroutineName("SyncJanitor")
    )

    /**
     * The single entry point for app initialization.
     */
    override fun startupChecks(): Job = janitorScope.launch(CoroutineName("Janitor-Startup")) {
        try {
            withTimeout(config.startupTimeout) {
                executor.execute("[Startup Checks]") {
                    mutex.withLock {
                        if (!isValidBootState()) {
                            logger.d { "Janitor: Skipping startup. State invalid." }
                            return@withLock
                        }

                        staleStateMaintenance()
                        initHydration()

                        logger.i { "Janitor Start Up checks finalized." }
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            handleBootFailure(MochaException.Transient.BootTimeout(cause = e))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            handleBootFailure(e.toMochaException(e.message))
        }
    }

    private fun isValidBootState(): Boolean {
        val currentState = bootUpdater.bootState.value
        return currentState is BootState.Init
    }

    private suspend fun initHydration() = withTimeout(5.seconds) { // not needed
        val nodeContext = nodeManager.getOrEstablishContext()
        logger.v { "Hydrating HLC | Last Known Local HLC: ${nodeContext.maxHlc ?: "NONE"} | NodeID: ${nodeContext.nodeId}" }
        hlcFactory.hydrate(nodeContext.maxHlc, nodeContext.nodeId)
    }

    private suspend fun staleStateMaintenance() {
        val mark = TimeSource.Monotonic.markNow()
        blobReconciliation()
        intentReconciliation()
        logger.i { "Stale state maintenance complete".withTimer(mark) }
    }

    override fun startRuntimeMaintenance(): Job =
        janitorScope.launch(CoroutineName("Janitor-RuntimeMaintenance")) {
            while (isActive) {
                delay(config.maintenanceInterval)
                mutex.withLock {
                    val mark = TimeSource.Monotonic.markNow()
                    logger.v { "Runtime maintenance cycle starting..." }

                    try {
                        intentReconciliation()
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        logger.e(e) { "Intent reconciliation encountered error: ${e.message}" }
                    }

                    try {
                        pruneAgedIntents()
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        logger.e(e) { "Intent pruning encountered error: ${e.message}" }
                    }

                    logger.v { "Runtime maintenance cycle finished".withTimer(mark) }
                }
            }
        }


    /**
     * Prunes in chunks then yields, based off the limit defaulting to
     * [PruneIntentsUseCase.Companion.DEFAULT_LIMIT] and the cutoff period of
     * [PruneIntentsUseCase.Companion.DEFAULT_PRUNE_DAYS].
     */
    private suspend fun pruneAgedIntents() {
        pruneUseCase()
    }

    /**
     * Compares blobs successfully staged in the file system (but have not shifted to
     * committed) against a local metadata record, to confirm if a crash came after the
     * database commit, meaning a retry is possible.
     * If there was a crash prior to the database commit,
     */
    private suspend fun blobReconciliation() {
        val pendingHashes = try {
            blobStore.listPendingHashes()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logger.e(e) { "Unable to locate pending hashes." }
            emptyList()
        }

        pendingHashes.forEach { hash ->
            try {
                if (intentStore.existsForBlob(hash)) {
                    logger.i { "Recovering stranded blob: $hash. Finalizing commit." }
                    blobStore.commit(hash)
                } else {
                    logger.w { "Found orphaned pending blob $hash with no metadata. Purging." }
                    blobStore.abort(hash)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                logger.e(e) { "Failed to reconcile individual blob: $hash" }
            }
        }

        yield()

        try {
            blobStore.clearIncompleteStaging()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logger.w(e) { "Purging incomplete staged files terminated: ${e.message}" }
        }
    }

    /**
     * Janitor owns the retry lifecycle of payloads. It's the only component that sees
     * the full history of an intent across multiple sync attempts.
     */
    private suspend fun intentReconciliation() {
        val cutoff = timeUtils.getMillisAgo(config.staleThreshold)

        transactor.runImmediateTransaction {
            val quarantined = intentStore.quarantineStaleLeases(
                cutOff = cutoff,
                retryThreshold = config.retryThreshold
            )
            if (quarantined > 0) {
                logger.w { "Quarantined $quarantined stale intent(s) exceeding retry threshold." }
            }

            val cascaded = intentStore.cascadeQuarantine()
            if (cascaded > 0) {
                logger.w { "Cascade-quarantined $cascaded dependent pending intent(s)." }
            }

            val reset = intentStore.resetStaleLeases(
                cutOff = cutoff,
                retryThreshold = config.retryThreshold
            )
            if (reset > 0) {
                logger.i { "Batch reset $reset stale intent lease(s) back to PENDING." }
                workerHook.invalidate()
            }
        }
    }


    // ----- EXCEPTION HELPERS -----
    private fun handleBootFailure(error: MochaException): MochaException {
        val failureState = error.toBootState()
        bootUpdater.updateState(failureState)

        if (failureState is BootState.LockOut) {
            logger.e(error) { "Persistent boot failure: ${error.message}" }
        } else {
            logger.w(error) { "Transient boot failure: ${error.message}" }
        }

        return error
    }

    private fun MochaException.toBootState(): BootState = when (this) {
        is MochaException.Transient -> BootState.TransientFailure(this.message, this)
        else -> BootState.LockOut(this.message, this)
    }

    override fun close() {
        janitorJob.cancel()
    }

}