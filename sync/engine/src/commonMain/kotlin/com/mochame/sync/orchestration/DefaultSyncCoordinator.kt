package com.mochame.sync.orchestration

import co.touchlab.kermit.Logger
import com.mochame.annotations.AppBackgroundScope
import com.mochame.annotations.CoordinatorMutex
import com.mochame.annotations.IoContext
import com.mochame.logger.LogTags
import com.mochame.logger.withTags
import com.mochame.logger.withTimer
import com.mochame.sync.api.boot.BootStatusProvider
import com.mochame.sync.api.exceptions.MochaException
import com.mochame.sync.api.models.HLC
import com.mochame.sync.domain.hlc.HlcFactory
import com.mochame.sync.api.metadata.FeatureContext
import com.mochame.sync.domain.model.SyncStatus
import com.mochame.sync.utils.deriveContext
import com.mochame.sync.domain.stores.QuarantinedPayloadStore
import com.mochame.sync.domain.stores.SyncIntentMaintenanceStore
import com.mochame.sync.domain.infrastructure.SyncReceiver
import com.mochame.sync.domain.infrastructure.SyncWorkerHook
import com.mochame.sync.spi.TransactionProvider
import com.mochame.sync.domain.serialization.IntentCodec
import com.mochame.sync.domain.serialization.PayloadCodec
import com.mochame.sync.domain.model.SyncIntent
import com.mochame.sync.api.network.SendResult
import com.mochame.sync.api.network.SyncTransport
import com.mochame.sync.infrastructure.adaptor.SyncReceiverRegistry
import com.mochame.sync.domain.infrastructure.NodeContextManager
import com.mochame.sync.api.coordination.SyncCoordinator
import com.mochame.sync.domain.policy.ExecutionPolicy
import com.mochame.utils.interfaces.TimeUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.koin.core.annotation.Single
import kotlin.concurrent.Volatile
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

data class InFlightBatch(
    val batchId: Long,
    val deferred: CompletableDeferred<Unit>
)

