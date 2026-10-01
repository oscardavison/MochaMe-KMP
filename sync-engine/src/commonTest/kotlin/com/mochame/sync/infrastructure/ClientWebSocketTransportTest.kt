package com.mochame.sync.infrastructure

import com.mochame.support.MochaPlatformTest
import com.mochame.support.runUnitEnvironment
import com.mochame.sync.api.exceptions.MochaException
import com.mochame.sync.di.infrastructure.ClientWebSocketTransportTestEnv
import com.mochame.sync.di.infrastructure.TransportTestModule
import com.mochame.sync.infrastructure.ClientWebSocketTransport.Companion.FAST_RECONNECT_DELAY
import com.mochame.sync.infrastructure.ClientWebSocketTransport.Companion.STANDARD_RECONNECT_DELAY
import com.mochame.sync.spi.network.NetworkConfig
import com.mochame.sync.spi.network.SendResult
import com.mochame.sync.spi.network.WireFrame
import com.mochame.sync.spi.network.WireFrameFactory
import com.mochame.sync.spi.network.encode
import com.mochame.utils.fixtures.TestPayloads
import io.ktor.http.URLProtocol
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import io.ktor.websocket.readReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.io.IOException
import org.koin.core.KoinApplication
import org.koin.plugin.module.dsl.modules
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private inline fun runEnv(
    crossinline koinSetup: KoinApplication.() -> Unit = {},
    crossinline block: suspend ClientWebSocketTransportTestEnv.(TestScope) -> Unit
) = runUnitEnvironment<ClientWebSocketTransportTestEnv>(
    koinSetup = {
        modules(TransportTestModule::class)
        koinSetup()
    },
    block = block
)

@DelicateCoroutinesApi
@ExperimentalCoroutinesApi
class ClientWebSocketTransportTest : MochaPlatformTest() {

    private fun defaultConfig(
        host: String = "localhost",
        port: Int = 8080,
        groupId: String = "team_alpha",
        isSecure: Boolean = false
    ) = NetworkConfig(
        host = host,
        port = port,
        isSecure = isSecure,
        groupId = groupId
    )

    // -------------------------------------------------------------------------
    // Endpoint & Handshake
    // -------------------------------------------------------------------------

    @Test
    fun should_connectAndDisconnect_correctly() = runEnv { scope ->
        // Given
        val context = nodeManager.getOrEstablishContext()
        val watermark = context.lastInboundWatermark ?: 0L

        // When: Connect
        transport.connect(defaultConfig(groupId = "bene_gesserit"))
        val request = awaitHandshake()
        val session = awaitSession()
        scope.runCurrent()

        assertEquals(
            "/sync/bene_gesserit/${context.nodeId}?since=${watermark}",
            request.url.encodedPathAndQuery
        )
        assertTrue(transport.isConnected)

        // When: Teardown
        teardown()
        scope.advanceUntilIdle()

        val frames = session.awaitFrames(1)
        assertEquals(1, frames.size)
        assertIs<Frame.Close>(frames.first())
        assertFalse(transport.isConnected)
        assertTrue(session.outgoing.isClosedForSend)
    }

    @Test
    fun should_throwIllegalStateException_whenNodeIdMissing() = runEnv { scope ->
        val exception = assertFailsWith<IllegalStateException> {
            transport.connect(defaultConfig())
        }

        assertEquals("Node Context is not initialized.", exception.message)

        scope.runCurrent()
        assertTrue(engine.handshakeRequests.isEmpty)
        assertTrue(engine.sessionChannel.isEmpty)
        assertFalse(transport.isConnected)
    }

    @Test
    fun should_persistSessionAndAvoidDuplicateConnections_whenInvokedTwiceWithIdenticalParameters() =
        runEnv { scope ->
            nodeManager.getOrEstablishContext()

            // Given: An initial connection is established
            transport.connect(defaultConfig())
            scope.runCurrent()

            awaitHandshake()
            val firstSession = awaitSession()
            scope.runCurrent()

            assertTrue(transport.isConnected)
            assertTrue(firstSession.coroutineContext.isActive)

            // When: connect() is invoked again with identical parameters while active
            transport.connect(defaultConfig())
            scope.runCurrent()

            // Then:
            assertTrue(engine.handshakeRequests.isEmpty)
            assertTrue(engine.sessionChannel.isEmpty)
            assertTrue(firstSession.coroutineContext.isActive)
            assertTrue(transport.isConnected)

            teardown()
        }

