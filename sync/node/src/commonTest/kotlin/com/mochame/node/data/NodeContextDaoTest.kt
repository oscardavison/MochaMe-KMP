package com.mochame.node.data

import com.mochame.node.di.NodeContextIntTestEnv
import com.mochame.node.di.NodeContextIntTestModule
import com.mochame.support.MochaPlatformTest
import com.mochame.utils.fixtures.TestHlcFactory
import com.mochame.support.getPhysicalRowCount
import com.mochame.support.runDatabaseEnvironment
import com.mochame.utils.fixtures.TestNodeId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import org.koin.plugin.module.dsl.modules
import kotlin.test.Test
import kotlin.test.assertEquals

// -----------------------------------------------------------
// SUT ENVIRONMENT
// -----------------------------------------------------------
private inline fun runEnv(crossinline block: suspend NodeContextIntTestEnv.(TestScope) -> Unit) =
    runDatabaseEnvironment<NodeContextMicroSchema, NodeContextIntTestEnv>(
        constructor = NodeContextMicroSchemaConstructor,
        koinSetup = { modules(NodeContextIntTestModule::class) },
        block = block
    )

internal const val nodeTableName = "node_context"

class NodeContextDaoTest : MochaPlatformTest() {

    // -----------------------------------------------------------
    // SINGLETON STATE
    // -----------------------------------------------------------

    @Test
    fun should_maintainSingleRowAndOverwriteFields_when_newContextIsInserted() = runEnv {
        // Given
        val initialEntity = createTestNodeContextEntity(
            nodeId = "node-A",
            appVersion = 1
        )
        val secondaryEntity = createTestNodeContextEntity(
            nodeId = "node-B",
            appVersion = 2
        )

        // When
        dao.insertOrReplaceContext(initialEntity)
        dao.upsert(secondaryEntity)

        // Then
        val finalContext = dao.getContext()
        assertEquals("node-B", finalContext?.nodeId)
        assertEquals(2, finalContext?.appVersion)

        assertEquals(
            1,
            db.getPhysicalRowCount(nodeTableName),
            "The database must physically contain exactly one configuration row."
        )
    }

    // -----------------------------------------------------------
    // STATE INTEGRITY
    // -----------------------------------------------------------

    @Test
    fun should_createAndReturnNewContext_when_databaseIsEmpty() = runEnv {
        // Given
        val fallbackId = "fallback-node"
        val version = 42
        val createdTime = 12345L

        // When
        val result = dao.getOrEstablish(fallbackId, version, createdTime)

        // Then
        assertEquals(1, result.id)
        assertEquals(fallbackId, result.nodeId)
        assertEquals(version, result.appVersion)
        assertEquals(createdTime, result.createdAt)

        // Verify persistence
        val savedContext = dao.getContext()
        assertEquals(
            result,
            savedContext,
            "The returned context must match what was persisted."
        )
        assertEquals(
            1,
            db.getPhysicalRowCount(nodeTableName),
            "The database must physically contain exactly one row."
        )
    }

    @Test
    fun should_returnExistingContextAndIgnoreFallbacks_when_databaseIsAlreadySeeded() =
        runEnv {
            // Given
            val existing =
                createTestNodeContextEntity(nodeId = "existing-node", appVersion = 1)
            dao.insertOrReplaceContext(existing)

            // When
            val result = dao.getOrEstablish(
                fallbackId = "should-be-ignored",
                baseVersion = 99,
                createdAt = 99999L
            )

            // Then
            assertEquals("existing-node", result.nodeId)
            assertEquals(1, result.appVersion)
            assertEquals(1, db.getPhysicalRowCount(nodeTableName))
        }

    // -----------------------------------------------------------
    // HLC CAUSALITY
    // -----------------------------------------------------------

