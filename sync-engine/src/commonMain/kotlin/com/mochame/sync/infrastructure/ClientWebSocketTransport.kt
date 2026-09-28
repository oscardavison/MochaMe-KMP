package com.mochame.sync.infrastructure

import co.touchlab.kermit.Logger
import com.mochame.annotations.AppBackgroundScope
import com.mochame.logger.LogTags
import com.mochame.logger.withTags
import com.mochame.sync.api.exceptions.MochaException
import com.mochame.sync.spi.network.SendResult
import com.mochame.sync.spi.network.SyncTransport
import com.mochame.sync.spi.network.WireFrame
import com.mochame.sync.spi.network.WireFrameFactory
import com.mochame.sync.spi.node.NodeContextManager
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.ResponseException
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.pingInterval
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.IOException
import org.koin.core.annotation.Single
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds


/**
 * WebSocket transport implementation for sync networking.
 *
 * Manages socket connections, automated retry loops, background pause
 * debouncing, and message framing between the local node and sync relay.
 */
@Single(binds = [SyncTransport::class])
internal class ClientWebSocketTransport(
    private val nodeManager: NodeContextManager,
    @AppBackgroundScope backgroundScope: CoroutineScope,
    engine: HttpClientEngine,
    logger: Logger
) : SyncTransport, AutoCloseable {

    private val logger =
        logger.withTags(LogTags.Layer.TRANSPORT, LogTags.Domain.SYNC, "ClSock")

    private val client = HttpClient(engine) {
        install(WebSockets) {
            pingInterval = 15.seconds
        }
    }

    private data class ConnectionEndpoint(
        val host: String,
        val port: Int,
        val groupId: String,
        val nodeId: String
    )

    private val transportJob = SupervisorJob(backgroundScope.coroutineContext[Job])

    private val transportScope = CoroutineScope(
        backgroundScope.coroutineContext + transportJob + CoroutineName("ClientTransport")
    )

    /**
     * Allows manual retry attempts to reinstantiate connection attempts, replacing fixed delay loops.
     */
    private val reconnectSignal = Channel<Unit>(Channel.CONFLATED)
    private var endpoint: ConnectionEndpoint? = null

    /**
     * Single coroutine managing the reconnect/retry loop across the lifetime of the connection target.
     */
    private var connectionJob: Job? = null

    /**
     * Pending delayed task providing a grace period before closing the active socket on pause.
     */
    private var pauseDebounceJob: Job? = null

    /**
     * Flag indicating whether the transport has been explicitly paused and should halt connection loops.
     */
    private val isPaused = atomic(false)

    /**
     * The currently established, WebSocket session available for sending and receiving frames.
     */
    private val activeSession = atomic<DefaultClientWebSocketSession?>(null)

    /**
     * State transitions across connect, pause, resume, and teardown operations.
     */
    private val lifecycleMutex = Mutex()

    private var inboundDeltaHandler: (suspend (watermark: Long, payload: ByteArray) -> Unit)? = null
    private var inboundAckHandler: (suspend (batchId: Long, watermark: Long) -> Unit)? = null
    private var onConnectedListener: (suspend () -> Unit)? = null
    private var onDisconnectedListener: (suspend () -> Unit)? = null

    /**
     * Useful if an external components lifecycle depends on an active connection.
     */
    override val isConnected: Boolean
        get() = activeSession.value?.isActive == true

    override fun setOnConnectedListener(onConnected: suspend () -> Unit) {
        this.onConnectedListener = onConnected
    }

    override fun setOnDisconnectedListener(onDisconnected: suspend () -> Unit) {
        this.onDisconnectedListener = onDisconnected
    }

    override fun registerInboundDeltaHandler(onReceived: suspend (Long, ByteArray) -> Unit) {
        this.inboundDeltaHandler = onReceived
    }

    override fun registerInboundAckHandler(onAck: suspend (Long, Long) -> Unit) {
        this.inboundAckHandler = onAck
    }

    private suspend fun awaitReconnect(delay: Duration) {
        while (reconnectSignal.tryReceive().isSuccess) { /* Drain stale triggers */
        }
        withTimeoutOrNull(delay) { reconnectSignal.receive() }
    }

    /**
     * Connects to the specified endpoint.
     *
     * If already connected or connecting to this endpoint, this call wakes up any
     * active backoff delay to retry immediately (as in a user clicking retry).
     * Otherwise, it closes any existing connection and starts a new connection loop.
     *
     * More than one possible endpoint at a time is not implemented or tested.
     * Ktor looks like i
     */
    override suspend fun connect(
        host: String,
        port: Int,
        groupId: String
    ) {
        lifecycleMutex.withLock {
            val nodeId = nodeManager.getNodeId() ?: error("Node Context is not initialized.")
            val newEndpoint = ConnectionEndpoint(host, port, groupId, nodeId.value.toString())

            pauseDebounceJob?.cancel()
            pauseDebounceJob = null

            if (endpoint == newEndpoint && connectionJob?.isActive == true && !isPaused.value) {
                if (activeSession.value == null) {
                    reconnectSignal.trySend(Unit)
                }
                logger.v { "Reconnection attempt made against ${newEndpoint.host}:${newEndpoint.port}" }
                return@withLock
            }

            endpoint = newEndpoint
            isPaused.value = false

            teardownActiveConnectionLocked()
            startConnectionLoopLocked()
        }
    }

    /**
     * Pauses the transport with a grace period before closing the socket.
     */
    override suspend fun pause() {
        lifecycleMutex.withLock {
            if (isPaused.value || pauseDebounceJob?.isActive == true) return@withLock

            pauseDebounceJob = transportScope.launch(CoroutineName("PauseDebounce")) {
                delay(5.seconds)
                lifecycleMutex.withLock {
                    isPaused.value = true
                    pauseDebounceJob = null
                    logger.i { "Grace period expired. Terminating WebSocket connection..." }
                    teardownActiveConnectionLocked()
                }
            }
        }
    }

    /**
     * Resumes the transport, canceling any pending pause debounce or reconnecting
     * if the grace period has elapsed.
     */
    override suspend fun resume() {
        lifecycleMutex.withLock {
            if (pauseDebounceJob?.isActive == true) {
                logger.i { "Resumed within grace window. Preserving existing connection." }
                pauseDebounceJob?.cancelAndJoin()
                pauseDebounceJob = null
                isPaused.value = false
                return@withLock
            }

            if (!isPaused.value || endpoint == null) return@withLock
            logger.i { "Resuming transport: Re-establishing socket connection." }
            isPaused.value = false
            startConnectionLoopLocked()
        }
    }

    private suspend fun teardownActiveConnectionLocked() {
        val session = activeSession.getAndSet(null)
        val job = connectionJob
        connectionJob = null

        if (session != null) {
            val listener = onDisconnectedListener
            if (listener != null) {
                transportScope.launch(CoroutineName("Teardown-DisconnectListener")) { listener.invoke() }
            }
            try {
                withTimeoutOrNull(500.milliseconds) {
                    session.close(CloseReason(CloseReason.Codes.NORMAL, "App backgrounded"))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.v(e) { "Socket close handshake aborted or already closed: ${e.message}" }
            }
        }

        job?.cancelAndJoin()
    }

    private fun startConnectionLoopLocked() {
        val target = endpoint ?: return
        if (connectionJob?.isActive == true) return

        connectionJob = transportScope.launch(CoroutineName("ConnectionLoop")) {
            while (isActive && !isPaused.value) {
                try {
                    try {
                        runSingleSession(target)
                    } finally {
                        activeSession.getAndSet(null)?.let {
                            val listener = onDisconnectedListener
                            if (listener != null) {
                                transportScope.launch(CoroutineName("Normal-DisconnectedListener")) { listener.invoke() }
                            }
                        }
                    }

                    if (isActive && !isPaused.value) {
                        logger.i { "WebSocket channel closed. Attempting reconnection in 30s..." }
                        awaitReconnect(30.seconds)
                    }

                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    when (e) {
                        is IOException -> {
                            logger.d(e) { "Network transport failure: ${e.message}. Retrying in 10s..." }
                            awaitReconnect(10.seconds)
                        }

                        is ClosedReceiveChannelException, is ClosedSendChannelException -> {
                            logger.w(e) { "Channel closed abruptly by remote peer. Retrying in 10s..." }
                            awaitReconnect(10.seconds)
                        }

                        is ResponseException -> {
                            logger.w(e) { "Handshake HTTP error [${e.response.status}]. Retrying in 10s..." }
                            awaitReconnect(10.seconds)
                        }

                        else -> {
                            logger.e(e) { "Error in connection loop: ${e.message}. Terminated Connection." }
                            break
                        }
                    }
                }
            }
        }
    }

    private suspend fun runSingleSession(target: ConnectionEndpoint) {
        val currentWatermark = nodeManager.getLastInboundWatermark() ?: 0L

        client.webSocket(
            host = target.host,
            port = target.port,
            path = "/sync/${target.groupId}/${target.nodeId}?since=$currentWatermark"
        ) {
            activeSession.value = this
            reconnectSignal.tryReceive()

            logger.i { "WebSocket connected to ${target.host}:${target.port} (Since Watermark: $currentWatermark)" }

            for (frame in incoming) {
                if (frame is Frame.Binary) {
                    try {
                        dispatchWireFrame(frame.readBytes())
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        val closeReason = when (e) {
                            is MochaException.Persistent -> CloseReason(
                                CloseReason.Codes.INTERNAL_ERROR,
                                "Unrecoverable client state"
                            )

                            else -> CloseReason(
                                CloseReason.Codes.TRY_AGAIN_LATER,
                                "Inbound ingestion error"
                            )
                        }
                        runCatching { close(closeReason) }

                        throw e
                    }
                }
            }
        }
    }

    /**
     * Does not catch illegal state exceptions. If the payload was corrupt, current behavior is to terminate
     * the session immediately for debugging, and to allow reconnection to trigger a backfill and retry.
     */
    private suspend fun dispatchWireFrame(bytes: ByteArray) {
        when (val wireFrame = WireFrameFactory.unwrap(bytes)) {
            is WireFrame.BackfillComplete -> {
                logger.i { "Backfill complete. Triggering outbound pipeline flush..." }
                transportScope.launch(CoroutineName("OnConnectedFlush")) {
                    onConnectedListener?.invoke()
                }
            }

            is WireFrame.Ack -> {
                inboundAckHandler?.invoke(wireFrame.batchId, wireFrame.watermark)
            }

            is WireFrame.Delta -> {
                inboundDeltaHandler?.invoke(wireFrame.watermark, wireFrame.payload)
            }

            is WireFrame.ClientSubmit -> {
                logger.w { "Device received an unexpected client submit frame [BatchId: ${wireFrame.batchId}] [Size: ${wireFrame.payload.size}]" }
            }
        }
    }

    /**
     * Sends an outbound batch payload to the connected relay.
     *
     * @return [SendResult.NoConnection] if the caller is still alive and doing work but the coroutine
     * termination is upstream,
     * or [SendResult.Failure] if an error occurs.
     *
     * @throws CancellationException if the caller itself was canceled.
     */
    override suspend fun send(batchId: Long, payload: ByteArray): SendResult {
        val session = activeSession.value ?: return SendResult.NoConnection

        return try {
            val frame = WireFrameFactory.client(batchId, payload)
            session.send(Frame.Binary(fin = true, data = frame))
            SendResult.Success
        } catch (e: CancellationException) {
            if (currentCoroutineContext().isActive) {
                SendResult.NoConnection
            } else {
                throw e
            }
        } catch (e: Exception) {
            // Only hits local client errors (e.g. WireFrameFactory serialization failure)
            SendResult.Failure(e)
        }
    }

    override fun close() {
        transportJob.cancel()
        client.close()
    }
}

