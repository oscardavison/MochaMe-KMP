package com.mochame.server.relay

import co.touchlab.kermit.Logger
import com.mochame.server.utils.ServerConfig
import com.mochame.server.utils.ServerLogger
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.channels.ClosedChannelException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Result outcomes for completing the delta backfill phase.
 */
sealed interface BackfillResult {
    data object Success : BackfillResult
    data object ChannelClosed : BackfillResult
    data class Failure(val cause: Exception) : BackfillResult
}

sealed interface EnqueueResult {
    data object Success : EnqueueResult

    /** A capacity planning and concurrency mismatch. The staging catch-up rate cannot outpace
     * the backfill throughput. Determined by the maxBackfillThreshold vs. outboundStagingCapacity ratio. */
    data object StagingSaturated : EnqueueResult

    /** Genuine network failure. The peer's TCP socket or reading loop cannot drain outbound frames fast enough. */
    data object OutboundSaturated : EnqueueResult
    data class Closed(val cause: Exception?) : EnqueueResult

    val isSuccess: Boolean get() = this === Success
}

/**
 * Encapsulates an active peer's WebSocket session state regarding its:
 * - Outbound channel for sending frames to the websocket, allowing suspension without blocking the relay system.
 * - Staging buffer for peer broadcasts until backfill is complete.
 *
 * Incoming frames are not coordinated with this handle.
 * It acts purely as a non-blocking stateful container for outbound communication, deduplication on connection, and
 * passing monotonic watermarked payloads.
 *
 * Coordinates the handoff between backfilling on connection (historical deltas), and broadcasted intents
 * from other peers in the group. Any backfilled deltas and concurrent deltas dispatched during
 * the backfilling process must end up in the outbound pipeline in sequence (by watermark).
 * If a single out of order watermark is shipped to the client, it's then possible for that node
 * to permanently drop that previous state.
 *
 * ###### Concurrency
 * - Calls via [enqueueBroadcast] are non-blocking and bypass locks once backfilled.
 * - Suspension on [outboundChannel] occurs outside locks to prevent stalling
 *   the database writer thread.
 * - Locking ensures the transition from processing a backfill to broadcasting received watermarks
 *   from other peers is atomic.
 */
