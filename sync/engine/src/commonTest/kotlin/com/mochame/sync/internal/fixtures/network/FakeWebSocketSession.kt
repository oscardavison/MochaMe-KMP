package com.mochame.sync.internal.fixtures.network

import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketExtension
import io.ktor.websocket.WebSocketSession
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.withTimeout
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds


class FakeWebSocketSession(parentContext: CoroutineContext) : WebSocketSession {
    val sessionJob = Job(parentContext[Job])
    override val coroutineContext: CoroutineContext = parentContext + sessionJob
    private val isClosed = atomic(false)

    val incomingChannel = Channel<Frame>(Channel.UNLIMITED)
    val outgoingChannel = Channel<Frame>(Channel.UNLIMITED)

    override val incoming: ReceiveChannel<Frame> get() = incomingChannel

    @Volatile
    var sendException: Throwable? = null

    @OptIn(InternalCoroutinesApi::class)
    override val outgoing: SendChannel<Frame> = object : SendChannel<Frame> by outgoingChannel {
        override suspend fun send(element: Frame) {
            sendException?.let { throw it }

            when (element) {
                is Frame.Ping -> {
                    incomingChannel.send(Frame.Pong(element.data))
                    return
                }

                is Frame.Pong -> return
                is Frame.Close -> {
                    if (!isClosed.compareAndSet(expect = false, update = true)) {
                        return
                    }
                    outgoingChannel.send(element)
                }

                else -> {
                    outgoingChannel.send(element)
                }
            }
        }

        override fun trySend(element: Frame): ChannelResult<Unit> {
            return when (element) {
                is Frame.Ping -> {
                    incomingChannel.trySend(Frame.Pong(element.data))
                    ChannelResult.success(Unit)
                }

                is Frame.Pong -> ChannelResult.success(Unit)
                else -> outgoingChannel.trySend(element)
            }
        }
    }

    override val extensions: List<WebSocketExtension<*>> = emptyList()
    override var maxFrameSize: Long = Long.MAX_VALUE
    override var masking: Boolean = false

    override suspend fun flush() {
        sendException?.let { throw it }
    }

    /**
     * Suspends until a frame arrives.
     * Yields the thread, eliminates loops, and wakes up the exact millisecond the frame lands.
     */
    suspend fun awaitFrames(min: Int, timeoutMs: Duration = 5.seconds): List<Frame> =
        withTimeout(timeoutMs) {
            val frames = mutableListOf<Frame>()
            while (frames.size < min) {
                frames.add(outgoingChannel.receive())
            }
            frames
        }

    /**
     * Drains sent application frames for assertion checks.
     */
    fun drainSentFrames(): List<Frame> {
        val frames = mutableListOf<Frame>()
        while (true) {
            val frame = outgoingChannel.tryReceive().getOrNull() ?: break
            frames.add(frame)
        }
        return frames
    }

    suspend fun close(reason: CloseReason) {
        outgoingChannel.send(Frame.Close(reason))
        incomingChannel.close()
        outgoingChannel.close()
        sessionJob.cancel()
    }

    @Deprecated("Use close() instead")
    override fun terminate() {
        incomingChannel.close()
        outgoingChannel.close()
        sessionJob.cancel()
    }
}