    @Test
    fun should_preserveInFlightHandshake_whenConnectCalledTwiceWithIdenticalParameters() =
        runEnv { scope ->
            nodeManager.getOrEstablishContext()

            val gate = CompletableDeferred<Unit>()
            engine.connectGate = gate

            // When: Concurrent active polling
            transport.connect(defaultConfig())
            scope.runCurrent()
            awaitHandshake()
            assertFalse(transport.isConnected)

            transport.connect(defaultConfig())
            scope.runCurrent()

            gate.complete(Unit)
            val session = awaitSession()
            scope.runCurrent()
            assertTrue(engine.sessionChannel.isEmpty)

            // Then: The transport is wired to the correct instance
            assertTrue(transport.isConnected)
            assertNotNull(session)

            val locator = CompletableDeferred<Pair<Long, ByteArray>>()
            transport.registerInboundDeltaHandler { watermark, payload ->
                locator.complete(Pair(watermark, payload))
            }
            session.incomingChannel.send(
                Frame.Binary(fin = true, data = WireFrame.Delta(1L, TestPayloads.DEFAULT).encode())
            )
            scope.runCurrent()
            assertTrue(locator.isCompleted)

            teardown()
        }

    // -------------------------------------------------------------------------
    // Lifecycle Transitions & Debounce
    // -------------------------------------------------------------------------

    @Test
    fun should_cancelTeardownAndRetainConnection_whenResumedWithinGracePeriod() = runEnv { scope ->
        // Given: An active connection is established
        nodeManager.getOrEstablishContext()
        transport.connect(defaultConfig())
        scope.runCurrent()

        awaitHandshake()
        val session = awaitSession()
        scope.runCurrent()

        assertTrue(transport.isConnected)
        assertTrue(session.coroutineContext.isActive)

        // When: pause() is called and time advances 500ms (< grace period)
        transport.pause()
        scope.advanceTimeBy(500.milliseconds)
        scope.runCurrent()

        // Then:
        assertTrue(transport.isConnected)
        assertTrue(session.coroutineContext.isActive)

        // When: resume() is called within the grace window
        transport.resume()
        scope.runCurrent()
        scope.advanceTimeBy(10.seconds)
        scope.runCurrent()

        // Then: Teardown was completely bypassed; original session is intact
        assertTrue(transport.isConnected)
        assertTrue(session.coroutineContext.isActive)
        assertTrue(session.drainSentFrames().isEmpty())
        assertFalse(session.outgoing.isClosedForSend)

        teardown()
    }

    @Test
    fun should_reestablishSession_whenResumedAfterGracePeriodExpired() = runEnv { scope ->
        val context = nodeManager.getOrEstablishContext()
        val watermark = context.lastInboundWatermark ?: 0L

        // Given: Transport is paused and allowed to expire into a torn-down state
        transport.connect(defaultConfig())

        awaitHandshake()
        val firstSession = awaitSession()
        scope.runCurrent()
        assertTrue(transport.isConnected)

        transport.pause()
        scope.advanceTimeBy(10.seconds)
        scope.runCurrent()

        assertFalse(transport.isConnected)
        assertFalse(firstSession.coroutineContext.isActive)
        val frames = firstSession.drainSentFrames()
        assertEquals(1, frames.size)
        assertIs<Frame.Close>(frames.first())

        // When: resume() is called after full expiration
        transport.resume()
        scope.runCurrent()

        // Then: Connection loop restarts and establishes a new session
        val secondHandshake = awaitHandshake()
        val secondSession = awaitSession()
        scope.runCurrent()

        assertTrue(transport.isConnected)
        assertTrue(secondSession.coroutineContext.isActive)
        assertNotSame(firstSession, secondSession)
        assertEquals(
            "/sync/team_alpha/${context.nodeId}?since=${watermark}",
            secondHandshake.url.encodedPathAndQuery
        )

        teardown()
    }