@Single(binds = [SyncCoordinator::class])
internal class DefaultSyncCoordinator(
    private val transactor: TransactionProvider,
    private val syncTransport: SyncTransport,
    private val payloadCodec: PayloadCodec,
    private val intentCodec: IntentCodec,
    private val executor: ExecutionPolicy,
    private val hlcFactory: HlcFactory,
    private val workerHook: SyncWorkerHook,
    private val bootManager: BootStatusProvider,
    private val nodeManager: NodeContextManager,
    private val timeUtils: TimeUtils,
    private val intentStore: SyncIntentMaintenanceStore,
    private val quarantinedPayloadStore: QuarantinedPayloadStore,
    private val receiverRegistry: SyncReceiverRegistry,
    @CoordinatorMutex private val coordinatorMutex: Mutex,
    @IoContext private val ioContext: CoroutineContext,
    @AppBackgroundScope appBackgroundScope: CoroutineScope,
    logger: Logger
) : SyncCoordinator, AutoCloseable {
    private val logger =
        logger.withTags(LogTags.Layer.ORCH, LogTags.Domain.SYNC, "MsCord")

    init {
        val registered = receiverRegistry.receivers.keys
        val requiredContexts = FeatureContext.entries.filter { it.isProductionEntity }.toSet()

        val missing = requiredContexts - registered
        if (missing.isNotEmpty()) {
            val errorMsg = "Missing SyncReceiver registration for: $missing."
            logger.e { errorMsg }
            throw MochaException.Persistent.Internal(errorMsg)
        }
    }

    private val coordinatorJob = SupervisorJob(appBackgroundScope.coroutineContext[Job])
    private val coordinatorScope = CoroutineScope(
        appBackgroundScope.coroutineContext + coordinatorJob + CoroutineName("SyncCoordinator")
    )
    private val backgroundDispatcher = coordinatorScope.coroutineContext[ContinuationInterceptor]
        ?: error("Dispatcher retrieval error")


    private val SyncIntent.receiver: SyncReceiver
        get() = receiverRegistry.receivers[featureContext] ?: run {
            logger.e { "Routing failure for feature context '$featureContext'" }
            throw MochaException.Transient.StateIssue("No SyncReceiver for feature context '$featureContext'")
        }

    @Volatile
    private var inFlightBatch: InFlightBatch? = null

    /**
     * No Mutex here.
     */
    override fun startOutboundListener(): Job = coordinatorScope.launch(
        CoroutineName("OutboundListener")
    ) {
        try {
            bootManager.awaitReady()
        } catch (e: Exception) {
            logger.e(e) { "Outbound sync pipeline disabled: boot readiness check failed." }
            return@launch
        }

        logger.v { "Started outbound listener..." }

        workerHook.signals.collect {
            try {
                logger.v { "Processing outbound queue..." }
                processQueueUntilExhausted()
            } catch (e: Exception) {
                if (e is CancellationException || e is MochaException.Persistent) throw e
                logger.e(e) {
                    "Failure during outbound processing: ${e.message}. " +
                            "Preserving outbound pipeline state."
                }
            }
        }
    }

    override suspend fun processQueueUntilExhausted() {
        coordinatorMutex.withLock {

            if (!syncTransport.isConnected) {
                logger.v { "Outbound: Transport not connected. Skipping queue processing." }
                return
            }

            while (true) {
                val batch = intentStore.claimNextBatch() ?: break

                val payload = try {
                    payloadCodec.encode(batch.intents)
                } catch (e: Exception) {
                    if (e is CancellationException || e is MochaException.Persistent) throw e
                    handleBatchEncodingFailure(batch.batchId, batch.intents, e)
                    continue
                }

                try {
                    val ackDeferred = CompletableDeferred<Unit>()
                    inFlightBatch = InFlightBatch(batch.batchId, ackDeferred)

                    val shouldContinue =
                        when (val result = syncTransport.send(batch.batchId, payload)) {
                            is SendResult.Success -> awaitAck(batch.batchId, ackDeferred)
                            else -> result.processSendFailure(batch.batchId)
                        }

                    if (!shouldContinue) break
                } finally {
                    if (inFlightBatch?.batchId == batch.batchId) {
                        inFlightBatch = null
                    }
                }
            }
        }
    }

    override suspend fun handleInboundBytes(watermark: Long, inbound: ByteArray) {
        logger.v { "Inbound: Received payload with ${inbound.size}B..." }

        try {
            bootManager.awaitReady()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            logger.e(e) { "Inbound [watermark-$watermark]: Node is not ready or failed boot." }
            return
        }

        val intents = try {
            withContext(backgroundDispatcher + CoroutineName("Inbound-Decode-$watermark")) {
                payloadCodec.decode(inbound)
            }
        } catch (e: Exception) {
            handlePayloadDecodeError(watermark, inbound, e)
            return
        }

        if (intents.isEmpty()) {
            logger.e { "Empty List returned (from: ${inbound.size}B)." }
            transactor.runImmediateTransaction {
                nodeManager.commitInboundWatermark(watermark, timeUtils.now())
            }
            return
        }

        processInboundIntents(watermark, intents)
    }

    private suspend fun processInboundIntents(
        watermark: Long,
        intents: List<SyncIntent>
    ) = withContext(ioContext + CoroutineName("Inbound-Persist-$watermark")) {
        var maxValidHlc: HLC? = null
        var acceptedCount = 0
        val mark = TimeSource.Monotonic.markNow()

        executor.execute("Inbound Watermark[$watermark]") {
            transactor.runImmediateTransaction {
                intents.forEach { intent ->
                    try {
                        // Feature layer executes synchronously without context hops
                        orchestrateIntent(intent)
                        maxValidHlc = maxValidHlc?.let { maxOf(it, intent.hlc) } ?: intent.hlc
                        acceptedCount++
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        handleInboundIntentError(intent, watermark, e)
                    }
                }

                // HLC witness and floor updates happen here directly on the IO thread
                maxValidHlc?.let {
                    hlcFactory.witness(it)
                    nodeManager.updateHlcFloor(it)
                }

                nodeManager.commitInboundWatermark(watermark, timeUtils.now())
            }
        }

        logger.i {
            "Inbound [watermark-$watermark]: Accepted $acceptedCount intent(s)".withTimer(mark)
        }
    }

    /**
     * Captures the state of the latest outbound payload awaiting ack, updates the database,
     * checks if that current state matches the state the ack was sent for, and calls complete
     * on the awaiting outbound pipeline.
     *
     * There is no guard here. The server must be atomic in client to ack/delta response.
     */
    override suspend fun handleInboundAck(batchId: Long, watermark: Long) {
        withContext(NonCancellable) {
            val active = inFlightBatch

            val rowsUpdated = transactor.runImmediateTransaction {
                nodeManager.commitOutboundWatermark(watermark, timeUtils.now())
                intentStore.acknowledgeSuccess(batchId)
            }

            if (active != null && active.batchId == batchId) {
                logger.v { "Inbound: Acknowledged batchId-$batchId (updated=$rowsUpdated) to watermark $watermark" }
                active.deferred.complete(Unit)
            } else {
                logger.w { "Inbound: Settled batchId-$batchId (updated=$rowsUpdated) to watermark $watermark. inFlightBatch asynchronicity occurred (active: ${active?.batchId})" }
            }
        }
    }

    /**
     * Checks if an outbound queue is awaiting an ack, and if so, completes with an exception resulting
     * in outbound queue job cancellation.
     */
    override suspend fun abortInFlightBatch(exception: Exception) {
        val active = inFlightBatch ?: return
        inFlightBatch = null
        active.deferred.completeExceptionally(exception)
    }

    /**
     * All individual Intent processing from inbound ingestion will have errors propagate
     * to this boundary.
     *
     * There may be a need to update the intent status here to specifically mark it as a
     * received intent?
     */
    private suspend fun orchestrateIntent(intent: SyncIntent) {
        val intentContext = intent.checkOverflowState().deriveContext()
        intent.receiver.processRemoteIntent(intentContext, intent.payload)
    }

    private suspend fun SyncIntent.checkOverflowState(): SyncIntent {
        val hasPayload = payload != null
        val hasBlobId = overflowBlobId != null

        if (hasPayload == hasBlobId) {
            val message =
                if (!hasPayload) "both payload and blobId are null" else "payload and blobId are mutually exclusive"
            throw MochaException.Transient.StateIssue("Data integrity violation: $message")
        }

        if (!hasPayload) {
            intentStore.recordIntent(this)
            logger.w { "Overflow intent staged: $candidateKey" }
        }

        return this
    }

    private suspend fun awaitAck(
        batchId: Long,
        ackDeferred: CompletableDeferred<Unit>
    ): Boolean = try {
        withTimeout(15.seconds) { ackDeferred.await() }
        true
    } catch (e: Exception) {
        when (e) {
            is TimeoutCancellationException,
            is MochaException.Transient.NetworkDisconnect -> {
                logger.w { "Outbound: ACK terminating for $batchId (${e::class.simpleName})" }
                intentStore.releaseIntents(batchId)
                false
            }

            else -> throw e
        }
    }

    // -- Exception Processing --

    private suspend fun handleBatchEncodingFailure(
        batchId: Long,
        batch: List<SyncIntent>,
        e: Exception
    ) {
        logger.e(e) { "Outbound: Batch $batchId encoding failed. Sifting intents..." }

        transactor.runImmediateTransaction {
            val validIntents = mutableListOf<SyncIntent>()

            for (intent in batch) {
                try {
                    intentCodec.encode(intent)
                    validIntents.add(intent)
                } catch (innerEx: Exception) {
                    if (innerEx is CancellationException || innerEx is MochaException.Persistent) throw innerEx

                    intentStore.quarantineIntent(
                        hlc = intent.hlc,
                        candidateKey = intent.candidateKey,
                        errorMessage = innerEx.message ?: "Codec serialization failure"
                    )
                    logger.e(innerEx) { "Outbound: Quarantined corrupt intent [key=${intent.candidateKey}]" }
                }
            }

            if (validIntents.isNotEmpty()) {
                intentStore.releaseIntents(validIntents.map { it.hlc })
            }
        }
    }

    /**
     * Packet never made it to the network so recover the intents immediately, or
     * if this is an internal issue, respond accordingly.
     */
    private suspend fun SendResult.processSendFailure(batchId: Long): Boolean {
        when (this) {
            is SendResult.NoConnection -> {
                logger.w { "Outbound: Connection lost on call to send $batchId. Releasing and awaiting reconnection..." }
                intentStore.releaseIntents(batchId)
            }

            is SendResult.Failure -> {
                intentStore.stampLastError(batchId, this.cause.message ?: "Transmission failure")

                if (this.cause is MochaException.Persistent) {
                    logger.e(this.cause) { "Outbound: Persistent failure on batch $batchId. Terminating outbound loop." }
                    throw this.cause
                    // Maybe need some kind of global app state and manager?
                } else {
                    logger.w(this.cause) { "Outbound: Failed to transmit batch $batchId. Janitor to loop retry." }
                }
            }

            else -> {}
        }

        return false
    }


    private suspend fun handlePayloadDecodeError(
        watermark: Long,
        inbound: ByteArray,
        e: Exception
    ) {
        if (e is CancellationException || e is MochaException.Persistent) throw e

        val failureReason = if (e is MochaException.Transient) {
            "TRANSIENT_${e.message}"
        } else {
            e.message ?: "Codec decode failure"
        }

        logger.e(e) { "Inbound [watermark-$watermark]: Parsing failure during processing (${inbound.size}B). $failureReason" }

        transactor.runImmediateTransaction {
            quarantinedPayloadStore.record(
                watermark = watermark,
                rawPayload = inbound,
                failureReason = failureReason
            )
            nodeManager.commitInboundWatermark(watermark, timeUtils.now())
        }
    }

    private suspend fun handleInboundIntentError(
        intent: SyncIntent,
        watermark: Long,
        e: Exception
    ) {
        when (e) {
            is CancellationException -> throw e

            is MochaException.Transient.StateIssue,
            is MochaException.Persistent.UnknownProtocolVersion -> {
                intentStore.recordIntent(
                    intent.copy(
                        syncStatus = SyncStatus.QUARANTINED,
                        lastErrorMessage = e.message,
                        leasedAt = null,
                        batchId = null
                    )
                )
                logger.w { "Inbound [watermark-$watermark]: Quarantined decoded intent [hlc=${intent.hlc}] [key=${intent.candidateKey}]" }
            }

            else -> {
                logger.e(e) { "Inbound [watermark-$watermark]: Unexpected Error. Aborting due to local environmental failure." }
                throw e
            }
        }
    }

    override fun close() {
        coordinatorJob.cancel()
    }
}
