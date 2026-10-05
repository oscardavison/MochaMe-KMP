package com.mochame.server.database

import com.mochame.server.utils.ServerConfig
import com.mochame.server.utils.createWriteIntent
import com.mochame.utils.fixtures.FakeTimeUtils
import io.kotest.core.spec.style.FunSpec
import io.kotest.engine.spec.tempdir
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import java.io.File
import java.util.UUID
import kotlin.time.Instant


class ServerDatabaseCompactionTest : FunSpec({
    val tempDir = tempdir()
    val clock = FakeTimeUtils()
    val db = ServerDatabase(
        path = File(tempDir, "compaction_test.db").absolutePath,
        clock = clock,
        config = ServerConfig(logPruneChunkSize = 10)
    )

    afterSpec {
        db.close()
    }

    test("should prune only records strictly older than cutoff when evaluating timestamp boundary") {
        // Given: Three sequential records inserted across the cutoff boundary (cutoff - 1, cutoff, cutoff + 1)
        val groupId = "group-${UUID.randomUUID()}"
        val cutoff = 100_000L

        clock.setTime(Instant.fromEpochMilliseconds(cutoff - 1))
        val keysA =
            db.insertBatch(listOf(createWriteIntent(groupId, "node-1", "record-A".encodeToByteArray())))

        clock.setTime(Instant.fromEpochMilliseconds(cutoff))
        val keysB =
            db.insertBatch(listOf(createWriteIntent(groupId, "node-1", "record-B".encodeToByteArray())))

        clock.setTime(Instant.fromEpochMilliseconds(cutoff + 1))
        val keysC =
            db.insertBatch(listOf(createWriteIntent(groupId, "node-1", "record-C".encodeToByteArray())))

        // When: Executing pruning with olderThanEpochMs set exactly to cutoff
        val totalPruned = db.pruneExpiredDeltas(olderThanEpochMs = cutoff)

        // Then: Only the record strictly before the cutoff is pruned, leaving boundary and later records intact
        totalPruned shouldBe 1

        val remaining = db.getDeltasSince(groupId, excludeNodeId = "none", sinceWatermark = 0L)
        remaining.map { it.watermark } shouldBe listOf(keysB.first(), keysC.first())
        remaining.map { String(it.payload) } shouldBe listOf("record-B", "record-C")
    }

    test("should prune expired deltas across multiple chunks when backlog exceeds configured chunk size") {
        // Given: 25 expired records created at T = 1000 and 5 active records created at T = 5000
        val groupId = "group-${UUID.randomUUID()}"
        val cutoff = 3_000L

        clock.setTime(Instant.fromEpochMilliseconds(1_000L))
        val expiredIntents = (1..25).map { index ->
            createWriteIntent(groupId, "node-1", "expired-$index".encodeToByteArray())
        }
        db.insertBatch(expiredIntents)

        clock.setTime(Instant.fromEpochMilliseconds(5_000L))
        val liveIntents = (1..5).map { index ->
            createWriteIntent(groupId, "node-1", "live-$index".encodeToByteArray())
        }
        val liveKeys = db.insertBatch(liveIntents)

        // When: Compacting with chunkSize = 10 across 3 sub-transactions (10, 10, 5)
        val totalPruned = db.pruneExpiredDeltas(olderThanEpochMs = cutoff, chunkSize = 10)

        // Then: Exactly 25 expired records are removed and all 5 active records are preserved
        totalPruned shouldBe 25

        val remaining = db.getDeltasSince(groupId, excludeNodeId = "none", sinceWatermark = 0L)
        remaining.size shouldBe 5
        remaining.map { it.watermark } shouldBe liveKeys
        remaining.map { String(it.payload) } shouldBe (1..5).map { "live-$it" }
    }

    test("should terminate loop cleanly when total expired records is an exact multiple of chunk size") {
        // Given: Exactly 20 expired records created at T = 1000 with chunk size configured to 10
        val groupId = "group-${UUID.randomUUID()}"
        val cutoff = 2_000L

        clock.setTime(Instant.fromEpochMilliseconds(1_000L))
        val expiredIntents = (1..20).map { index ->
            createWriteIntent(groupId, "node-1", "expired-$index".encodeToByteArray())
        }
        db.insertBatch(expiredIntents)

        // When: Running pruning where total matches multiple of chunk size
        val totalPruned = db.pruneExpiredDeltas(olderThanEpochMs = cutoff, chunkSize = 10)

        // Then: Terminate on the zero-row boundary chunk and verify complete deletion
        totalPruned shouldBe 20
        val remaining = db.getDeltasSince(groupId, excludeNodeId = "none", sinceWatermark = 0L)
        remaining.shouldBeEmpty()
    }

    test("should prune expired records globally across all group partitions while preserving live records") {
        // Given: Interleaved expired records (T = 1000) and live records (T = 5000) across two distinct groups
        val groupAlpha = "group-alpha-${UUID.randomUUID()}"
        val groupBeta = "group-beta-${UUID.randomUUID()}"
        val cutoff = 3_000L

        clock.setTime(Instant.fromEpochMilliseconds(1_000L))
        val expiredBatch = listOf(
            createWriteIntent(groupAlpha, "node-1", "alpha-expired-1".encodeToByteArray()),
            createWriteIntent(groupAlpha, "node-2", "alpha-expired-2".encodeToByteArray()),
            createWriteIntent(groupBeta, "node-3", "beta-expired-1".encodeToByteArray()),
            createWriteIntent(groupBeta, "node-4", "beta-expired-2".encodeToByteArray())
        )
        db.insertBatch(expiredBatch)

        clock.setTime(Instant.fromEpochMilliseconds(5_000L))
        val liveBatch = listOf(
            createWriteIntent(groupAlpha, "node-1", "alpha-live-1".encodeToByteArray()),
            createWriteIntent(groupBeta, "node-3", "beta-live-1".encodeToByteArray())
        )
        val liveKeys = db.insertBatch(liveBatch)

        // When: Compacting log globally across all partitions
        val totalPruned = db.pruneExpiredDeltas(olderThanEpochMs = cutoff, chunkSize = 10)

        // Then: Expired records in all groups are removed while live records are preserved in their respective partitions
        totalPruned shouldBe 4

        val remainingAlpha = db.getDeltasSince(groupAlpha, excludeNodeId = "none", sinceWatermark = 0L)
        val remainingBeta = db.getDeltasSince(groupBeta, excludeNodeId = "none", sinceWatermark = 0L)

        remainingAlpha.size shouldBe 1
        remainingAlpha.first().watermark shouldBe liveKeys[0]
        String(remainingAlpha.first().payload) shouldBe "alpha-live-1"

        remainingBeta.size shouldBe 1
        remainingBeta.first().watermark shouldBe liveKeys[1]
        String(remainingBeta.first().payload) shouldBe "beta-live-1"
    }

    test("should interleave live client writes without starvation when pruning large expired backlog") {
        // Given: 200 expired records requiring 20 chunk iterations (chunkSize = 10) and a live client ready to commit
        val groupId = "group-${UUID.randomUUID()}"
        val cutoff = 2_000L

        clock.setTime(Instant.fromEpochMilliseconds(1_000L))
        val expiredIntents = (1..200).map { index ->
            createWriteIntent(groupId, "node-prune", "expired-$index".encodeToByteArray())
        }
        db.insertBatch(expiredIntents)

        clock.setTime(Instant.fromEpochMilliseconds(5_000L))
        val liveIntents = (1..20).map { index ->
            createWriteIntent(groupId, "node-live", "live-$index".encodeToByteArray())
        }

        val readyUps = List(2) { CompletableDeferred<Unit>() }
        val ishtarGate = CompletableDeferred<Unit>()

        // When: Launching background pruning and concurrent live client writes across Dispatchers.IO
        val pruneDeferred = async(Dispatchers.IO) {
            readyUps[0].complete(Unit)
            ishtarGate.await()
            db.pruneExpiredDeltas(olderThanEpochMs = cutoff, chunkSize = 10)
        }
        val writeDeferred = async(Dispatchers.IO) {
            readyUps[1].complete(Unit)
            ishtarGate.await()
            db.insertBatch(liveIntents)
        }

        readyUps.awaitAll()
        ishtarGate.complete(Unit)
        val liveKeys = writeDeferred.await()
        val totalPruned = pruneDeferred.await()

        // Then: Both operations complete successfully without connection timeout or lock exhaustion
        totalPruned shouldBe 200
        liveKeys.size shouldBe 20

        val remaining = db.getDeltasSince(groupId, excludeNodeId = "none", sinceWatermark = 0L)
        remaining.size shouldBe liveKeys.size
        remaining.map { it.watermark } shouldBe liveKeys
    }
})