    @Test
    fun should_abortBackoffDelayAndHalt_whenPausedDuringRetryWindow() = runEnv { scope ->
        nodeManager.getOrEstablishContext()
        engine.failureOnConnect = IOException("Connection refused: localhost:8080")

        transport.connect(defaultConfig())
        scope.runCurrent()
        assertFalse(transport.isConnected)

        // When: Transport is paused while waiting in backoff
        transport.pause()
        scope.advanceTimeBy(5.seconds)
        scope.runCurrent()

        // And: Server returns online while transport is paused
        engine.failureOnConnect = null
        scope.advanceTimeBy(30.seconds)
        scope.runCurrent()

        assertFalse(transport.isConnected)
        assertTrue(engine.sessionChannel.isEmpty)

        // When: Resume is explicitly called
        transport.resume()
        scope.runCurrent()

        // Then: Connection loop restarts cleanly and connects
        awaitHandshake()
        val session = awaitSession()
        scope.runCurrent()

        assertTrue(transport.isConnected)
        assertNotNull(session)

        teardown()
    }

    @Test
    fun should_cancelCleanlyWithoutOrphanedSession_whenPauseInvokedDuringInFlightHandshake() =
        runEnv { scope ->
            nodeManager.getOrEstablishContext()

            // Given: HTTP upgrade suspends indefinitely
            val gate = CompletableDeferred<Unit>()
            engine.connectGate = gate

            // When:
            transport.connect(defaultConfig())
            scope.runCurrent()

            // Then: Handshake request arrived at the engine, but session is not established
            val request = awaitHandshake()
            assertNotNull(request)
            assertFalse(transport.isConnected)
            assertTrue(engine.sessionChannel.isEmpty)

            // When: Lifecycle transition to Pause before late HTTP 101 response
            teardown()
            scope.advanceTimeBy(5.seconds)
            scope.runCurrent()

            assertFalse(transport.isConnected)

            gate.complete(Unit)
            scope.runCurrent()
            scope.advanceUntilIdle()

            // Then: No zombie session
            assertFalse(transport.isConnected)
            assertTrue(engine.sessionChannel.isEmpty)
        }

    @Test
    fun should_establishSessionDuringGracePeriodThenTeardownAtExpiry_whenPauseInvokedDuringHandshake() =
        runEnv { scope ->
            nodeManager.getOrEstablishContext()

            val gate = CompletableDeferred<Unit>()
            engine.connectGate = gate
            transport.connect(defaultConfig())
            scope.runCurrent()
            awaitHandshake()

            // Given: Coroutine launched for lifecycle transition immediately on connect
            transport.pause()
            scope.runCurrent()

            // When: Server responds within 1 second
            scope.advanceTimeBy(1.seconds)
            gate.complete(Unit)
            val session = awaitSession()
            scope.runCurrent()

            // Then: Session was allowed to connect because grace period was active
            assertTrue(transport.isConnected)
            assertNotNull(session)

            scope.advanceTimeBy(4.seconds)
            scope.runCurrent()

            // And: Now grace period has expired, tearing down the session
            assertFalse(transport.isConnected)
            val closeFrame = session.drainSentFrames().firstOrNull() as? Frame.Close
            assertNotNull(closeFrame)
            assertEquals(CloseReason.Codes.NORMAL, closeFrame.readReason()?.knownReason)

            teardown()
        }

    // -------------------------------------------------------------------------
    // Outbound Pipeline
    // -------------------------------------------------------------------------

    @Test
    fun should_dispatchBinaryClientSubmitFrameAndReturnSuccess_whenSessionIsActive() =
        runEnv { scope ->
            // Given: An active connection is established
            nodeManager.getOrEstablishContext()
            transport.connect(defaultConfig())

            awaitHandshake()
            val session = awaitSession()
            scope.runCurrent()
            assertTrue(transport.isConnected)

            val batchId = 101L
            val payload = TestPayloads.DEFAULT
            val expectedWireBytes = WireFrameFactory.client(batchId, payload)

            // When:
            val result = transport.send(batchId, payload)

            // Then: Returns Success and dispatches matching binary ClientSubmit frame
            assertEquals(SendResult.Success, result)

            val outgoingFrame = session.outgoingChannel.receive()
            assertTrue(outgoingFrame is Frame.Binary)
            assertContentEquals(expectedWireBytes, outgoingFrame.readBytes())

            val unwrapped = WireFrameFactory.unwrap(outgoingFrame.readBytes())
            assertTrue(unwrapped is WireFrame.ClientSubmit)
            assertEquals(batchId, unwrapped.batchId)
            assertContentEquals(payload, unwrapped.payload)

            teardown()
        }

