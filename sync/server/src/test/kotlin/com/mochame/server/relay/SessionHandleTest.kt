package com.mochame.server.relay

import com.mochame.server.utils.FakeWebSocketSession
import com.mochame.server.utils.ServerConfig
import com.mochame.support.awaitCondition
import io.kotest.assertions.throwables.shouldThrow
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
@DelicateCoroutinesApi
class SessionHandleTest : FunSpec({

    val smallBufferConfig = ServerConfig.Default(
        outboundChannelCapacity = 2,
        outboundStagingCapacity = 5
    )
    coroutineTestScope = true

    // -------------------------------------------------------------------------
    // EnqueueResult (OutboundChannel, Staging, & Backpressure Paths)
    // -------------------------------------------------------------------------

    context("EnqueueResult Domain") {

        context("EnqueueResult.Success") {
            test("Enqueueing when backfilled forwards directly through outboundChannel to session") {
                // Given: Session is backfilled and transitioned to live broadcasting
                val fakeSession = FakeWebSocketSession(backgroundScope.coroutineContext)
                val handle = SessionHandle(
                    nodeId = "node-alpha",
                    groupId = "group-1",
                    session = fakeSession
                )

                val backfillResult = handle.completeBackfill(maxWatermark = 0L)
                backfillResult shouldBe BackfillResult.Success
                handle.isBackfilled shouldBe true

                // When: live broadcast is triggered by peers
                val accepted1 = handle.enqueueBroadcast(100L, Frame.Text("delta-1"))
                val accepted2 = handle.enqueueBroadcast(101L, Frame.Text("delta-2"))

                // Then:
                accepted1 shouldBe EnqueueResult.Success
                accepted2 shouldBe EnqueueResult.Success

                testScheduler.runCurrent()

                val sent = fakeSession.drainSentFrames()
                sent shouldHaveSize 2
                sent[0].shouldBeInstanceOf<Frame.Text>().readText() shouldBe "delta-1"
                sent[1].shouldBeInstanceOf<Frame.Text>().readText() shouldBe "delta-2"
            }
        }

        context("EnqueueResult.OutboundSaturated") {
            test("When outboundChannel fills up, enqueueBroadcast returns failure without blocking") {
                // Given: Simulating CIO worker suspension as socket channel is full
                val fakeSession = FakeWebSocketSession(backgroundScope.coroutineContext)
                val handle = SessionHandle(
                    nodeId = "node-slow",
                    groupId = "group-1",
                    session = fakeSession,
                    config = smallBufferConfig // outboundChannelCapacity = 2
                )
                handle.completeBackfill(maxWatermark = 0L)

                val gate = CompletableDeferred<Unit>()
                fakeSession.sendDelayGate = gate

                // When & Then:
                // First frame is immediately consumed by the CIO worker and gets suspended at fakeSession.send()
                val enqueued1 = handle.enqueueBroadcast(100L, Frame.Text("msg-1"))
                enqueued1 shouldBe EnqueueResult.Success
                testScheduler.runCurrent()

                // Next 2 frames fill the outboundChannel buffer
                val enqueued2 = handle.enqueueBroadcast(101L, Frame.Text("msg-2"))
                val enqueued3 = handle.enqueueBroadcast(102L, Frame.Text("msg-3"))
                enqueued2 shouldBe EnqueueResult.Success
                enqueued3 shouldBe EnqueueResult.Success

                // Backpressure on outboundChannel pushes back on the database actor (who then calls to terminate)
                val enqueued4 = handle.enqueueBroadcast(103L, Frame.Text("msg-4"))
                enqueued4 shouldBe EnqueueResult.OutboundSaturated
            }
        }

        context("EnqueueResult.StagingSaturated") {
            test("Flooding staging buffer rejects excess frames without corrupting order") {
                // Given: Staging capacity clamped to 3
                val config = ServerConfig.Default(
                    outboundChannelCapacity = 10,
                    outboundStagingCapacity = 3
                )
                val fakeSession = FakeWebSocketSession(backgroundScope.coroutineContext)
                val handle = SessionHandle(
                    nodeId = "node-overflow",
                    groupId = "group-1",
                    session = fakeSession,
                    config = config
                )

                // When: Enqueueing 4 frames before backfill cutover
                val accepted1 = handle.enqueueBroadcast(10L, Frame.Text("msg-1"))
                val accepted2 = handle.enqueueBroadcast(20L, Frame.Text("msg-2"))
                val accepted3 = handle.enqueueBroadcast(30L, Frame.Text("msg-3"))
                val accepted4 = handle.enqueueBroadcast(40L, Frame.Text("msg-4"))

                // Then: First 3 are accepted, 4th is rejected due to staging saturation
                accepted1 shouldBe EnqueueResult.Success
                accepted2 shouldBe EnqueueResult.Success
                accepted3 shouldBe EnqueueResult.Success
                accepted4 shouldBe EnqueueResult.StagingSaturated

                // And: Completing backfill delivers the 3 preserved frames uncorrupted
                handle.completeBackfill(maxWatermark = 0L) shouldBe BackfillResult.Success
                testScheduler.runCurrent()
                val sentFrames = fakeSession.drainSentFrames()
                sentFrames shouldHaveSize 3
                sentFrames.map { (it as Frame.Text).readText() } shouldBe listOf(
                    "msg-1",
                    "msg-2",
                    "msg-3"
                )
            }
        }

        context("EnqueueResult.Closed (Post- & Pre-Backfill Rejection)") {
            test("should return EnqueueResult.Closed on enqueueBroadcast when session is closed post-backfill") {
                // Given: A session that has completed backfill and is subsequently closed
                val fakeSession = FakeWebSocketSession(backgroundScope.coroutineContext)
                val handle = SessionHandle(
                    nodeId = "node-outbound-closed",
                    groupId = "group-1",
                    session = fakeSession
                )
                handle.completeBackfill(maxWatermark = 0L)
                handle.close(CloseReason.Codes.GOING_AWAY, "Server restarting")

                // When: A producer attempts to broadcast a frame to the terminated session
                val result = handle.enqueueBroadcast(100L, Frame.Text("drop-me"))

                // Then: Outbound channel rejection is translated to EnqueueResult.Closed
                result.shouldBeInstanceOf<EnqueueResult.Closed>()
                result.isSuccess shouldBe false
            }

            test("should return EnqueueResult.Closed on enqueueBroadcast when session is closed pre-backfill") {
                // Given: A session that is closed BEFORE backfill completes
                val fakeSession = FakeWebSocketSession(backgroundScope.coroutineContext)
                val handle = SessionHandle(
                    nodeId = "node-pre-backfill-closed",
                    groupId = "group-1",
                    session = fakeSession
                )
                handle.close(CloseReason.Codes.NORMAL, "Cancelled before sync")

                // When: Broadcasting to a session that closed mid-handshake
                val result = handle.enqueueBroadcast(10L, Frame.Text("staged-drop"))

                // Then: The handle must reject the frame rather than staging into a zombie buffer
                result.shouldBeInstanceOf<EnqueueResult.Closed>()
                result.isSuccess shouldBe false
                handle.isBackfilled shouldBe false
            }
        }
    }

    // -------------------------------------------------------------------------
    // BackfillResult Domain (Historical Catch-up & Watermark Reconciliation)
    // -------------------------------------------------------------------------
    context("BackfillResult Domain") {

        context("BackfillResult.Success (Deduplication & Buffer Cutover)") {
            test("Watermarks <= maxWatermark are discarded, watermarks > maxWatermark are sent") {
                // Given: Multiple frames enqueued prior to backfill completion
                val fakeSession = FakeWebSocketSession(backgroundScope.coroutineContext)
                val handle = SessionHandle(
                    nodeId = "node-dedup",
                    groupId = "group-1",
                    session = fakeSession
                )

                handle.enqueueBroadcast(50L, Frame.Text("delta-50"))
                handle.enqueueBroadcast(100L, Frame.Text("delta-100"))
                handle.enqueueBroadcast(150L, Frame.Text("delta-150"))
                handle.enqueueBroadcast(200L, Frame.Text("delta-200"))

                // When: Backfill completes covering watermarks up to 100
                val result = handle.completeBackfill(maxWatermark = 100L)
                testScheduler.runCurrent()

                // Then: Staged frames <= 100 are dropped, surviving frames are dispatched in order
                result shouldBe BackfillResult.Success
                handle.isBackfilled shouldBe true

                val sentFrames = fakeSession.drainSentFrames()
                sentFrames shouldHaveSize 2
                sentFrames[0].shouldBeInstanceOf<Frame.Text>().readText() shouldBe "delta-150"
                sentFrames[1].shouldBeInstanceOf<Frame.Text>().readText() shouldBe "delta-200"
            }

            test("Incoming frames while send() suspends are drained before isBackfilled = true") {
                // Given: Outbound channel throttled to force completeBackfill to suspend on outboundChannel.send()
                val config = ServerConfig.Default(
                    outboundChannelCapacity = 1,
                    outboundStagingCapacity = 10
                )
                val fakeSession = FakeWebSocketSession(UnconfinedTestDispatcher(testScheduler))
                val gate = CompletableDeferred<Unit>()
                fakeSession.sendDelayGate = gate

                val handle = SessionHandle(
                    nodeId = "node-concurrent-cutover",
                    groupId = "group-1",
                    session = fakeSession,
                    config = config
                )

                // Stage 3 frames: worker takes 1st and blocks on gate; 2nd fills outbound channel (cap 1); 3rd suspends send()
                handle.enqueueBroadcast(10L, Frame.Text("staged-1"))
                handle.enqueueBroadcast(20L, Frame.Text("staged-2"))
                handle.enqueueBroadcast(30L, Frame.Text("staged-3"))

                val backfillJob = async { handle.completeBackfill(maxWatermark = 0L) }

                // When: A live broadcast arrives while completeBackfill() is suspended outside the lock
                val acceptedConcurrent = handle.enqueueBroadcast(40L, Frame.Text("concurrent-40"))
                acceptedConcurrent shouldBe EnqueueResult.Success
                handle.isBackfilled shouldBe false

                // Release worker 1 and outgoing send pipeline
                gate.complete(Unit)

                // Then: Cutover completes successfully and drains both original and concurrent frames in watermark order
                backfillJob.await() shouldBe BackfillResult.Success
                handle.isBackfilled shouldBe true

                val sentFrames = fakeSession.drainSentFrames()
                sentFrames shouldHaveSize 4
                sentFrames.map { (it as Frame.Text).readText() } shouldBe listOf(
                    "staged-1",
                    "staged-2",
                    "staged-3",
                    "concurrent-40"
                )
            }
        }

        context("BackfillResult.ChannelClosed (Teardown Interruption)") {
            test("Outbound channel closure pre backfill, yields BackfillResult.Closed to caller") {
                // Given: Outbound channel saturated so completeBackfill suspends on send()
                val config = ServerConfig.Default(
                    outboundChannelCapacity = 1,
                    outboundStagingCapacity = 5
                )
                val fakeSession = FakeWebSocketSession(UnconfinedTestDispatcher(testScheduler))

                val handle = SessionHandle(
                    nodeId = "node-cutover-cancellation",
                    groupId = "group-1",
                    session = fakeSession,
                    config = config
                )
                handle.outboundChannel.close()

                handle.enqueueBroadcast(10L, Frame.Text("frame-1"))
                handle.enqueueBroadcast(20L, Frame.Text("frame-2"))
                handle.enqueueBroadcast(30L, Frame.Text("frame-3"))

                // Then: Backfill loop catches ClosedSendChannelException and terminates with ChannelClosed result
                handle.completeBackfill(0L).shouldBeInstanceOf<BackfillResult.ChannelClosed>()
                handle.isBackfilled shouldBe false
            }
        }

        context("BackfillResult.Failure (Transport Exception Handoff)") {
            test("Outbound channel exception mid-cutover, yields BackfillResult.Failure to caller") {
                // Given: Outbound channel saturated so completeBackfill suspends on send()
                val config = ServerConfig.Default(
                    outboundChannelCapacity = 1,
                    outboundStagingCapacity = 5
                )
                val fakeSession = FakeWebSocketSession(UnconfinedTestDispatcher(testScheduler))
                val gate = CompletableDeferred<Unit>()
                fakeSession.sendDelayGate = gate

                val handle = SessionHandle(
                    nodeId = "node-cutover-cancellation",
                    groupId = "group-1",
                    session = fakeSession,
                    config = config
                )

                handle.enqueueBroadcast(10L, Frame.Text("frame-1"))
                handle.enqueueBroadcast(20L, Frame.Text("frame-2"))
                handle.enqueueBroadcast(30L, Frame.Text("frame-3"))

                val backfillJob = async { handle.completeBackfill(maxWatermark = 0L) }

                // When: Outbound channel is canceled while completeBackfill is suspended awaiting channel capacity
                handle.outboundChannel.close(IOException("Client connection drop"))
                gate.complete(Unit)

                // Then: Backfill loop catches ClosedSendChannelException and terminates with ChannelClosed result
                backfillJob.await().shouldBeInstanceOf<BackfillResult.Failure>()
                handle.isBackfilled shouldBe false
            }
        }
    }

    // -------------------------------------------------------------------------
    // Cancellation Propagation & Fault Isolation
    // -------------------------------------------------------------------------

    test("Worker rethrows CancellationException and cleans up without dispatching close frames") {
        val socketJob = Job()
        val fakeSession = FakeWebSocketSession(backgroundScope.coroutineContext + socketJob)
        val handle = SessionHandle(
            nodeId = "node-cancelled",
            groupId = "group-1",
            session = fakeSession
        )
        handle.completeBackfill(maxWatermark = 0L)
        testScheduler.runCurrent() // CIO outbound worker suspends on send()

        // When: Server worker directly cancels the websocket coroutine
        socketJob.cancel()
        testScheduler.runCurrent()

        // Then:
        fakeSession.outgoing.isClosedForSend shouldBe true
        handle.outboundChannel.isClosedForSend shouldBe true
        // No close frame was cleanly pushed over the wire because the scope itself was cancelled
        fakeSession.drainSentFrames().shouldBeEmpty()
        handle.enqueueBroadcast(0, Frame.Text("payload"))
            .shouldBeInstanceOf<EnqueueResult.Closed>()
    }

    test("Outbound channel cancellation exception mid-cutover, propagates cancellation") {
        // Given: Outbound channel saturated so completeBackfill suspends on send()

        val fakeSession = FakeWebSocketSession(UnconfinedTestDispatcher(testScheduler))
        val gate = CompletableDeferred<Unit>()
        fakeSession.sendDelayGate = gate

        val handle = SessionHandle(
            nodeId = "node-cutover-cancellation",
            groupId = "group-1",
            session = fakeSession
        )

        handle.enqueueBroadcast(10L, Frame.Text("frame-1"))
        handle.enqueueBroadcast(20L, Frame.Text("frame-2"))
        handle.enqueueBroadcast(30L, Frame.Text("frame-3"))

        val backfillJob = async { handle.completeBackfill(maxWatermark = 0L) }

        // When: Outbound channel is closed while completeBackfill is suspended awaiting channel capacity
        handle.outboundChannel.close(CancellationException("Channel abruptly closed"))
        gate.complete(Unit)

        // Then: Backfill loop catches ClosedSendChannelException and terminates with ChannelClosed result
        shouldThrow<CancellationException> {
            backfillJob.await()
        }
        handle.isBackfilled shouldBe false
    }


    test("When session.send throws, worker triggers close(INTERNAL_ERROR) and cancels channel") {
        // Given: Simulated TCP socket teardown
        val fakeSession = FakeWebSocketSession(backgroundScope.coroutineContext)
        val handle = SessionHandle(
            nodeId = "node-broken-connection",
            groupId = "group-1",
            session = fakeSession
        )
        handle.completeBackfill(maxWatermark = 0L)
        fakeSession.sendException = IOException("Client terminated connection")

        // When: DatabaseActor plants the seed to hit the exception passed across the JNI boundary
        handle.enqueueBroadcast(100L, Frame.Text("payload"))
            .shouldBeInstanceOf<EnqueueResult.Success>()

        // Then: worker caught the exception, closed correctly, and canceled the channel
        val closeReason = fakeSession.awaitCloseReason()
        closeReason.knownReason shouldBe CloseReason.Codes.INTERNAL_ERROR
        closeReason.message shouldBe "Client terminated connection"
        handle.outboundChannel.isClosedForSend shouldBe true
        // Subsequent enqueue calls must fail cleanly
        val rejected = handle.enqueueBroadcast(101L, Frame.Text("post-crash-payload"))
        rejected.shouldBeInstanceOf<EnqueueResult.Closed>()
    }

    // -------------------------------------------------------------------------
    // Teardown Lifecycle, CAS Operations & Monotonicity
    // -------------------------------------------------------------------------


    test("should cancel outbound channel, terminate worker, and transmit close frame on close") {
        // Given: An active backfilled session with a running outbound worker
        val fakeSession = FakeWebSocketSession(backgroundScope.coroutineContext)
        val handle = SessionHandle(
            nodeId = "node-teardown-clean",
            groupId = "group-1",
            session = fakeSession
        )
        handle.completeBackfill(maxWatermark = 0L)

        // When: Connection closure is initiated manually
        fakeSession.outgoing.isClosedForSend shouldBe false
        handle.close(code = CloseReason.Codes.NORMAL, reason = "Client logout")

        // Then: The close handshake frame is transmitted over the wire with exact reason and code
        val capturedReason = fakeSession.awaitCloseReason()
        capturedReason.knownReason shouldBe CloseReason.Codes.NORMAL
        capturedReason.message shouldBe "Client logout"
        fakeSession.outgoing.isClosedForSend shouldBe true
        handle.outboundChannel.isClosedForSend shouldBe true
    }

    test("should execute close handshake exactly once when close is called concurrently across threads") {
        // Given: An active session exposed to multithreaded contention
        val fakeSession =
            autoClose(FakeWebSocketSession(UnconfinedTestDispatcher(testScheduler)))
        val handle = SessionHandle(
            nodeId = "node-concurrent-close",
            groupId = "group-1",
            session = fakeSession
        )
        handle.completeBackfill(maxWatermark = 0L)

        val threads = 8
        val readySignals = List(threads) { CompletableDeferred<Unit>() }
        val startGate = CompletableDeferred<Unit>()

        val jobs = List(threads) { index ->
            launch(Dispatchers.Default) {
                readySignals[index].complete(Unit)
                startGate.await()

                handle.close(
                    code = CloseReason.Codes.NORMAL,
                    reason = "Concurrent teardown call #$index"
                )
            }
        }

        // When: Multiple threads concurrently call close()
        readySignals.awaitAll()
        startGate.complete(Unit)
        jobs.joinAll()

        // Then: AtomicBoolean CAS guarantees exactly one close frame was sent
        val closeReason = fakeSession.awaitCloseReason()
        closeReason.knownReason shouldBe CloseReason.Codes.NORMAL
        // And: Only 1 close frame exists in the transport output buffer
        val sentFrames = fakeSession.drainSentFrames().filterIsInstance<Frame.Close>()
        sentFrames shouldHaveSize 1
    }


    test("should process staged frames and switch to live broadcast seamlessly under concurrent actor broadcasts").config(
        invocations = 100,
        timeout = 10.seconds
    ) {
        FakeWebSocketSession(Dispatchers.Default).use { fakeSession ->
            // Given: 4 backfilled, 4 duplicates, 4+ staged frames
            val handle = SessionHandle(
                nodeId = "node-cutover-suspension",
                groupId = "group-1",
                session = fakeSession
            )

            val stagedStart = 1
            val backfillCutoff = 4L
            val stagedEnd = 8L
            val broadcastEnd = 50

            val expectedWatermarks = ((backfillCutoff + 1L)..broadcastEnd).toList()
            val expectedCount = expectedWatermarks.size

            val enqueueResults = mutableListOf<EnqueueResult>()

            // And: Seed staged frames (period between peer registration and its backfill database snapshot)
            for (w in stagedStart..stagedEnd) {
                handle.enqueueBroadcast(w, Frame.Text("$w"))
            }

            val startGate = CompletableDeferred<Unit>()
            val readyUps = List(2) { CompletableDeferred<Unit>() }

            // When: Actor broadcasts while completeBackfill starts and runs
            val cioWorkerBackfillJob = launch(Dispatchers.Default) {
                readyUps[0].complete(Unit)
                startGate.await()
                handle.completeBackfill(backfillCutoff)
            }

            val actorBroadcasterJob = launch(Dispatchers.Default.limitedParallelism(1)) {
                readyUps[1].complete(Unit)
                startGate.await()
                for (w in (stagedEnd + 1L)..broadcastEnd) {
                    enqueueResults.add(handle.enqueueBroadcast(w, Frame.Text("$w")))
                }
            }

            readyUps.awaitAll()
            startGate.complete(Unit)
            cioWorkerBackfillJob.join()
            actorBroadcasterJob.join()

            // Then: Output must contain all non-deduped frames in exact sequence
            val receivedFrames = mutableListOf<Frame>()
            awaitCondition(
                pollInterval = 5.milliseconds,
                message = "Expected $expectedCount frames to land in fakeSession"
            ) {
                receivedFrames.addAll(fakeSession.drainSentFrames())
                receivedFrames.size >= expectedCount
            }

            val receivedWatermarks = receivedFrames
                .filterIsInstance<Frame.Text>()
                .map { it.readText().toLong() }

            receivedWatermarks shouldBe expectedWatermarks
            receivedWatermarks.toSet().size shouldBe expectedCount
            handle.isBackfilled shouldBe true

            val enqueueResultSet = enqueueResults.toSet()
            enqueueResultSet.size shouldBe 1
            enqueueResultSet.first() shouldBe EnqueueResult.Success
        }
    }
})