@file:OptIn(ExperimentalCoroutinesApi::class)

package com.mochame.server.utils

import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketExtension
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readReason
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.coroutines.cancellation.CancellationException

/**
 * To simulate an abrupt network drop or pipe closure in a test, call fakeSession.coroutineContext.cancel()
 * or throw directly in the test body.
 */
class FakeWebSocketSession(
    parentContext: CoroutineContext = UnconfinedTestDispatcher()
) : WebSocketSession, AutoCloseable {

    private val sessionJob = SupervisorJob(parentContext[Job])
    override val coroutineContext: CoroutineContext = parentContext + sessionJob

    init { // Leaving as originally this was confusing
        parentContext[Job.Key]?.invokeOnCompletion {
            close()
        }
    }

    val incomingChannel = Channel<Frame>(Channel.UNLIMITED)
    override val incoming: ReceiveChannel<Frame> = incomingChannel

    /** Latches the close reason without race conditions and can be used to identify the causal trigger of disconnection. */
    private val closeReasonDeferred = CompletableDeferred<CloseReason>()

    /** If set, outgoing.send() will suspend until this deferred completes, simulating transport backpressure. */
    var sendDelayGate: CompletableDeferred<Unit>? = null

    /** If set, outgoing.send() throws this exception on non-close frames to simulate mid-flight socket collapse. */
    var sendException: Throwable? = null

    var capturedCloseReason: CloseReason? = null
        private set

    val backingOutgoing = Channel<Frame>(Channel.UNLIMITED)

    /** Manual interception on Ktor's outgoing frame calls to intercept close frames */
    override val outgoing: SendChannel<Frame> = object : SendChannel<Frame> by backingOutgoing {

        override suspend fun send(element: Frame) {
            intercept(element)

            // Only throttle or fail application data frames, allowing close frames through
            if (element !is Frame.Close) {
                sendDelayGate?.await()
                sendException?.let { throw it }
                backingOutgoing.send(element)
            } else {
                backingOutgoing.send(element)
                backingOutgoing.close()
            }
        }

        override fun trySend(element: Frame): ChannelResult<Unit> {
            intercept(element)
            return backingOutgoing.trySend(element)
        }

        override fun close(cause: Throwable?): Boolean {
            val wasClosed = backingOutgoing.close(cause)
            if (wasClosed && !closeReasonDeferred.isCompleted) {
                val reason = if (cause != null) {
                    CloseReason(CloseReason.Codes.INTERNAL_ERROR, cause.message ?: "Channel closed with error")
                } else {
                    CloseReason(CloseReason.Codes.NORMAL, "Outgoing channel closed")
                }
                capturedCloseReason = reason
                closeReasonDeferred.complete(reason)
            }
            return wasClosed
        }

        private fun intercept(frame: Frame) {
            if (frame is Frame.Close) {
                val reason = frame.readReason() ?: CloseReason(
                    CloseReason.Codes.NOT_CONSISTENT,
                    "Unless a Close reason wasn't passed, fake didn't capture test code"
                )
                capturedCloseReason = reason
                closeReasonDeferred.complete(reason)
            }
        }
    }

    override val extensions: List<WebSocketExtension<*>> = emptyList()
    override var maxFrameSize: Long = Long.MAX_VALUE
    override var masking: Boolean = false

    override suspend fun flush() {}

    @Suppress("OVERRIDE_DEPRECATION")
    override fun terminate() {
        incomingChannel.close()
        backingOutgoing.close()
    }

    /**
     * Suspends until session.close(...) which launches a coroutine finishes pushing its Close frame.
     */
    suspend fun awaitCloseReason(): CloseReason = closeReasonDeferred.await()

    /**
     * Suspends until a frame arrives.
     * Yields the thread, eliminates loops, and wakes up the exact millisecond the frame lands.
     */
    suspend fun awaitFrames(min: Int, timeoutMs: Duration = 5.seconds): List<Frame> =
        withTimeout(timeoutMs) {
            val frames = mutableListOf<Frame>()
            while(frames.size < min) {
                frames.add(backingOutgoing.receive())
            }
            frames
        }

    /**
     * Drains sent application frames for assertion checks.
     */
    fun drainSentFrames(): List<Frame> {
        val frames = mutableListOf<Frame>()
        while (true) {
            val frame = backingOutgoing.tryReceive().getOrNull() ?: break
            frames.add(frame)
        }
        return frames
    }

    /** Usage will not capture exception */
    override fun close() {
        if (!closeReasonDeferred.isCompleted) {
            closeReasonDeferred.completeExceptionally(CancellationException())
        }
        outgoing.close()
        incomingChannel.cancel(CancellationException())
        sessionJob.cancel()
    }
}