    @Test
    fun should_returnNoConnectionImmediately_whenSessionIsInactiveOrNull() = runEnv {
        nodeManager.getOrEstablishContext()
        assertFalse(transport.isConnected)

        val result = transport.send(batchId = 1L, payload = TestPayloads.DEFAULT)

        assertEquals(SendResult.NoConnection, result)
    }

    @Test
    fun should_returnSendResultFailure_onParsingError() = runEnv { scope ->
        // Given: Active session
        nodeManager.getOrEstablishContext()
        transport.connect(defaultConfig())
        awaitHandshake()
        awaitSession()
        scope.runCurrent()
        assertTrue(transport.isConnected)

        // When:
        val result = transport.send(batchId = 1L, payload = ByteArray(0))

        // Then:
        assertIs<SendResult.Failure>(result)
        assertIs<IllegalArgumentException>(result.cause)

        teardown()
    }

    @Test
    fun should_returnNoConnection_whenSessionCancelledConcurrently() = runEnv { scope ->
        // Given: Active connection
        nodeManager.getOrEstablishContext()
        transport.connect(defaultConfig())
        awaitHandshake()
        val session = awaitSession()
        scope.runCurrent()
        assertTrue(transport.isConnected)

        // When: send() is invoked after or during session teardown
        session.close(CloseReason(CloseReason.Codes.NORMAL, "Network drop"))
        val result = transport.send(batchId = 202L, payload = TestPayloads.DEFAULT)

        // Then:
        assertEquals(SendResult.NoConnection, result)

        teardown()
    }

    // -------------------------------------------------------------------------
    // Inbound Pipeline
    // -------------------------------------------------------------------------

    @Test
    fun should_invokeInboundAckHandler_whenAckFrameReceived() = runEnv { scope ->
        // Given: An active session with a registered ack handler
        nodeManager.getOrEstablishContext()
        val ackDeferred = CompletableDeferred<Pair<Long, Long>>()
        transport.registerInboundAckHandler { batchId, watermark ->
            ackDeferred.complete(batchId to watermark)
        }

        transport.connect(defaultConfig())
        awaitHandshake()
        val session = awaitSession()
        scope.runCurrent()
        assertTrue(transport.isConnected)

        // When: Inbound Ack frame (10, 50) is emitted to the client
        session.incomingChannel.send(
            Frame.Binary(fin = true, data = WireFrame.Ack(10L, 1L).encode())
        )
        scope.runCurrent()

        // Then: inboundAckHandler is invoked with matching values
        assertTrue(ackDeferred.isCompleted)
        val (batchId, watermark) = ackDeferred.await()
        assertEquals(10L, batchId)
        assertEquals(1L, watermark)

        teardown()
    }

    @Test
    fun should_invokeInboundDeltaHandler_whenDeltaFrameReceived() = runEnv { scope ->
        // Given: An active session with a registered delta handler
        nodeManager.getOrEstablishContext()
        val deltaDeferred = CompletableDeferred<Pair<Long, ByteArray>>()
        transport.registerInboundDeltaHandler { watermark, payload ->
            deltaDeferred.complete(watermark to payload)
        }

        transport.connect(defaultConfig())
        awaitHandshake()
        val session = awaitSession()
        scope.runCurrent()
        assertTrue(transport.isConnected)

        // When:
        session.incomingChannel.send(
            Frame.Binary(fin = true, WireFrame.Delta(1L, TestPayloads.DEFAULT).encode())
        )
        scope.runCurrent()

        // Then: inboundDeltaHandler is invoked with matching watermark and bytes
        assertTrue(deltaDeferred.isCompleted)
        val (watermark, payload) = deltaDeferred.await()
        assertEquals(1L, watermark)
        assertContentEquals(TestPayloads.DEFAULT, payload)

        teardown()
    }

