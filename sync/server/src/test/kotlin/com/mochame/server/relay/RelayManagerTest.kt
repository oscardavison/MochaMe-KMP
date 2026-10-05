package com.mochame.server.relay

import com.mochame.server.utils.FakeWebSocketSession
import com.mochame.server.utils.ServerConfig
import com.mochame.utils.fixtures.TestPayloads
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.coroutines.backgroundScope
import io.kotest.engine.coroutines.testScheduler
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.io.IOException

@OptIn(DelicateCoroutinesApi::class)
class RelayManagerTest : FunSpec({
    coroutineTestScope = true

    // -------------------------------------------------------------------------
    // Registry to Session Lifecycle & Group Isolation
    // -------------------------------------------------------------------------

    test("should map node to group and return true on hasRegisteredSession when registered") {
        // Given:
        val fakeSession = FakeWebSocketSession(backgroundScope.coroutineContext)
        val handle = SessionHandle(
            nodeId = "node-alpha",
            groupId = "group-1",
            session = fakeSession
        )
        val relayManager = RelayManager()
        relayManager.hasRegisteredSession(handle) shouldBe false

        // When:
        relayManager.register(handle)

        relayManager.hasRegisteredSession(handle) shouldBe true
    }

    test("should maintain complete group isolation when operations occur in different groups") {
        // Given: Peers registered across two distinct groups
        val fakeSessionA = FakeWebSocketSession(backgroundScope.coroutineContext)
        val a = SessionHandle(nodeId = "node-1", groupId = "group-1", session = fakeSessionA)

        val fakeSessionB = FakeWebSocketSession(backgroundScope.coroutineContext)
        val b = SessionHandle(nodeId = "node-1", groupId = "group-2", session = fakeSessionB)

        val relayManager = RelayManager()
        relayManager.register(a)
        relayManager.register(b)

        // When: Node in group-1 is terminated and a broadcast is dispatched to group-1
        relayManager.terminate(a, CloseReason.Codes.NORMAL, "Teardown group 1")
        relayManager.broadcast(
            groupId = "group-1",
            excludeNodeId = "",
            watermark = 10L,
            data = TestPayloads.DEFAULT
        )

        // Then: Group 2's handle and state remain intact and untouched
        relayManager.hasRegisteredSession(a) shouldBe false
        relayManager.hasRegisteredSession(b) shouldBe true
        b.outboundChannel.isClosedForSend shouldBe false
        fakeSessionB.drainSentFrames().isEmpty() shouldBe true

        // And: Group 1 terminated
        relayManager.getActiveGroup("group-1") shouldBe null
        val reason = fakeSessionA.awaitCloseReason()
        reason.knownReason shouldBe CloseReason.Codes.NORMAL
        reason.message shouldBe "Teardown group 1"
        fakeSessionA.drainSentFrames().filterIsInstance<Frame.Close>().size shouldBe 1
        a.outboundChannel.isClosedForSend shouldBe true
    }

    context("Unregistered Handle Teardown") {
        test("should close handle safely without altering registry when handle was never registered") {
            // Given: A relay with one active session (node-active)
            val fakeSessionActive = FakeWebSocketSession(backgroundScope.coroutineContext)
            val handleActive = SessionHandle(
                nodeId = "node-active",
                groupId = "group-1",
                session = fakeSessionActive
            )
            val relayManager = RelayManager()
            relayManager.register(handleActive)

            // And: A handle that was never registered in the relay
            val fakeSessionUnregistered = FakeWebSocketSession(backgroundScope.coroutineContext)
            val unregisteredHandle = SessionHandle(
                nodeId = "node-unregistered",
                groupId = "group-1",
                session = fakeSessionUnregistered
            )

            // When: terminate() is called on the unregistered handle
            relayManager.terminate(
                handle = unregisteredHandle,
                code = CloseReason.Codes.PROTOCOL_ERROR,
                reason = "Auth handshake failed before register"
            )

            // Then: The unregistered handle is closed cleanly
            val closeReason = fakeSessionUnregistered.awaitCloseReason()
            closeReason.knownReason shouldBe CloseReason.Codes.PROTOCOL_ERROR
            closeReason.message shouldBe "Auth handshake failed before register"
            unregisteredHandle.outboundChannel.isClosedForSend shouldBe true

            // And: The registry remains intact
            relayManager.hasRegisteredSession(handleActive) shouldBe true
            val group = relayManager.getActiveGroup("group-1")
            group?.size shouldBe 1
            group?.get("node-active") shouldBe handleActive
            group?.containsKey("node-unregistered") shouldBe false
        }

        test("should close handle safely without altering registry when groupId does not exist") {
            // Given: An empty RelayManager and a handle belonging to an unmapped group
            val fakeSession = FakeWebSocketSession(backgroundScope.coroutineContext)
            val handle = SessionHandle(
                nodeId = "node-orphan",
                groupId = "group-ghost",
                session = fakeSession
            )
            val relayManager = RelayManager()

            // When: terminate() is invoked for a non-existent group
            relayManager.terminate(
                handle = handle,
                code = CloseReason.Codes.NORMAL,
                reason = "Closed before group registration"
            )

            // Then: The handle is closed without creating or modifying groups
            val closeReason = fakeSession.awaitCloseReason()
            closeReason.knownReason shouldBe CloseReason.Codes.NORMAL
            handle.outboundChannel.isClosedForSend shouldBe true
            relayManager.getActiveGroup("group-ghost") shouldBe null
        }
    }

    // -------------------------------------------------------------------------
    // Session Invariance
    // -------------------------------------------------------------------------

    test("should evict and close previous session with NORMAL when registering same nodeId") {
        // Given: A node has an active registered session handle (A1)
        val fakeSessionA1 = FakeWebSocketSession(backgroundScope.coroutineContext)
        val handleA1 = SessionHandle(
            nodeId = "node-alpha",
            groupId = "group-1",
            session = fakeSessionA1
        )
        val relayManager = RelayManager()
        relayManager.register(handleA1)

        // And: A new connection arrives for the same node (A2)
        val fakeSessionA2 = FakeWebSocketSession(backgroundScope.coroutineContext)
        val handleA2 = SessionHandle(
            nodeId = "node-alpha",
            groupId = "group-1",
            session = fakeSessionA2
        )

        // When: The new handle registers with the relay
        relayManager.register(handleA2)

        // Then: The old handle (A1) is evicted and closed with NORMAL
        relayManager.hasRegisteredSession(handleA1) shouldBe false
        val a1CloseReason = fakeSessionA1.awaitCloseReason()
        a1CloseReason.knownReason shouldBe CloseReason.Codes.NORMAL
        a1CloseReason.message shouldBe "Replaced by new connection"
        handleA1.outboundChannel.isClosedForSend shouldBe true
        handleA1.session.outgoing.isClosedForSend shouldBe true

        // And: The new handle (A2) is registered as the active session for that node
        relayManager.hasRegisteredSession(handleA2) shouldBe true
        relayManager.getActiveGroup("group-1")?.get("node-alpha") shouldBe handleA2
        handleA2.outboundChannel.isClosedForSend shouldBe false
        handleA2.session.outgoing.isClosedForSend shouldBe false
    }

    test("should retain active session when terminate is invoked with stale handle reference") {
        // Given: Node 'node-alpha' has connected (A1) and subsequently reconnected (A2)
        val fakeSessionA1 = FakeWebSocketSession(backgroundScope.coroutineContext)
        val handleA1 =
            SessionHandle(nodeId = "node-alpha", groupId = "group-1", session = fakeSessionA1)

        val fakeSessionA2 = FakeWebSocketSession(backgroundScope.coroutineContext)
        val handleA2 =
            SessionHandle(nodeId = "node-alpha", groupId = "group-1", session = fakeSessionA2)

        val relayManager = RelayManager()
        relayManager.register(handleA1)
        relayManager.register(handleA2)

        // When: The previous connection's CIO worker detects socket closure and calls terminate on stale handle A1
        relayManager.terminate(
            handle = handleA1,
            code = CloseReason.Codes.GOING_AWAY,
            reason = "Stale TCP transport disconnected"
        )

        // Then: Stale handle A1 teardown executes without evicting the active handle A2
        handleA1.outboundChannel.isClosedForSend shouldBe true
        relayManager.hasRegisteredSession(handleA2) shouldBe true
        relayManager.getActiveGroup("group-1")?.get("node-alpha") shouldBe handleA2
        handleA2.outboundChannel.isClosedForSend shouldBe false
    }

    // -------------------------------------------------------------------------
    // Broadcast Distribution / EnqueueResult Handling / Backpressure
    // -------------------------------------------------------------------------

    context("Broadcast Routing & Filtering") {
        test("should push frames to all peers in group while excluding excludeNodeId") {
            // Given: Three backfilled peers in the same group
            val fakeSession1 = FakeWebSocketSession(backgroundScope.coroutineContext)
            val handle1 =
                SessionHandle(nodeId = "node-1", groupId = "group-1", session = fakeSession1)
            handle1.completeBackfill(maxWatermark = 0L)

            val fakeSession2 = FakeWebSocketSession(backgroundScope.coroutineContext)
            val handle2 =
                SessionHandle(nodeId = "node-2", groupId = "group-1", session = fakeSession2)
            handle2.completeBackfill(maxWatermark = 0L)

            val fakeSession3 = FakeWebSocketSession(backgroundScope.coroutineContext)
            val handle3 =
                SessionHandle(nodeId = "node-3", groupId = "group-1", session = fakeSession3)
            handle3.completeBackfill(maxWatermark = 0L)

            val relayManager = RelayManager()
            relayManager.register(handle1)
            relayManager.register(handle2)
            relayManager.register(handle3)

            // When: Broadcasting an update originating from node-1
            relayManager.broadcast(
                groupId = "group-1",
                excludeNodeId = "node-1",
                watermark = 100L,
                data = TestPayloads.DEFAULT
            )
            testScheduler.runCurrent()

            // Then: Excluded node-1 receives no outbound frame
            fakeSession1.drainSentFrames().shouldBeEmpty()

            // And: Peers node-2 and node-3 receive the broadcast frame
            val sent2 = fakeSession2.drainSentFrames()
            sent2 shouldHaveSize 1
            sent2[0].data shouldBe TestPayloads.DEFAULT

            val sent3 = fakeSession3.drainSentFrames()
            sent3 shouldHaveSize 1
            sent3[0].data shouldBe TestPayloads.DEFAULT
        }

        test("should return silently without errors when broadcasting to non-existent groupId") {
            // Given: An empty RelayManager
            val relayManager = RelayManager()

            // When: Broadcasting to a non-existent group
            // Then: Function returns immediately without throwing exceptions
            relayManager.broadcast(
                groupId = "ghost-group",
                excludeNodeId = "",
                watermark = 10L,
                data = TestPayloads.DEFAULT
            )
        }
    }

    test("should evict slow peer correctly on EnqueueResult.OutboundSaturated and maintain fast peer on EnqueueResult.Success") {
        // Given: A backfilled peer whose outbound pipeline is saturated by transport backpressure
        val config = ServerConfig(
            outboundChannelCapacity = 1,
            outboundStagingCapacity = 5
        )
        val slowSession = FakeWebSocketSession(backgroundScope.coroutineContext)

        val slowPeer = SessionHandle(
            nodeId = "node-slow-outbound",
            groupId = "group-1",
            session = slowSession,
            config = config
        )
        slowPeer.completeBackfill(maxWatermark = 0L)

        val gate = CompletableDeferred<Unit>()
        slowSession.sendDelayGate = gate

        // Fill outbound capacity: 1st consumed by worker (blocked on gate), 2nd fills channel
        slowPeer.enqueueBroadcast(1L, Frame.Text("in-flight")) shouldBe EnqueueResult.Success
        testScheduler.runCurrent()
        slowPeer.enqueueBroadcast(2L, Frame.Text("buffered")) shouldBe EnqueueResult.Success

        // And: A valid fast peer
        val fastSession = FakeWebSocketSession(backgroundScope.coroutineContext)
        val fastPeer = SessionHandle(
            nodeId = "node-fast-outbound",
            groupId = "group-1",
            session = fastSession,
            config = config
        )
        fastPeer.completeBackfill(maxWatermark = 0L)

        val relayManager = RelayManager()
        relayManager.register(slowPeer)
        relayManager.register(fastPeer)

        // When: A broadcast is dispatched to the group, triggering OutboundSaturated on the slow peer
        relayManager.broadcast(
            groupId = "group-1",
            excludeNodeId = "",
            watermark = 3L,
            data = TestPayloads.DEFAULT
        )

        gate.complete(Unit)
        testScheduler.runCurrent() // trigger outbound workers

        // Then: Slow Peer is immediately unregistered from the active group with correct reason
        relayManager.hasRegisteredSession(slowPeer) shouldBe false
        val closeReason = slowSession.awaitCloseReason()
        closeReason.knownReason shouldBe CloseReason.Codes.TRY_AGAIN_LATER
        closeReason.message shouldBe "SLOW_CONSUMER: Outbound channel at capacity"
        slowPeer.outboundChannel.isClosedForSend shouldBe true
        slowSession.outgoing.isClosedForSend shouldBe true
        slowSession.drainSentFrames().first().shouldBeInstanceOf<Frame.Close>()

        // And: Fast Peer is alive and well
        relayManager.hasRegisteredSession(fastPeer) shouldBe true
        relayManager.getActiveGroup("group-1")?.get("node-fast-outbound") shouldBe fastPeer
        fastPeer.outboundChannel.isClosedForSend shouldBe false
        fastPeer.session.outgoing.isClosedForSend shouldBe false
        val emittedFrames = fastSession.drainSentFrames()
        emittedFrames.first().data shouldBe TestPayloads.DEFAULT
    }

    test("should evict peer and terminate with TRY_AGAIN_LATER on EnqueueResult.StagingSaturated") {
        // Given: A pre-backfill peer with saturated staging buffer
        val config = ServerConfig(
            outboundChannelCapacity = 5,
            outboundStagingCapacity = 2
        )
        val fakeSession = FakeWebSocketSession(backgroundScope.coroutineContext)
        val handle = SessionHandle(
            nodeId = "node-slow-staging",
            groupId = "group-1",
            session = fakeSession,
            config = config
        )
        val relayManager = RelayManager()
        relayManager.register(handle)

        // Fill staging buffer to maximum capacity (2)
        handle.enqueueBroadcast(1L, Frame.Text("staged-1")) shouldBe EnqueueResult.Success
        handle.enqueueBroadcast(2L, Frame.Text("staged-2")) shouldBe EnqueueResult.Success

        // When: A broadcast arrives during active catch-up exceeding staging capacity
        relayManager.broadcast(
            groupId = "group-1",
            excludeNodeId = "",
            watermark = 3L,
            data = TestPayloads.DEFAULT
        )

        // Then: Peer is evicted from the registry
        relayManager.hasRegisteredSession(handle) shouldBe false
        relayManager.getActiveGroup("group-1") shouldBe null

        // And: Peer is terminated with TRY_AGAIN_LATER and STAGING_OVERFLOW reason
        val closeReason = fakeSession.awaitCloseReason()
        closeReason.knownReason shouldBe CloseReason.Codes.TRY_AGAIN_LATER
        closeReason.message shouldBe "STAGING_OVERFLOW: Live peer broadcasts exceeded backfill buffer"
        relayManager.hasRegisteredSession(handle) shouldBe false
        handle.outboundChannel.isClosedForSend shouldBe true
        fakeSession.drainSentFrames().first().shouldBeInstanceOf<Frame.Close>()
    }

    test("should evict peer and terminate with INTERNAL_ERROR on EnqueueResult.Closed") {
        // Given: A registered peer whose outbound channel was closed from the kernel side
        val fakeSession = FakeWebSocketSession(backgroundScope.coroutineContext)
        val handle = SessionHandle(
            nodeId = "node-dead-socket",
            groupId = "group-1",
            session = fakeSession
        )
        handle.completeBackfill(maxWatermark = 0L)

        val relayManager = RelayManager()
        relayManager.register(handle)

        // Socket channel severs with an underlying cause
        handle.outboundChannel.close(IOException("Connection reset by peer"))
        testScheduler.runCurrent() // trigger outbound worker to blow up

        // When: A broadcast is dispatched to the dead session
        relayManager.broadcast(
            groupId = "group-1",
            excludeNodeId = "",
            watermark = 10L,
            data = TestPayloads.DEFAULT
        )

        // Then: Peer is evicted from the active registry
        relayManager.hasRegisteredSession(handle) shouldBe false

        // And: Peer is terminated with NORMAL and the underlying cause message
        val closeReason = fakeSession.awaitCloseReason()
        closeReason.knownReason shouldBe CloseReason.Codes.INTERNAL_ERROR
        closeReason.message shouldBe "Connection reset by peer"
        relayManager.hasRegisteredSession(handle) shouldBe false
        handle.outboundChannel.isClosedForSend shouldBe true

        val sentFrames = fakeSession.drainSentFrames()
        sentFrames.size shouldBe 1
        sentFrames.first().shouldBeInstanceOf<Frame.Close>()
    }

    // -------------------------------------------------------------------------
    // Multithreaded Lifecycle Contention
    // -------------------------------------------------------------------------

    test("should execute termination cleanly when called simultaneously") {
        // Given: A registered session handle and 8 parallel worker threads
        val fakeSession = autoClose(FakeWebSocketSession(Dispatchers.Default))
        val handle = SessionHandle(
            nodeId = "node-simultaneous-term",
            groupId = "group-1",
            session = fakeSession
        )
        handle.completeBackfill(maxWatermark = 0L)

        val relayManager = RelayManager()
        relayManager.register(handle)

        val threads = 8
        val readySignals = List(threads) { CompletableDeferred<Unit>() }
        val startGate = CompletableDeferred<Unit>()

        // When: Multiple threads (actor and CIO workers) call terminate() simultaneously
        val jobs = List(threads) { index ->
            launch(Dispatchers.Default) {
                readySignals[index].complete(Unit)
                startGate.await()

                relayManager.terminate(
                    handle = handle,
                    code = CloseReason.Codes.NORMAL,
                    reason = "Concurrent teardown call #$index"
                )
            }
        }

        readySignals.awaitAll()
        startGate.complete(Unit)
        jobs.joinAll()

        // Then: The node is unmapped and the empty group bucket is pruned
        relayManager.hasRegisteredSession(handle) shouldBe false
        relayManager.getActiveGroup("group-1") shouldBe null

        // And: SessionHandle's CAS ensures exactly one close frame is sent over the wire
        val closeReason = fakeSession.awaitCloseReason()
        closeReason.knownReason shouldBe CloseReason.Codes.NORMAL
        val closeFrames = fakeSession.drainSentFrames().filterIsInstance<Frame.Close>()
        closeFrames shouldHaveSize 1
        handle.outboundChannel.isClosedForSend shouldBe true
    }

    test("should leave new session registered when reconnection(A2) races with terminate(A1)") {
        // Given: An existing session handle (A1) registered for node-alpha
        val fakeSessionA1 = autoClose(FakeWebSocketSession(Dispatchers.Default))
        val handleA1 = SessionHandle(
            nodeId = "node-alpha",
            groupId = "group-1",
            session = fakeSessionA1
        )

        val relayManager = RelayManager()
        relayManager.register(handleA1)

        // And: A reconnecting session handle (A2) for the same node
        val fakeSessionA2 = autoClose(FakeWebSocketSession(Dispatchers.Default))
        val handleA2 = SessionHandle(
            nodeId = "node-alpha",
            groupId = "group-1",
            session = fakeSessionA2
        )

        val readySignals = List(2) { CompletableDeferred<Unit>() }
        val startGate = CompletableDeferred<Unit>()

        // When: CIO worker 2 registers A2 while CIO worker 1 terminates stale A1 concurrently
        val registerJob = launch(Dispatchers.Default) {
            readySignals[0].complete(Unit)
            startGate.await()
            relayManager.register(handleA2)
        }

        val terminateJob = launch(Dispatchers.Default) {
            readySignals[1].complete(Unit)
            startGate.await()
            relayManager.terminate(
                handle = handleA1,
                code = CloseReason.Codes.GOING_AWAY,
                reason = "Stale connection terminated"
            )
        }

        readySignals.awaitAll()
        startGate.complete(Unit)
        registerJob.join()
        terminateJob.join()

        // Then: Identity comparison (===) prevents stale terminate(A1) from evicting A2
        relayManager.hasRegisteredSession(handleA2) shouldBe true
        relayManager.hasRegisteredSession(handleA1) shouldBe false
        relayManager.getActiveGroup("group-1")?.get("node-alpha") shouldBe handleA2

        // And: Stale handle A1 is closed, while active handle A2 remains open
        handleA1.outboundChannel.isClosedForSend shouldBe true
        handleA2.outboundChannel.isClosedForSend shouldBe false
    }

    test("should complete broadcast without throwing or deadlocking while peers register and terminate concurrently") {
        // Given: A high-capacity group with active peers and a dedicated single-threaded actor dispatcher
        val actorDispatcher = Dispatchers.Default.limitedParallelism(1)
        val config = ServerConfig(
            outboundChannelCapacity = 1000,
            outboundStagingCapacity = 1000
        )
        val relayManager = RelayManager()

        // Seed baseline peers in group-1
        for (i in 1..10) {
            val session = FakeWebSocketSession(Dispatchers.Default)
            val handle = SessionHandle(
                nodeId = "base-node-$i",
                groupId = "group-1",
                session = session,
                config = config
            )
            handle.completeBackfill(0L)
            relayManager.register(handle)
        }

        val readySignals = List(3) { CompletableDeferred<Unit>() }
        val startGate = CompletableDeferred<Unit>()

        // When: The Database Actor broadcasts while CIO workers register and terminate peers simultaneously
        val actorBroadcasterJob = launch(actorDispatcher) {
            readySignals[0].complete(Unit)
            startGate.await()
            for (w in 1L..100L) {
                relayManager.broadcast(
                    groupId = "group-1",
                    excludeNodeId = "actor-node",
                    watermark = w,
                    data = TestPayloads.DEFAULT
                )
            }
        }

        val cioRegisterJob = launch(Dispatchers.Default) {
            readySignals[1].complete(Unit)
            startGate.await()
            for (i in 1..40) {
                val session = FakeWebSocketSession(Dispatchers.Default)
                val handle = SessionHandle(
                    nodeId = "churn-node-$i",
                    groupId = "group-1",
                    session = session,
                    config = config
                )
                handle.completeBackfill(0L)
                relayManager.register(handle)
            }
        }

        val cioTerminateJob = launch(Dispatchers.Default) {
            readySignals[2].complete(Unit)
            startGate.await()
            for (i in 1..40) {
                val group = relayManager.getActiveGroup("group-1")
                val victim = group?.values?.firstOrNull { it.nodeId.startsWith("base-node-") }
                if (victim != null) {
                    relayManager.terminate(
                        handle = victim,
                        code = CloseReason.Codes.NORMAL,
                        reason = "Churn termination"
                    )
                }
            }
        }

        readySignals.awaitAll()
        startGate.complete(Unit)

        // Then: All concurrent jobs join without ConcurrentModificationException or deadlocks
        actorBroadcasterJob.join()
        cioRegisterJob.join()
        cioTerminateJob.join()
    }

})