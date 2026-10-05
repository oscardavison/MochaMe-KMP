package com.mochame.server.relay

import com.mochame.server.database.ServerDatabase
import com.mochame.server.utils.FakeWebSocketSession
import com.mochame.server.utils.ServerConfig
import com.mochame.server.utils.createWriteIntent
import com.mochame.server.utils.fakeSession
import com.mochame.sync.protocol.WireFrame
import com.mochame.sync.protocol.WireFrameFactory
import com.mochame.utils.fixtures.AutoIncrementFakeTimeUtils
import io.kotest.core.spec.style.FunSpec
import io.kotest.core.test.TestScope
import io.kotest.engine.coroutines.backgroundScope
import io.kotest.engine.coroutines.testScheduler
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import java.io.File
import java.util.UUID
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext


@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
class DatabaseActorTest : FunSpec({

    coroutineTestScope = true

    val tempDir = tempdir()
    val dbFile = File(tempDir, "actor_test.db")
    val clock = AutoIncrementFakeTimeUtils()
    val relayManager = RelayManager()
    val db = ServerDatabase(
        path = dbFile.absolutePath,
        clock = clock,
        dispatcher = Dispatchers.Unconfined // Matches production limited parallelism
    )

    afterSpec {
        db.close()
    }

    // --- Helpers ---

    fun TestScope.createDatabaseActor(
        config: ServerConfig = ServerConfig.Default,
        dispatcher: CoroutineDispatcher = this.coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
    ) = DatabaseActor(
        database = db,
        relayManager = relayManager,
        config = config,
        scope = this.backgroundScope,
        dispatcher = dispatcher
    )

    suspend fun TestScope.initializePeer(
        nodeId: String,
        groupId: String,
        context: CoroutineContext = this.backgroundScope.coroutineContext,
        config: ServerConfig = ServerConfig.Default
    ): SessionHandle {
        val session = FakeWebSocketSession(context)
        val handle = SessionHandle(nodeId, groupId, session, config)

        handle.completeBackfill(0L)
        relayManager.register(handle)
        return handle
    }


    // -------------------------------------------------------------------------
    // Channel to Batch Chunking
    // -------------------------------------------------------------------------

    test("should flush intent immediately when channel contains fewer items than client ceiling") {
        // Given: Solitary write intent and actor configured with maxBroadcastingBatchSize = 10
        val actor = createDatabaseActor(config = ServerConfig(maxBroadcastingBatchSize = 10))
        val groupId = "group-${UUID.randomUUID()}"
        val sender = initializePeer("node-solitary", groupId)
        val intent = createWriteIntent(sender, batchId = 101L)

        // When:
        actor.writeChannel.send(intent)
        testScheduler.runCurrent() // outbound worker starts, ships from outbound to outgoing

        // Then: Immediate flush commits to database and delivers matching ACK frame to sender
        val storedDeltas = db.getDeltasSince(groupId, excludeNodeId = "none", sinceWatermark = 0L)
        storedDeltas.size shouldBe 1

        val frames = sender.fakeSession.drainSentFrames()
        val ack = WireFrameFactory.unwrap(frames.first().data).shouldBeInstanceOf<WireFrame.Ack>()
        frames.size shouldBe 1
        ack.batchId shouldBe 101L
        ack.watermark shouldBe storedDeltas.first().watermark
    }

    test("should clamp client to maxBroadcastingBatchSize and partition backlog across discrete commits") {
        // Given: Actor configured with maxBroadcastingBatchSize = 3, and a single peer submitting 5 intents
        val chunkSize = 3
        val totalIntents = 5
        val actor = createDatabaseActor(config = ServerConfig(maxBroadcastingBatchSize = chunkSize))
        val groupId = "group-${UUID.randomUUID()}"
        val peer = initializePeer("node-batcher", groupId)

        val intents = (1..totalIntents).map { index ->
            createWriteIntent(handle = peer, batchId = index.toLong())
        }

        // When: Submitting all intents into writeChannel
        for (intent in intents) {
            actor.writeChannel.send(intent)
        }
        testScheduler.runCurrent()

        // Then: Storage confirms execution was partitioned into batches of 3 and 2 via transaction timestamps
        db.withWriteConnection { conn ->
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery(
                    """
                SELECT created_at, COUNT(*) 
                FROM sync_change_log 
                WHERE group_id = '$groupId' 
                GROUP BY created_at 
                ORDER BY MIN(watermark) ASC;
                """.trimIndent()
                )
                val batchSizes = mutableListOf<Int>()
                while (rs.next()) {
                    batchSizes.add(rs.getInt(2))
                }
                batchSizes shouldBe listOf(chunkSize, totalIntents - chunkSize)
            }
        }

        // And: All 5 ACKs were dispatched back to the sender
        val frames = peer.fakeSession.awaitFrames(totalIntents)
        frames.size shouldBe totalIntents
    }


    test("should preserve queue sequence and map positional watermarks to respective senders when batching multiple intents") {
        // Given: 3 registered peers in the same group and sequential intents submitted in order A -> B -> C
        val actor = createDatabaseActor(config = ServerConfig(maxBroadcastingBatchSize = 10))
        val groupId = "group-${UUID.randomUUID()}"
        val peerA = initializePeer("node-A", groupId)
        val peerB = initializePeer("node-B", groupId)
        val peerC = initializePeer("node-C", groupId)

        val intentA = createWriteIntent(peerA, 101L, "payload-A".encodeToByteArray())
        val intentB = createWriteIntent(peerB, 102L, "payload-B".encodeToByteArray())
        val intentC = createWriteIntent(peerC, 103L, "payload-C".encodeToByteArray())

        // When: Intents are queued sequentially into writeChannel
        actor.writeChannel.send(intentA)
        actor.writeChannel.send(intentB)
        actor.writeChannel.send(intentC)
        testScheduler.runCurrent()

        // Then: Storage confirms exact sequential order preservation
        val stored = db.getDeltasSince(groupId, excludeNodeId = "none", sinceWatermark = 0L)
        stored.size shouldBe 3
        stored.map { it.payload.decodeToString() } shouldContainExactly listOf(
            "payload-A",
            "payload-B",
            "payload-C"
        )

        val watermarks = stored.map { it.watermark }
        watermarks[0] shouldBeLessThan watermarks[1]
        watermarks[1] shouldBeLessThan watermarks[2]

        // And: Positional Ack mapping guarantees each sender receives its matching watermark
        val ackA = WireFrameFactory.unwrap(peerA.fakeSession.drainSentFrames().first().data)
            .shouldBeInstanceOf<WireFrame.Ack>()
        ackA.batchId shouldBe 101L
        ackA.watermark shouldBe watermarks[0]

        val ackB = WireFrameFactory.unwrap(peerB.fakeSession.drainSentFrames()[1].data)
            .shouldBeInstanceOf<WireFrame.Ack>()
        ackB.batchId shouldBe 102L
        ackB.watermark shouldBe watermarks[1]

        val ackC = WireFrameFactory.unwrap(peerC.fakeSession.drainSentFrames()[2].data)
            .shouldBeInstanceOf<WireFrame.Ack>()
        ackC.batchId shouldBe 103L
        ackC.watermark shouldBe watermarks[2]
    }

    // -------------------------------------------------------------------------
    // Peer Outbound Channel Routing
    // -------------------------------------------------------------------------

    test("should route ACK to origin sender and broadcast deltas exclusively to peers in group") {
        // Given: 3 registered peers in the same group and 1 intent from peer 1
        val actor = createDatabaseActor()
        val groupId = "group-${UUID.randomUUID()}"
        val sender = initializePeer("sender", groupId)
        val peerA = initializePeer("peer-A", groupId)
        val peerB = initializePeer("peer-B", groupId)

        val batchId = 99L
        val payload = "delta-payload".encodeToByteArray()
        val intent = createWriteIntent(handle = sender, batchId = batchId, payload = payload)

        // When: Intent is processed
        actor.writeChannel.send(intent)
        testScheduler.runCurrent()
        val stored = db.getDeltasSince(groupId, excludeNodeId = "none", sinceWatermark = 0L)
        val assignedWatermark = stored.single().watermark

        // Then: Sender receives Ack only
        val senderFrames =
            sender.fakeSession.drainSentFrames().map { WireFrameFactory.unwrap(it.data) }
        val ack = senderFrames.first().shouldBeInstanceOf<WireFrame.Ack>()
        ack.batchId shouldBe batchId
        ack.watermark shouldBe assignedWatermark
        senderFrames.filterIsInstance<WireFrame.Delta>().shouldBeEmpty()

        // And: Peers receive Deltas only
        for (peer in listOf(peerA, peerB)) {
            val peerFrames =
                peer.fakeSession.drainSentFrames().map { WireFrameFactory.unwrap(it.data) }
            val delta = peerFrames.first().shouldBeInstanceOf<WireFrame.Delta>()
            delta.watermark shouldBe assignedWatermark
            delta.payload shouldBe payload
            peerFrames.filterIsInstance<WireFrame.Ack>().shouldBeEmpty()
        }
    }

    test("should isolate broadcast delivery by group and enforce author self-exclusion when delta is dispatched") {
        // Given: Sender and Peer in Group-Alpha, and an isolated Foreign Peer in Group-Beta
        val actor = createDatabaseActor()
        val groupAlpha = "group-alpha-${UUID.randomUUID()}"
        val groupBeta = "group-beta-${UUID.randomUUID()}"

        val senderAlpha = initializePeer("sender-alpha", groupAlpha)
        val peerAlpha = initializePeer("peer-alpha", groupAlpha)
        val foreignPeerBeta = initializePeer("peer-beta", groupBeta)

        val batchId = 50L
        val payload = "alpha-broadcast-data".encodeToByteArray()
        val intent = createWriteIntent(handle = senderAlpha, batchId = batchId, payload = payload)

        // When: Intent is committed and broadcast
        actor.writeChannel.send(intent)
        testScheduler.runCurrent()

        val stored = db.getDeltasSince(groupAlpha, excludeNodeId = "none", sinceWatermark = 0L)
        val assignedWatermark = stored.single().watermark

        // Then: Sender gets Ack
        val sFrames = senderAlpha.fakeSession.drainSentFrames()
        sFrames.filterIsInstance<WireFrame.Delta>().shouldBeEmpty()
        val ack = WireFrameFactory.unwrap(sFrames.first().data).shouldBeInstanceOf<WireFrame.Ack>()
        ack.batchId shouldBe batchId
        ack.watermark shouldBe assignedWatermark

        // Then: Peer gets Delta
        val delta = WireFrameFactory.unwrap(peerAlpha.fakeSession.drainSentFrames().first().data)
            .shouldBeInstanceOf<WireFrame.Delta>()
        delta.watermark shouldBe assignedWatermark
        delta.payload shouldBe payload

        // Then: Foreign Peer empty
        foreignPeerBeta.fakeSession.drainSentFrames().shouldBeEmpty()
    }

    // -------------------------------------------------------------------------
    // Fail Fast & Fault Isolation
    // -------------------------------------------------------------------------

    test("should terminate peer with internal error when outbound ack channel is closed") {
        // Given: Registered sender whose outbound channel is closed
        val actor = createDatabaseActor()
        val groupId = "group-${UUID.randomUUID()}"
        val sender = initializePeer("sender-closed", groupId)

        sender.outboundChannel.close()

        val intent = createWriteIntent(handle = sender, batchId = 1L)

        // When: Intent is processed
        actor.writeChannel.send(intent)
        testScheduler.runCurrent()

        // Then: Sender is unregistered from RelayManager
        relayManager.hasRegisteredSession(sender) shouldBe false
        val closeReason = sender.fakeSession.awaitCloseReason()
        closeReason.knownReason shouldBe CloseReason.Codes.INTERNAL_ERROR
        closeReason.message shouldContain "ACK_SEND_FAILURE: Channel closed/cancelled"
    }

    test("should terminate peer with internal error when outbound ack buffer is saturated") {
        // Given: Registered sender whose outbound buffer is filled to capacity
        val actor = createDatabaseActor()
        val groupId = "group-${UUID.randomUUID()}"
        val sender = initializePeer(
            "sender-saturated",
            groupId,
            config = ServerConfig(outboundChannelCapacity = 1)
        )

        sender.outboundChannel.trySend(Frame.Binary(true, byteArrayOf())).isSuccess shouldBe true
        val intent = createWriteIntent(handle = sender, batchId = 2L)

        // When: Intent is processed
        actor.writeChannel.send(intent)
        testScheduler.runCurrent()

        // Then: Sender is unregistered from RelayManager without stalling actor
        relayManager.hasRegisteredSession(sender) shouldBe false
        val closeReason = sender.fakeSession.awaitCloseReason()
        closeReason.knownReason shouldBe CloseReason.Codes.INTERNAL_ERROR
        closeReason.message shouldContain "ACK_SEND_FAILURE: Outbound buffer saturated"
    }

    test("should isolate ack failure so that failing sender does not drop subsequent peer acks or broadcasts") {
        // Given: Sender A (closed channel) and Sender B (healthy) in the same sync group
        val actor = createDatabaseActor()
        val groupId = "group-${UUID.randomUUID()}"
        val senderA = initializePeer("sender-A", groupId)
        val senderB = initializePeer("sender-B", groupId)

        senderA.outboundChannel.close()

        val payloadA = "payload-A".encodeToByteArray()
        val payloadB = "payload-B".encodeToByteArray()
        val intentA = createWriteIntent(handle = senderA, batchId = 10L, payload = payloadA)
        val intentB = createWriteIntent(handle = senderB, batchId = 20L, payload = payloadB)

        // When: Both intents are queued and processed in the same committed client
        actor.writeChannel.send(intentA)
        actor.writeChannel.send(intentB)
        testScheduler.runCurrent()

        // Then: Sender A is terminated due to ACK delivery failure
        relayManager.hasRegisteredSession(senderA) shouldBe false
        val closeReasonA = senderA.fakeSession.awaitCloseReason()
        closeReasonA.knownReason shouldBe CloseReason.Codes.INTERNAL_ERROR
        // And: Sender B remains registered and intact in RelayManager
        relayManager.hasRegisteredSession(senderB) shouldBe true

        // Then: Database committed both writes successfully
        val stored = db.getDeltasSince(groupId, excludeNodeId = "none", sinceWatermark = 0L)
        stored.size shouldBe 2
        val watermarkA = stored[0].watermark
        val watermarkB = stored[1].watermark
        // And: Sender B receives its ACK and the broadcast delta from Sender A
        val senderBFrames = senderB.fakeSession.drainSentFrames()
            .map { WireFrameFactory.unwrap((it as Frame.Binary).data) }
        senderBFrames.size shouldBe 2

        val ackB = senderBFrames.filterIsInstance<WireFrame.Ack>().single()
        ackB.batchId shouldBe 20L
        ackB.watermark shouldBe watermarkB

        val deltaA = senderBFrames.filterIsInstance<WireFrame.Delta>().single()
        deltaA.watermark shouldBe watermarkA
        deltaA.payload shouldBe payloadA
    }

    test("should evict all senders in client with internal error only once when database commit throws") {
        // Given: Multiple senders registered and a database trigger configured to abort client insertion
        val actor = createDatabaseActor()
        val groupId = "group-${UUID.randomUUID()}"
        val senderA = initializePeer("sender-A", groupId)
        val senderB = initializePeer("sender-B", groupId)

        val intents = listOf(
            createWriteIntent(handle = senderA, batchId = 1L),
            createWriteIntent(handle = senderA, batchId = 2L),
            createWriteIntent(handle = senderA, batchId = 3L),
            createWriteIntent(handle = senderB, batchId = 4L)
        )

        val triggerName = "trig_batch_fail_${System.nanoTime()}"
        db.withWriteConnection {  conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    CREATE TRIGGER $triggerName
                    BEFORE INSERT ON sync_change_log
                    BEGIN
                        SELECT RAISE(ABORT, 'Simulated database client write failure');
                    END;
                    """.trimIndent()
                )
            }
        }

        try {

            // When: Intents are submitted and database transaction aborts
            for (intent in intents) {
                actor.writeChannel.send(intent)
            }
            testScheduler.runCurrent()

            // Then: All senders in the failed client are evicted from RelayManager
            relayManager.hasRegisteredSession(senderA) shouldBe false
            relayManager.hasRegisteredSession(senderB) shouldBe false

            val senderAFrames = senderA.fakeSession.drainSentFrames()
            senderAFrames.size shouldBe 1
            senderAFrames.first().shouldBeInstanceOf<Frame.Close>()
            val closeReasonA = senderA.fakeSession.awaitCloseReason()
            closeReasonA.knownReason shouldBe CloseReason.Codes.INTERNAL_ERROR
            closeReasonA.message shouldContain "DATABASE_BATCH_WRITE_ERROR"

            val senderBFrames = senderB.fakeSession.drainSentFrames()
            senderBFrames.size shouldBe 1
            senderBFrames.first().shouldBeInstanceOf<Frame.Close>()
            val closeReasonB = senderB.fakeSession.awaitCloseReason()
            closeReasonB.knownReason shouldBe CloseReason.Codes.INTERNAL_ERROR
            closeReasonB.message shouldContain "DATABASE_BATCH_WRITE_ERROR"
        } finally {
            db.withWriteConnection { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute("DROP TRIGGER IF EXISTS $triggerName;")
                }
            }
        }
    }

    test("should maintain actor loop and process subsequent batches when earlier database commit throws") {
        // Given: A failing sender configured to abort on insert and a separate healthy sender
        val actor = createDatabaseActor()
        val groupId = "group-${UUID.randomUUID()}"
        val senderFailing = initializePeer("sender-failing", groupId)
        val senderHealthy = initializePeer("sender-healthy", groupId)

        val triggerName = "trig_actor_fault_tol_${System.nanoTime()}"
        db.withWriteConnection { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    CREATE TRIGGER $triggerName
                    BEFORE INSERT ON sync_change_log
                    WHEN NEW.origin_node_id = '${senderFailing.nodeId}'
                    BEGIN
                        SELECT RAISE(ABORT, 'Simulated database client failure');
                    END;
                    """.trimIndent()
                )
            }
        }

        try {
            val failingIntent = createWriteIntent(handle = senderFailing, batchId = 1L)
            val healthyPayload = "healthy-payload".encodeToByteArray()
            val healthyIntent = createWriteIntent(handle = senderHealthy, batchId = 2L, payload = healthyPayload)

            // When: Failing intent is processed and aborts database commit
            actor.writeChannel.send(failingIntent)
            testScheduler.runCurrent()

            // Then: Failing sender is terminated with INTERNAL_ERROR
            senderFailing.fakeSession.drainSentFrames().first().shouldBeInstanceOf<Frame.Close>()
            senderFailing.outboundChannel.isClosedForSend shouldBe true
            relayManager.hasRegisteredSession(senderFailing) shouldBe false
            val closeReason = senderFailing.fakeSession.awaitCloseReason()
            closeReason.knownReason shouldBe CloseReason.Codes.INTERNAL_ERROR

            // When: Subsequent valid intent is submitted by a healthy peer
            actor.writeChannel.send(healthyIntent)
            testScheduler.runCurrent()

            // Then: Actor remains alive and successfully commits and acknowledges the subsequent client
            relayManager.hasRegisteredSession(senderHealthy) shouldBe true

            val stored = db.getDeltasSince(groupId, excludeNodeId = "none", sinceWatermark = 0L)
            stored.size shouldBe 1
            stored.first().payload shouldBe healthyPayload

            val frames = senderHealthy.fakeSession.drainSentFrames()
            val ack = WireFrameFactory.unwrap(frames.first().data).shouldBeInstanceOf<WireFrame.Ack>()
            ack.batchId shouldBe 2L
            ack.watermark shouldBe stored.first().watermark
        } finally {
            db.withWriteConnection { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute("DROP TRIGGER IF EXISTS $triggerName;")
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Cancellation Propagation
    // -------------------------------------------------------------------------

    test("should rethrow cancellation without evicting senders when actor coroutine is cancelled during client execution") {
        // Given: Registered sender and actor running inside a cancellable child job
        val actorJob = Job(backgroundScope.coroutineContext[Job])
        val actorScope = CoroutineScope(backgroundScope.coroutineContext + actorJob)
        val actor = DatabaseActor(
            database = db,
            relayManager = relayManager,
            scope = actorScope,
            dispatcher = backgroundScope.coroutineContext[ContinuationInterceptor] as CoroutineDispatcher
        )
        val groupId = "group-${UUID.randomUUID()}"
        val sender = initializePeer("sender-cancelled", groupId)
        val intent = createWriteIntent(handle = sender, batchId = 1L)

        // When: Intent is buffered and actor job is canceled prior to dispatch
        actor.writeChannel.send(intent)
        actorJob.cancel()
        testScheduler.runCurrent()

        // Then: CancellationException bypasses client failure handling, leaving sender registered
        relayManager.hasRegisteredSession(sender) shouldBe true
        sender.outboundChannel.isClosedForSend shouldBe false
        sender.fakeSession.drainSentFrames().shouldBeEmpty()
    }

})