class SessionHandle(
    val nodeId: String,
    val groupId: String,
    val session: WebSocketSession,
    config: ServerConfig = ServerConfig.Default,
    private val logger: Logger = ServerLogger.base.withTag(nodeId.take(8))
) {
    /**
     * Outbound channel for sending frames to the websocket. All outbound frames related to a session
     * must be sent through this channel.
     */
    val outboundChannel: SendChannel<Frame>
        field = Channel<Frame>(capacity = config.outboundChannelCapacity)

    /** Stages peer broadcasts until backfill is complete. */
    private val stagingBuffer = ArrayDeque<Pair<Long, Frame>>()

    /** Only performs in-memory pointer arithmetic on a pre-bounded deque, releasing lock per staged frame. */
    private val stagingLock = Any()

    @OptIn(ExperimentalAtomicApi::class)
    private val isClosed = AtomicBoolean(false)
    private val maxStagingCapacity = config.outboundStagingCapacity

    @Volatile
    var isBackfilled: Boolean = false
        private set

    init {
        session.launch(CoroutineName("$nodeId [Outbound]")) {
            runOutboundWorker()
        }
    }

    /**
     * Drains the internal outbound channel to the underlying WebSocket session (suspending) until closed or canceled.
     */
    private suspend fun runOutboundWorker() {
        val tag = "node '$nodeId' [@$identityHex]"
        try {
            for (frame in outboundChannel) {
                session.send(frame)
            }
        } catch (e: CancellationException) {
            logger.d { "Outbound worker cancelled for $tag" }
            throw e
        } catch (e: ClosedSendChannelException) {
            // Expected when client drops TCP connection or Ktor closes the outgoing pipeline
            logger.d { "Outbound channel closed for $tag (${e::class.simpleName})" }
        } catch (e: ClosedChannelException) {
            // Expected on socket reset
            logger.d { "Underlying socket closed for $tag (${e::class.simpleName})" }
        } catch (e: Exception) {
            logger.w(e) { "Termination error in outbound worker for $tag" }
            close(
                CloseReason.Codes.INTERNAL_ERROR,
                e.message ?: "[${e::class.simpleName}] Outbound worker termination"
            )
        } finally {
            outboundChannel.cancel(CancellationException("Outbound worker finished '$nodeId' [@$identityHex]"))
        }
    }

    /**
     * Enqueues a broadcast frame to the outbound pipeline.
     *
     * Bypasses synchronization locks once [isBackfilled] is true, dispatching directly
     * to [outboundChannel]. During active catch-up, uses [stagingLock] to stage
     * frames into [stagingBuffer], preserving monotonic ordering during the handoff
     * to live-streaming.
     *
     *
     * @param watermark The persistent sequence watermark associated with this frame.
     * @param frame The encoded WebSocket frame to transmit.
     * @return [EnqueueResult.Success] if enqueued or staged; [EnqueueResult.StagingSaturated]
     * if [stagingBuffer] limit is exceeded during backfill; [EnqueueResult.OutboundSaturated]
     * if the outbound channel buffer is full; or [EnqueueResult.Closed] if the outbound channel is closed.
     */
    fun enqueueBroadcast(watermark: Long, frame: Frame): EnqueueResult {
        if (isBackfilled) {
            return outboundChannel.trySend(frame).toEnqueueResult()
        }

        if (isClosed.get()) {
            return EnqueueResult.Closed(null)
        }

        synchronized(stagingLock) {
            return if (!isBackfilled) {
                if (stagingBuffer.size >= maxStagingCapacity) {
                    EnqueueResult.StagingSaturated
                } else {
                    stagingBuffer.addLast(watermark to frame)
                    EnqueueResult.Success
                }
            } else {
                outboundChannel.trySend(frame).toEnqueueResult()
            }
        }
    }

    /**
     * Processes staging sequentially. Suspends on outboundChannel.send()
     * outside the lock.
     *
     * If an exception was passed to the [outboundChannel] closure, and staged intents were
     * made after reader connection lock on the database, this failure is wrapped in a
     * [BackfillResult.Failure].
     *
     * @throws CancellationException explicitly.
     */
    suspend fun completeBackfill(maxWatermark: Long): BackfillResult {
        var dropped = 0
        var staged = 0

        while (true) {
            val nextFrame: Frame = synchronized(stagingLock) {
                while (stagingBuffer.isNotEmpty() && stagingBuffer.first().first <= maxWatermark) {
                    // Duplicates between node registration, immediate peer broadcast staging, and the reader connection snapshot checking watermarks since
                    stagingBuffer.removeFirst()
                    dropped++
                }

                if (stagingBuffer.isEmpty()) {
                    isBackfilled = true
                    logger.v { "Frame staging buffer processed. Dropped: $dropped Staged: $staged" }
                    return BackfillResult.Success
                }

                staged++
                stagingBuffer.removeFirst().second
            }

            try {
                outboundChannel.send(nextFrame)
            } catch (e: CancellationException) {
                throw e
            } catch (_: ClosedSendChannelException) {
                return BackfillResult.ChannelClosed
            } catch (e: Exception) {
                return BackfillResult.Failure(e)
            }
        }
    }

    /**
     * Initiates connection closure. Transmits the provided code and reason over the wire,
     * cancelling the session scope and outbound worker.
     *
     * Should never be called from as a consequence of a [CancellationException], but purely
     * for internal exceptions.
     */
    @OptIn(ExperimentalAtomicApi::class)
    fun close(
        code: CloseReason.Codes = CloseReason.Codes.NORMAL,
        reason: String = "Session closed"
    ) {
         if (!isClosed.compareAndSet(false, true)) return

        val sanitizedReason = if (reason.length > 75) reason.take(40) + "..." else reason

        outboundChannel.cancel(CancellationException(reason))

        session.launch {
            withContext(NonCancellable) {
                try {
                    session.close(CloseReason(code, sanitizedReason))
                    logger.d { "Closed session for node '$nodeId' [${code.name}]: $reason" }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.w(e) { "Failed sending close frame to node '$nodeId'" }
                }
            }
        }
    }

    private fun ChannelResult<Unit>.toEnqueueResult(): EnqueueResult = when {
        isSuccess -> EnqueueResult.Success
        isClosed -> {
            val cause = exceptionOrNull()
            if (cause is Error) throw cause
            EnqueueResult.Closed(cause as? Exception)
        }

        else -> EnqueueResult.OutboundSaturated
    }
}

val Any.identityHex: String
    get() = Integer.toHexString(System.identityHashCode(this))