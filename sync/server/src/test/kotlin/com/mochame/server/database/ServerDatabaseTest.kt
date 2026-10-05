package com.mochame.server.database

import com.mochame.server.utils.createWriteIntent
import com.mochame.utils.fixtures.FakeTimeUtils
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.sql.SQLException
import java.util.UUID


class ServerDatabaseTest : FunSpec({

    val tempDir = tempdir()
    val dbFile = File(tempDir, "test.db")
    val db = ServerDatabase(
        dbFile.absolutePath,
        clock = FakeTimeUtils(),
        dispatcher = Dispatchers.IO
    )

    afterSpec {
        db.close()
    }

    coroutineTestScope = true

    test("should boot with schema in WAL mode with composite index registered") {
        println("DB Location: ${tempDir.absolutePath}")

        db.withWriteConnection { conn ->
            conn.createStatement().use { stmt ->
                val journalModeRs = stmt.executeQuery("PRAGMA journal_mode;")
                journalModeRs.next()
                journalModeRs.getString(1) shouldBe "wal"

                val indexRs = stmt.executeQuery(
                    "SELECT name FROM sqlite_schema WHERE type = 'index' AND tbl_name = 'sync_change_log';"
                )
                val indexes = mutableListOf<String>()
                while (indexRs.next()) {
                    indexes.add(indexRs.getString("name"))
                }
                indexes shouldContain "idx_group_watermark"
            }
        }
    }

    // -------------------------------------------------------------------------
    // Batch / Watermark Monotonicity
    // -------------------------------------------------------------------------

    test("should maintain 1:1 positional return and client-over-client monotonicity") {
        // Given: Multiple batches of deltas across nodes
        val groupId = "group-${UUID.randomUUID()}"
        val batch1 = listOf(
            createWriteIntent(groupId, "node-1", "delta-1".encodeToByteArray()),
            createWriteIntent(groupId, "node-1", "delta-2".encodeToByteArray(), batchId = 2L),
            createWriteIntent(groupId, "node-2", "delta-3".encodeToByteArray())
        )
        val batch2 = listOf(
            createWriteIntent(groupId, "node-2", "delta-4".encodeToByteArray(), batchId = 2L),
            createWriteIntent(groupId, "node-1", "delta-5".encodeToByteArray(), batchId = 3L)
        )

        // When: Batches are inserted sequentially
        val watermarks1 = db.insertBatch(batch1)
        val watermarks2 = db.insertBatch(batch2)

        // Then: 1:1 & Monotonicity is preserved
        watermarks1.size shouldBe batch1.size
        watermarks2.size shouldBe batch2.size

        watermarks1[0] shouldBeLessThan watermarks1[1]
        watermarks1[1] shouldBeLessThan watermarks1[2]

        watermarks2[0] shouldBeGreaterThan watermarks1.last()
        watermarks2[0] shouldBeLessThan watermarks2[1]
    }

    test("should return empty list immediately on insertBatch when empty input") {
        val result = db.insertBatch(emptyList())
        result.shouldBeEmpty()
    }

    // -------------------------------------------------------------------------
    // Rollback
    // -------------------------------------------------------------------------

    test("should roll back mid-insertBatch failure atomically and preserve sequence progression") {
        val groupId = "group-${UUID.randomUUID()}"
        val triggerName = "trig_force_fail_${System.nanoTime()}"

        // Install a transient SQLite trigger to force mid-client failure
        db.withWriteConnection { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    CREATE TRIGGER $triggerName
                    BEFORE INSERT ON sync_change_log
                    WHEN NEW.origin_node_id = 'TRIGGER_FAILURE'
                    BEGIN
                        SELECT RAISE(ABORT, 'Simulated mid-client write failure');
                    END;
                    """.trimIndent()
                )
            }
        }

        try {
            // Given: Baseline record to anchor the sequence and a failing client
            val initialKeys = db.insertBatch(
                listOf(
                    createWriteIntent(groupId, "node-init")
                )
            )
            val baselineWatermark = initialKeys.first()

            val failingBatch = listOf(
                createWriteIntent(groupId, "node-1"),
                createWriteIntent(groupId, "TRIGGER_FAILURE"),
                createWriteIntent(groupId, "node-1", batchId = 3L)
            )

            // When:
            shouldThrow<SQLException> {
                db.insertBatch(failingBatch)
            }

            // Then: Atomic Rollback
            val deltasAfterFailure = db.getDeltasSince(
                groupId = groupId,
                excludeNodeId = "none",
                sinceWatermark = baselineWatermark
            )
            deltasAfterFailure.shouldBeEmpty()

            // And: Recovery
            val recoveryKeys = db.insertBatch(
                listOf(
                    createWriteIntent(groupId, "node-1")
                )
            )
            recoveryKeys.size shouldBe 1
            recoveryKeys.first() shouldBeGreaterThan baselineWatermark
        } finally {
            db.withWriteConnection { conn ->
                conn.createStatement().use { stmt ->
                    stmt.execute("DROP TRIGGER IF EXISTS $triggerName;")
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Watermark & Buffer Retrieval/Integrity
    // -------------------------------------------------------------------------

    test("should preserve binary payload byte-for-byte when storing and retrieving arbitrary bytes and null terminators") {
        // Given: Payloads containing null terminators (0x00), signed boundaries, and Protobuf wire bytes
        val groupId = "group-${UUID.randomUUID()}"
        val payloadWithNulls =
            byteArrayOf(0x00, 0x01, 0x00, 0x7F, (-128).toByte(), (-1).toByte(), 0x00)
        val protobufWirePayload =
            byteArrayOf(0x08, 0x96.toByte(), 0x01, 0x12, 0x07, 0x74, 0x65, 0x73, 0x74, 0x00)

        val intents = listOf(
            createWriteIntent(groupId, "node-1", payloadWithNulls),
            createWriteIntent(groupId, "node-2", protobufWirePayload)
        )

        // When: Batch is inserted and retrieved from the change log
        db.insertBatch(intents)
        val retrieved = db.getDeltasSince(groupId, excludeNodeId = "none", sinceWatermark = 0L)

        // Then: Binary payload roundtrips with byte-for-byte parity
        retrieved.size shouldBe 2
        retrieved[0].payload contentEquals payloadWithNulls
        retrieved[1].payload contentEquals protobufWirePayload
    }

    test("should enforce strict exclusive lower bound and return empty when sinceWatermark is at or above max") {
        // Given: Sequential records committed for a group with captured dynamic watermarks
        val groupId = "group-${UUID.randomUUID()}"

        val intents = listOf(
            createWriteIntent(groupId, "node-1"),
            createWriteIntent(groupId, "node-1", batchId = 2L),
            createWriteIntent(groupId, "node-1", batchId = 3L)
        )
        val keys = db.insertBatch(intents)
        val w1 = keys[0]
        val w2 = keys[1]
        val w3 = keys[2]

        // When: Slicing across boundaries (w1, w2, max w3, and beyond max)
        val sliceFromW1 = db.getDeltasSince(groupId, excludeNodeId = "none", sinceWatermark = w1)
        val sliceFromW2 = db.getDeltasSince(groupId, excludeNodeId = "none", sinceWatermark = w2)
        val sliceFromMax = db.getDeltasSince(groupId, excludeNodeId = "none", sinceWatermark = w3)
        val sliceBeyondMax =
            db.getDeltasSince(groupId, excludeNodeId = "none", sinceWatermark = w3 + 100L)

        // Then: Strictly exclusive lower bound is respected and ceiling queries return empty
        sliceFromW1.map { it.watermark } shouldBe listOf(w2, w3)
        sliceFromW2.map { it.watermark } shouldBe listOf(w3)
        sliceFromMax.shouldBeEmpty()
        sliceBeyondMax.shouldBeEmpty()
    }

    test("should omit caller-authored writes when excludeNodeId matches and return all when unknown") {
        // Given: Interleaved writes authored by Node-A and Node-B
        val groupId = "group-${UUID.randomUUID()}"
        val node1 = "node-1"
        val node2 = "node-2"

        val intents = listOf(
            createWriteIntent(groupId, node1, "delta-A1".encodeToByteArray()),
            createWriteIntent(groupId, node2, "delta-B1".encodeToByteArray()),
            createWriteIntent(groupId, node1, "delta-A2".encodeToByteArray(), batchId = 2L),
            createWriteIntent(groupId, node2, "delta-B2".encodeToByteArray(), batchId = 2L)
        )
        val keys = db.insertBatch(intents)
        val node1Keys = listOf(keys[0], keys[2])
        val node2Keys = listOf(keys[1], keys[3])

        // When: Querying with Node-A excluded, Node-B excluded, and an unobserved origin node
        val excludeNode1 = db.getDeltasSince(groupId, excludeNodeId = node1, sinceWatermark = 0L)
        val excludeNode2 = db.getDeltasSince(groupId, excludeNodeId = node2, sinceWatermark = 0L)
        val excludeUnknown =
            db.getDeltasSince(groupId, excludeNodeId = "Node-Unknown", sinceWatermark = 0L)

        // Then: Filter out only writes originating from the excluded node ID
        excludeNode1.map { it.watermark } shouldBe node2Keys
        excludeNode1.map { String(it.payload) } shouldBe listOf("delta-B1", "delta-B2")

        excludeNode2.map { it.watermark } shouldBe node1Keys
        excludeNode2.map { String(it.payload) } shouldBe listOf("delta-A1", "delta-A2")

        excludeUnknown.map { it.watermark } shouldBe keys
    }

    test("should isolate deltas strictly by groupId when multiple groups are interleaved in storage") {
        // Given: Interleaved writes committed across Group-Alpha and Group-Beta
        val groupAlpha = "group-${UUID.randomUUID()}"
        val groupBeta = "group-${UUID.randomUUID()}"

        val alphaIntents = listOf(
            createWriteIntent(groupAlpha, "node-1", "alpha-1".encodeToByteArray()),
            createWriteIntent(groupAlpha, "node-2", "alpha-2".encodeToByteArray())
        )
        val betaIntents = listOf(
            createWriteIntent(groupBeta, "node-3", "beta-1".encodeToByteArray()),
            createWriteIntent(groupBeta, "node-4", "beta-2".encodeToByteArray())
        )

        db.insertBatch(alphaIntents)
        db.insertBatch(betaIntents)

        // When: Querying deltas specifically for Group-Alpha
        val alphaDeltas = db.getDeltasSince(groupAlpha, excludeNodeId = "none", sinceWatermark = 0L)

        // Then: Only Group-Alpha deltas are returned with zero cross-tenant contamination
        alphaDeltas.size shouldBe 2
        alphaDeltas.map { String(it.payload) } shouldBe listOf("alpha-1", "alpha-2")
    }

    test("should clamp limit boundaries and paginate deltas in ascending order when requesting slices") {
        // Given: 10 committed deltas for a single group
        val groupId = "group-${UUID.randomUUID()}"
        val intents = (1..10).map { i ->
            createWriteIntent(groupId, "node-1", "delta-$i".encodeToByteArray())
        }
        val assignedKeys = db.insertBatch(intents)

        // When: Requesting a page with limit = 3, clamped lower bound (limit <= 0), and limit exceeding maximum
        val pageOfThree =
            db.getDeltasSince(groupId, excludeNodeId = "none", sinceWatermark = 0L, limit = 3)
        val clampedLower =
            db.getDeltasSince(groupId, excludeNodeId = "none", sinceWatermark = 0L, limit = -5)
        val clampedUpper =
            db.getDeltasSince(groupId, excludeNodeId = "none", sinceWatermark = 0L, limit = 1000)

        // Then: Slices return the lowest watermarks and values outside boundaries clamp cleanly
        pageOfThree.map { it.watermark } shouldBe assignedKeys.take(3)
        clampedLower.size shouldBe 1
        clampedLower.first().watermark shouldBe assignedKeys.first()
        clampedUpper.size shouldBe 10
    }

    test("should match getDeltasSince result set size exactly when counting backlog deltas across filter permutations") {
        // Given: Committed deltas authored by multiple nodes across a single group
        val groupId = "group-${UUID.randomUUID()}"
        val intents = listOf(
            createWriteIntent(groupId, "node-A", "delta-1".encodeToByteArray()),
            createWriteIntent(groupId, "node-B", "delta-2".encodeToByteArray()),
            createWriteIntent(groupId, "node-A", "delta-3".encodeToByteArray()),
            createWriteIntent(groupId, "node-B", "delta-4".encodeToByteArray())
        )
        val assignedKeys = db.insertBatch(intents)
        val splitWatermark = assignedKeys[1]

        // When: Evaluating backlog counts and slice sizes across varying bounds and exclusions
        val countAll = db.countDeltasSince(groupId, excludeNodeId = "none", sinceWatermark = 0L)
        val sliceAll = db.getDeltasSince(groupId, excludeNodeId = "none", sinceWatermark = 0L)

        val countExcluded =
            db.countDeltasSince(groupId, excludeNodeId = "node-A", sinceWatermark = 0L)
        val sliceExcluded =
            db.getDeltasSince(groupId, excludeNodeId = "node-A", sinceWatermark = 0L)

        val countBounded =
            db.countDeltasSince(groupId, excludeNodeId = "node-A", sinceWatermark = splitWatermark)
        val sliceBounded =
            db.getDeltasSince(groupId, excludeNodeId = "node-A", sinceWatermark = splitWatermark)

        // Then: Backlog count strictly matches slice size in all filter permutations
        countAll shouldBe sliceAll.size.toLong()
        countExcluded shouldBe sliceExcluded.size.toLong()
        countBounded shouldBe sliceBounded.size.toLong()
    }

    test("should return null for empty group and lowest active watermark when group is populated") {
        // Given: An unpopulated group and a populated group with sequential commits
        val emptyGroupId = "group-empty-${UUID.randomUUID()}"
        val populatedGroupId = "group-pop-${UUID.randomUUID()}"

        val intents = listOf(
            createWriteIntent(populatedGroupId, "node-1", "delta-1".encodeToByteArray()),
            createWriteIntent(populatedGroupId, "node-2", "delta-2".encodeToByteArray())
        )
        val assignedKeys = db.insertBatch(intents)
        val expectedMin = assignedKeys.first()

        // When: Fetching min watermarks for both groups
        val emptyMin = db.getMinWatermark(emptyGroupId)
        val populatedMin = db.getMinWatermark(populatedGroupId)

        // Then: Empty group evaluates to null and populated group resolves to the lowest retained watermark
        emptyMin.shouldBeNull()
        populatedMin shouldBe expectedMin
    }
})