    @Test
    fun should_rejectHlcUpdate_when_incomingHlcIsOlderOrEqual() = runEnv {
        // Given
        val fallbackId = TestNodeId.A
        dao.getOrEstablish(fallbackId = fallbackId.toString(), baseVersion = 1, createdAt = 1000L)

        val baseHlc = TestHlcFactory.create(ts = 1000L, count = 5, nodeId = fallbackId)
        dao.setMaxHlc(baseHlc.toString())

        val olderHlc = TestHlcFactory.create(ts = 1000L, count = 4, nodeId = fallbackId)
        val equalHlc = TestHlcFactory.create(ts = 1000L, count = 5, nodeId = fallbackId)

        // When
        val rowsUpdatedByOlder = dao.setMaxHlc(olderHlc.toString())
        val rowsUpdatedByEqual = dao.setMaxHlc(equalHlc.toString())

        // Then
        assertEquals(
            0, rowsUpdatedByOlder,
            "An older HLC must update exactly 0 rows."
        )
        assertEquals(
            0,
            rowsUpdatedByEqual,
            "An identical HLC must update exactly 0 rows."
        )
        assertEquals(
            baseHlc.toString(),
            dao.getMaxHlc(),
            "The stored maxHlc must remain unchanged."
        )
    }

    @Test
    fun should_initiateHlcFloor_when_transitioningFromNullState() = runEnv {
        // Given
        val fallbackId = TestNodeId.A
        dao.getOrEstablish(fallbackId = fallbackId.toString(), baseVersion = 10, createdAt = 1000L)
        val initialHlc = TestHlcFactory.create(ts = 1000L, count = 0, nodeId = fallbackId)

        // When
        val rowsUpdated = dao.setMaxHlc(initialHlc.toString())

        // Then
        assertEquals(1, rowsUpdated)
        assertEquals(initialHlc.toString(), dao.getMaxHlc())
    }

    @Test
    fun should_overwriteStoredHlc_when_incomingHlcIsLogicallyGreater() = runEnv {
        // Given
        val fallbackId = TestNodeId.A
        dao.getOrEstablish(fallbackId = fallbackId.toString(), baseVersion = 1, createdAt = 1000L)

        val baseHlc = TestHlcFactory.create(ts = 1000L, count = 1, nodeId = fallbackId)
        val greaterHlc = TestHlcFactory.create(ts = 1000L, count = 2, nodeId = fallbackId)
        dao.setMaxHlc(baseHlc.toString())

        // When
        val rowsUpdated = dao.setMaxHlc(greaterHlc.toString())

        // Then
        assertEquals(1, rowsUpdated)
        assertEquals(greaterHlc.toString(), dao.getMaxHlc())
    }

    // -----------------------------------------------------------
    // CONCURRENCY
    // -----------------------------------------------------------

    @Test
    fun should_maintainIntegrityWithoutRowDuplication_when_slammedByConcurrentWrites() =
        runEnv { scope ->
            // Given
            val baseEntity =
                createTestNodeContextEntity(nodeId = "node-concurrent", appVersion = 1)
            dao.insertOrReplaceContext(baseEntity)

            val threadCount = 8
            val iterationsPerThread = 15
            val gate = CompletableDeferred<Unit>()
            // Generate distinct HLC pools per thread to ensure unique values are written
            val totalOperations = threadCount * iterationsPerThread
            val hlcSequence = TestHlcFactory.chronologicalSequence(size = totalOperations)

            // When - Multi-threaded HLC writes
            val workerJobs = List(threadCount) { threadId ->
                scope.launch(Dispatchers.Default) {
                    gate.await()

                    val startIndex = threadId * iterationsPerThread
                    repeat(iterationsPerThread) { iteration ->
                        val currentHlc = hlcSequence[startIndex + iteration].toString()
                        dao.setMaxHlc(currentHlc)
                    }
                }
            }
            gate.complete(Unit)
            workerJobs.joinAll()

            // Then
            val finalContext = dao.getContext()
            assertEquals(
                "node-concurrent",
                finalContext?.nodeId,
                "The static identity must not be corrupted."
            )
            assertEquals(
                1,
                db.getPhysicalRowCount(nodeTableName),
                "Db must physically contain exactly one row."
            )
            // The final stored maxHlc value is not corrupted, belonging to the written set. Not possible to enforce monotonicity here.
            val isAValidHlc = hlcSequence.any { it.toString() == finalContext?.maxHlc }
            assertEquals(
                true,
                isAValidHlc,
                "The final persisted maxHlc must match a valid value from the write pool."
            )
        }

}