    @Test
    fun should_invokeOnConnectedListenerOnBackgroundScope_whenBackfillCompleteReceived() =
        runEnv { scope ->
            // Given: An active session with a registered onConnected listener
            nodeManager.getOrEstablishContext()
            val backfillCompleteDeferred = CompletableDeferred<Unit>()
            transport.setOnConnectedListener {
                backfillCompleteDeferred.complete(Unit)
            }

            transport.connect(defaultConfig())
            awaitHandshake()
            val session = awaitSession()
            scope.runCurrent()
            assertTrue(transport.isConnected)

            // When: Inbound BackfillComplete frame arrives
            session.incomingChannel.send(
                Frame.Binary(fin = true, WireFrame.BackfillComplete.encode())
            )
            scope.runCurrent()

            // Then: onConnected callback is dispatched and executed
            assertTrue(backfillCompleteDeferred.isCompleted)

            teardown()
        }

    // -------------------------------------------------------------------------
    // Internal Failure Handling
    // -------------------------------------------------------------------------

    @Test
    fun should_closeSocketWithTryAgainLater_whenInboundDeltaHandlerThrowsNonPersistentException() =
        runEnv { scope ->
            // Given: An active session where delta processing encounters an internal error
            nodeManager.getOrEstablishContext()
            transport.registerInboundDeltaHandler { _, _ ->
                throw IllegalStateException("Blargian Snagglebeast")
            }

            transport.connect(defaultConfig())
            awaitHandshake()
            val session = awaitSession()
            scope.runCurrent()
            assertTrue(transport.isConnected)

            // When: A delta frame arrives and handler throws
            session.incomingChannel.send(
                Frame.Binary(fin = true, WireFrame.Delta(1L, TestPayloads.DEFAULT).encode())
            )

            // Then: Socket is closed correctly
            val closeFrame = session.outgoingChannel.receive() as? Frame.Close
            assertNotNull(closeFrame)
            val reason = closeFrame.readReason()
            assertEquals(CloseReason.Codes.TRY_AGAIN_LATER, reason?.knownReason)
            assertFalse(transport.isConnected)
            assertTrue(session.sessionJob.isCancelled)

            teardown()
        }

    @Test
    fun should_closeSocketWithInternalError_whenInboundDeltaHandlerThrowsPersistentException() =
        runEnv { scope ->
            // Given: An active session where delta processing encounters an ingestion error
            nodeManager.getOrEstablishContext()
            transport.registerInboundDeltaHandler { _, _ ->
                throw MochaException.Persistent.DiskFull()
            }

            transport.connect(defaultConfig())
            awaitHandshake()
            val session = awaitSession()
            scope.runCurrent()
            assertTrue(transport.isConnected)

            // When: A delta frame arrives and handler throws
            session.incomingChannel.send(
                Frame.Binary(fin = true, WireFrame.Delta(1L, TestPayloads.DEFAULT).encode())
            )

            // Then: Socket is closed correctly
            val closeFrame = session.outgoingChannel.receive() as? Frame.Close
            assertNotNull(closeFrame)
            val reason = closeFrame.readReason()
            assertEquals(CloseReason.Codes.INTERNAL_ERROR, reason?.knownReason)
            assertFalse(transport.isConnected)
            assertTrue(session.sessionJob.isCancelled)

            teardown()
        }

    @Test
    fun should_terminateConnectionLoopWithoutRetrying_whenEncounteringPersistentFailure() =
        runEnv { scope ->
            // Given: An active session where delta processing encounters a persistent unrecoverable failure
            nodeManager.getOrEstablishContext()
            transport.registerInboundDeltaHandler { _, _ ->
                throw MochaException.Persistent.DiskFull()
            }

            transport.connect(defaultConfig())
            awaitHandshake()
            val session = awaitSession()
            scope.runCurrent()
            assertTrue(transport.isConnected)

            // When: Inbound delta frame triggers fatal exception
            session.incomingChannel.send(
                Frame.Binary(fin = true, WireFrame.Delta(1L, TestPayloads.DEFAULT).encode())
            )
            scope.runCurrent()

            // Then: Loop terminates immediately without scheduling backoff delays
            assertFalse(transport.isConnected)

            // Advancing virtual time confirms no retry attempt is ever scheduled
            scope.advanceTimeBy(60.seconds)
            scope.runCurrent()

            assertFalse(transport.isConnected)
            assertTrue(engine.sessionChannel.isEmpty)

            teardown()
        }

