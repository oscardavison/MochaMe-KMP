package com.mochame.server.relay

import com.mochame.server.database.ServerDatabase
import com.mochame.server.utils.ServerConfig
import com.mochame.server.utils.ServerLogger
import com.mochame.sync.api.network.WireFrame
import com.mochame.sync.api.network.WireFrameFactory
import com.mochame.utils.fixtures.FakeTimeUtils
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.core.test.TestScope
import io.kotest.engine.coroutines.backgroundScope
import io.kotest.engine.coroutines.testScheduler
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.comparables.beGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import java.io.File
import java.sql.Statement
import java.util.UUID
import kotlin.time.Duration.Companion.seconds

class SyncRelayRouteTest : FunSpec({

    coroutineTestScope = true

    val logger = ServerLogger.base.withTag("RouteTst")
    val relayManager = RelayManager()
    val clock = FakeTimeUtils()

    val tempDir = tempdir()
    val dbFile = File(tempDir, "route_test.db")
    val db = ServerDatabase(
        path = dbFile.absolutePath,
        clock = clock,
        dispatcher = Dispatchers.Unconfined
    )

    afterSpec {
        db.close()
    }

    // --- Helpers ---

    fun TestScope.createDatabaseActor(
        config: ServerConfig = ServerConfig.Default,
        dispatcher: CoroutineDispatcher = Dispatchers.IO // I'm not sure how to manipulate Ktor's dispatchers
    ) = DatabaseActor(
        database = db,
        relayManager = relayManager,
        config = config,
        scope = this.backgroundScope,
        dispatcher = dispatcher
    )

    fun TestScope.withRelayClient(
        config: ServerConfig = ServerConfig.Default,
        block: suspend (HttpClient) -> Unit
    ) {
        val databaseActor = createDatabaseActor(config)
        this.testScheduler.runCurrent()

        testApplication {
            application {
                install(WebSockets) { pingPeriod = null }
                routing { syncRelayRoute(db, relayManager, databaseActor, logger, config) }
            }

            val client = createClient {
                install(io.ktor.client.plugins.websocket.WebSockets)
            }

            withTimeout(5.seconds) {
                block(client)
            }
        }
    }

    // -------------------------------------------------------------------------
    // State Guards
    // -------------------------------------------------------------------------

    test("should reject connection when since watermark is older than retention floor without registering session") {
        val groupId = "group-${UUID.randomUUID()}"
        val nodeId = "node-stale"

        // Given: Watermark floor = 5L (simulating 1 - 4 were pruned)
        db.withWriteConnection { conn ->
            conn.prepareStatement(
                """
            INSERT INTO sync_change_log (watermark, group_id, origin_node_id, payload, created_at)
            VALUES (?, ?, ?, ?, ?)
            """.trimIndent()
            ).use { stmt ->
                stmt.setLong(1, 5L)
                stmt.setString(2, groupId)
                stmt.setString(3, "node-seed")
                stmt.setBytes(4, byteArrayOf(0x01))
                stmt.setLong(5, clock.now().toEpochMilliseconds())
                stmt.executeUpdate()
            }
        }

        // When: Client requests since = 2L
        withRelayClient { client ->
            client.webSocket("/sync/$groupId/$nodeId?since=2") {
                // Then: Connection should fail
                val reason = closeReason.await()
                reason?.code shouldBe CloseReason.Codes.VIOLATED_POLICY.code
                reason?.message shouldBe "DELTA_HISTORY_EXPIRED"
            }
            relayManager.getActiveGroup(groupId)?.get(nodeId) shouldBe null
        }
    }

    test("should reject connection with SNAPSHOT_REQUIRED when pending backlog exceeds threshold") {
        val groupId = "group-${UUID.randomUUID()}"
        val connectingNode = "node-entering-busier-street-than-expected"
        val peerNode = "node-writer"
        val threshold = 2
        val config = ServerConfig(maxBackfillThreshold = threshold)

        // Given: (threshold + 1) deltas authored by peerNode (origin_node_id != connectingNode)
        db.withWriteConnection { conn ->
            conn.prepareStatement(
                """
            INSERT INTO sync_change_log (group_id, origin_node_id, payload, created_at)
            VALUES (?, ?, ?, ?);
            """.trimIndent()
            ).use { stmt ->
                repeat(threshold + 1) { index ->
                    stmt.setString(1, groupId)
                    stmt.setString(2, peerNode)
                    stmt.setBytes(3, byteArrayOf(0x01, index.toByte()))
                    stmt.setLong(4, clock.now().toEpochMilliseconds())
                    stmt.addBatch()
                }
                stmt.executeBatch()
            }
        }

        // When: Peer walks into a busier street than expected
        withRelayClient(config) { client ->
            client.webSocket("/sync/$groupId/$connectingNode?since=0") {
                // Then: Peer was rejected prior to session registration
                val reason = closeReason.await()
                reason?.code shouldBe CloseReason.Codes.VIOLATED_POLICY.code
                reason?.message shouldBe "SNAPSHOT_REQUIRED"
            }
            relayManager.getActiveGroup(groupId)?.get(connectingNode) shouldBe null
        }
    }

    // -------------------------------------------------------------------------
    // Backfill Streaming
    // -------------------------------------------------------------------------

    test("should stream historical deltas across chunks ending with backfillComplete") {
        val groupId = "group-${UUID.randomUUID()}"
        val connectingNode = "node-client"
        val peerNode = "node-writer"

        val config = ServerConfig(
            maxBackfillChunkSize = 2,
            maxBackfillThreshold = 10
        )

        val baseline = db.withReaderConnection { conn ->
            conn.createStatement().use { stmt ->
                val rs = stmt.executeQuery("SELECT COALESCE(MAX(watermark), 0) FROM sync_change_log")
                if (rs.next()) rs.getLong(1) else 0L
            }
        }
        val batchSize = 5

        // Given: 5 deltas authored by peerNode
        val payloads = (1..batchSize).map { index ->
            "payload-delta-${baseline + index}".encodeToByteArray()
        }
        val expectedWatermarks = (1..batchSize).map { index -> baseline + index }
        val assignedWatermarks = mutableListOf<Long>()

        db.withWriteConnection { conn ->
            conn.prepareStatement(
                """
                INSERT INTO sync_change_log (group_id, origin_node_id, payload, created_at)
                VALUES (?, ?, ?, ?);
                """.trimIndent(),
                Statement.RETURN_GENERATED_KEYS
            ).use { stmt ->
                for (payload in payloads) {
                    stmt.setString(1, groupId)
                    stmt.setString(2, peerNode)
                    stmt.setBytes(3, payload)
                    stmt.setLong(4, clock.now().toEpochMilliseconds())
                    stmt.executeUpdate()

                    val rs = stmt.generatedKeys
                    if (rs.next()) {
                        assignedWatermarks.add(rs.getLong(1))
                    }
                }
            }
        }
        assignedWatermarks shouldBe expectedWatermarks

        // When: Client connects requesting since=0
        withRelayClient(config) { client ->
            client.webSocket("/sync/$groupId/$connectingNode?since=0") {
                // Then: All 5 deltas arrive in ascending order matching WireFrameFactory framing
                for (i in 0 until 5) {
                    val frame = WireFrameFactory.unwrap(incoming.receive().data)
                        .shouldBeInstanceOf<WireFrame.Delta>()
                    frame.watermark shouldBe assignedWatermarks[i]
                    frame.payload shouldBe payloads[i]
                }

                // And: the backfillComplete frame arrives immediately after and client is registered
                WireFrameFactory.unwrap(incoming.receive().data)
                    .shouldBeInstanceOf<WireFrame.BackfillComplete>()
                relayManager.getActiveGroup(groupId)?.containsKey(connectingNode) shouldBe true

                close(CloseReason(CloseReason.Codes.NORMAL, "Test completed"))
            }
        }
    }


    // -------------------------------------------------------------------------
    // Live Behaviour
    // -------------------------------------------------------------------------

    test("should trigger PROTOCOL_ERROR and disconnect client when inbound binary payload under 9 bytes") {
        val groupId = "group-${UUID.randomUUID()}"
        val nodeId = "node-malformed"

        withRelayClient { client ->
            client.webSocket("/sync/$groupId/$nodeId?since=0") {
                val fenceFrame = incoming.receive().shouldBeInstanceOf<Frame.Binary>()
                fenceFrame.data shouldBe WireFrameFactory.backfillComplete()

                val truncatedPayload = byteArrayOf(0x04, 0x00, 0x00, 0x00, 0x01)
                outgoing.send(Frame.Binary(fin = true, data = truncatedPayload))

                val reason = closeReason.await()
                reason?.code shouldBe CloseReason.Codes.PROTOCOL_ERROR.code
                reason?.message shouldBe "Expected >= 9 Bytes [0x04][batchId: 8B]"
            }

            eventually(5.seconds) {
                relayManager.getActiveGroup(groupId)?.get(nodeId) shouldBe null
            }
        }
    }

    test("valid inbound frame is decoded, enqueued to actor, committed, and acknowledged") {
        val groupId = "group-${UUID.randomUUID()}"
        val nodeId = "node-writer"
        val expectedBatchId = 0x0102030405060708L
        val payloadData = "binary donut".encodeToByteArray()

        // Given
        val clientBytes = WireFrameFactory.client(expectedBatchId, payloadData)

        withRelayClient { client ->
            client.webSocket("/sync/$groupId/$nodeId?since=0") {
                val fenceFrame = incoming.receive().shouldBeInstanceOf<Frame.Binary>()
                fenceFrame.readBytes() shouldBe WireFrameFactory.backfillComplete()

                // When
                outgoing.send(Frame.Binary(fin = true, data = clientBytes))

                // Then: Client receives ACK frame [0x02: Opcode (1B)][batchId: 8B][watermark: 8B] (17 Bytes)
                val ackFrame = WireFrameFactory.unwrap(incoming.receive().data)
                        .shouldBeInstanceOf<WireFrame.Ack>()
                ackFrame.batchId shouldBe expectedBatchId
                ackFrame.watermark shouldBe beGreaterThan(0L)

                // And: Intent successfully traversed writeChannel into SQLite
                val stored = db.getDeltasSince(
                    groupId = groupId,
                    excludeNodeId = "",
                    sinceWatermark = 0L
                )
                stored.size shouldBe 1
                stored.first().watermark shouldBe ackFrame.watermark
                stored.first().payload shouldBe payloadData

                close(CloseReason(CloseReason.Codes.NORMAL, "Test completed"))
            }
        }
    }
})