    // -------------------------------------------------------------------------
    // Network Failure Recovery
    // -------------------------------------------------------------------------

    @Test
    fun should_triggerImmediateReconnectionAttempt_onConnect_whenConnectionLoopSuspended() =
        runEnv { scope ->
            // Given: Connection retry loop is established and no active session
            nodeManager.getOrEstablishContext()
            engine.failureOnConnect = IOException("No response")
            transport.connect(defaultConfig())
            scope.runCurrent()
            engine.failureOnConnect = null

            assertFalse(transport.isConnected)
            assertFalse(engine.sessionChannel.tryReceive().isSuccess)
            assertTrue(engine.handshakeRequests.tryReceive().isSuccess)

            // When: Reconnect signal manually sent, earlier than standard retry attempt
            scope.advanceTimeBy(2.seconds)
            assertFalse(transport.isConnected)
            transport.connect(defaultConfig())
            scope.runCurrent()

            // Then: Connection loop restarts and establishes a new session
            assertTrue(transport.isConnected)
            assertTrue(engine.sessionChannel.tryReceive().isSuccess)
            assertTrue(scope.testScheduler.currentTime < FAST_RECONNECT_DELAY.inWholeMilliseconds)

            teardown()
        }

    @Test
    fun should_retryConnectionAfter10Seconds_whenServerIsOfflineInitially() = runEnv { scope ->
        nodeManager.getOrEstablishContext()

        // Given: Server offline (Connection refused)
        engine.failureOnConnect = IOException("Connection refused: localhost:8080")

        transport.connect(defaultConfig())
        scope.runCurrent()
        assertFalse(transport.isConnected)
        assertTrue(engine.sessionChannel.isEmpty)

        // When: Advance time during backoff: no reconnection should happen before 10s
        scope.advanceTimeBy(FAST_RECONNECT_DELAY - 1.seconds)
        scope.runCurrent()
        assertFalse(transport.isConnected)
        assertTrue(engine.sessionChannel.isEmpty)

        // And: Server comes back online
        engine.failureOnConnect = null
        scope.advanceTimeBy(1.seconds)
        scope.runCurrent()

        assertTrue(transport.isConnected)
        assertTrue(engine.sessionChannel.tryReceive().isSuccess)

        teardown()
    }

    @Test
    fun should_reconnectAfter10Seconds_whenClientsTrainGoesIntoASmallTunnel() = runEnv { scope ->
        nodeManager.getOrEstablishContext()
        transport.connect(defaultConfig())
        awaitHandshake()
        val session1 = awaitSession()
        scope.runCurrent()
        assertTrue(transport.isConnected)

        // When: Uh oh tunnel
        session1.incomingChannel.close(IOException("Tunnel time"))
        scope.runCurrent()

        assertFalse(transport.isConnected)

        // And: Advance virtual time to retry window
        scope.advanceTimeBy(FAST_RECONNECT_DELAY)
        scope.runCurrent()

        // Then: Verify a new session is created and handshake completes
        awaitHandshake()
        val session2 = awaitSession()
        scope.runCurrent()

        assertTrue(transport.isConnected)
        assertNotEquals(session1, session2)

        teardown()
    }

    @Test
    fun should_attemptReconnectionIn30Seconds_whenServerClosesCleanly() = runEnv { scope ->
        nodeManager.getOrEstablishContext()
        transport.connect(defaultConfig())
        awaitHandshake()
        val session1 = awaitSession()
        scope.runCurrent()
        assertTrue(transport.isConnected)

        // When: Remote peer closes stream cleanly without transport error
        session1.incomingChannel.close()
        scope.runCurrent()

        assertFalse(transport.isConnected)

        // And: Advance virtual time to 29s (below 30s clean close window)
        scope.advanceTimeBy(STANDARD_RECONNECT_DELAY - 1.seconds)
        scope.runCurrent()
        assertFalse(transport.isConnected)
        assertTrue(engine.sessionChannel.isEmpty)

        // And: Advance past the 30-second mark
        scope.advanceTimeBy(1.seconds)
        scope.runCurrent()

        // Then: Reconnection loop cycles and establishes new session
        awaitHandshake()
        val session2 = awaitSession()
        scope.runCurrent()

        assertTrue(transport.isConnected)
        assertNotEquals(session1, session2)

        teardown()
    }

    @Test
    fun should_reconnectAfter10Seconds_whenChannelClosesAbruptly() = runEnv { scope ->
        nodeManager.getOrEstablishContext()
        transport.connect(defaultConfig())
        awaitHandshake()
        val session1 = awaitSession()
        scope.runCurrent()
        assertTrue(transport.isConnected)

        // When: Channel drops abruptly via EOF
        session1.incomingChannel.close(ClosedReceiveChannelException("Channel closed abruptly"))
        scope.runCurrent()

        assertFalse(transport.isConnected)

        // And: Advance virtual time to 10s retry window
        scope.advanceTimeBy(FAST_RECONNECT_DELAY)
        scope.runCurrent()

        // Then: Verify a new session is created and handshake completes
        awaitHandshake()
        val session2 = awaitSession()
        scope.runCurrent()

        assertTrue(transport.isConnected)
        assertNotEquals(session1, session2)

        teardown()
    }

    // -------------------------------------------------------------------------
    // Cancellation & Structured Concurrency
    // -------------------------------------------------------------------------

    @Test
    fun should_rethrowCancellationException_whenCallerIsCancelledDuringSend() = runEnv { scope ->
        // Given: Active session
        nodeManager.getOrEstablishContext()
        transport.connect(defaultConfig())
        awaitHandshake()
        awaitSession()
        scope.runCurrent()
        assertTrue(transport.isConnected)

        // When: The calling coroutine scope is cancelled
        val callerScope = CoroutineScope(scope.coroutineContext + Job())
        callerScope.cancel()

        // Then: send rethrows CancellationException rather than returning SendResult.NoConnection
        assertFailsWith<CancellationException> {
            callerScope.async {
                transport.send(batchId = 1L, payload = TestPayloads.DEFAULT)
            }.await()
        }

        teardown()
    }

    @Test
    fun should_invokeOnDisconnectedListener_whenActiveSessionTerminates() = runEnv { scope ->
        // Given: Active session with a registered onDisconnected listener
        nodeManager.getOrEstablishContext()
        val disconnectDeferred = CompletableDeferred<Unit>()
        transport.setOnDisconnectedListener {
            disconnectDeferred.complete(Unit)
        }

        transport.connect(defaultConfig())
        awaitHandshake()
        val session = awaitSession()
        scope.runCurrent()
        assertTrue(transport.isConnected)

        // When: Socket is terminated
        session.incomingChannel.close(IOException("Connection reset"))
        scope.runCurrent()

        // Then: Disconnect listener is dispatched
        assertTrue(disconnectDeferred.isCompleted)
        assertFalse(transport.isConnected)

        teardown()
    }

    @Test
    fun should_notInvokeOnDisconnectedListener_whenConnectionFailsInitially() = runEnv { scope ->
        nodeManager.getOrEstablishContext()
        var disconnectedInvoked = false
        transport.setOnDisconnectedListener {
            disconnectedInvoked = true
        }

        // Given: Initial connect fails before handshake
        engine.failureOnConnect = IOException("Connection refused: localhost:8080")
        transport.connect(defaultConfig())
        scope.runCurrent()

        // Then: Disconnect listener is never invoked because no session was active
        assertFalse(disconnectedInvoked)
        assertFalse(transport.isConnected)

        teardown()
    }

    // -------------------------------------------------------------------------
    // Protocol / Security Verification
    // -------------------------------------------------------------------------

    @Test
    fun should_holdWssUrlInHandshakeRequest_whenSecureIsTrue() = runEnv {
        nodeManager.getOrEstablishContext()

        transport.connect(defaultConfig(isSecure = true))
        val request = awaitHandshake()

        assertEquals("wss", request.url.protocol.name)
        assertEquals(URLProtocol.WSS, request.url.protocol)

        teardown()
    }

    @Test
    fun should_holdWsUrlInHandshakeRequest_whenSecureIsFalse() = runEnv {
        nodeManager.getOrEstablishContext()

        transport.connect(defaultConfig(isSecure = false))
        val request = awaitHandshake()

        assertEquals("ws", request.url.protocol.name)
        assertEquals(URLProtocol.WS, request.url.protocol)

        teardown()
    }
}