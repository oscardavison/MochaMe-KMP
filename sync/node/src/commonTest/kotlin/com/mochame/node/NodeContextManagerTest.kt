package com.mochame.node

import co.touchlab.kermit.ExperimentalKermitApi
import com.mochame.node.data.NodeContextMicroSchema
import com.mochame.node.data.NodeContextMicroSchemaConstructor
import com.mochame.node.data.nodeTableName
import com.mochame.node.di.NodeContextIntTestEnv
import com.mochame.node.di.NodeContextIntTestModule
import com.mochame.node.managers.DefaultNodeContextManager
import com.mochame.support.MochaPlatformTest
import com.mochame.support.getPhysicalRowCount
import com.mochame.support.runDatabaseEnvironment
import com.mochame.sync.spi.node.NodeContext
import com.mochame.utils.fixtures.TestHlcFactory
import com.mochame.utils.fixtures.TestNodeId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.TestScope
import org.koin.plugin.module.dsl.modules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

// -----------------------------------------------------------
// SUT ENVIRONMENT
// -----------------------------------------------------------

private inline fun runEnv(crossinline block: suspend NodeContextIntTestEnv.(TestScope) -> Unit) =
    runDatabaseEnvironment<NodeContextMicroSchema, NodeContextIntTestEnv>(
        constructor = NodeContextMicroSchemaConstructor,
        koinSetup = { modules(NodeContextIntTestModule::class) },
        block = block
    )


class NodeContextManagerTest : MochaPlatformTest() {

    // -----------------------------------------------------------
    // CONTEXT INTEGRITY / MAPPING
    // -----------------------------------------------------------
    @Test
    fun should_establishCleanDefaultContext_when_databaseIsEmpty() = runEnv {
        // Given
        val targetBaseVersion = 3
        // When
        val establishedContext = manager.getOrEstablishContext(targetBaseVersion)
        // Then
        assertNotNull(establishedContext.nodeId)
        assertNotNull(establishedContext.createdAt)
        assertEquals(targetBaseVersion, establishedContext.appVersion)
        assertNull(establishedContext.maxHlc)
        assertNull(establishedContext.lastInboundWatermark)
        assertNull(establishedContext.lastServerResponseTime)

        // Safety check at database boundary
        assertEquals(1, db.getPhysicalRowCount(nodeTableName))
    }

    @Test
    fun should_preserveAllPopulatedFields_when_mappingRoundTripExecutes() = runEnv {
        // Given
        val expectedHlc =
            TestHlcFactory.create(ts = 15000L, count = 4)
        val populatedDomainContext = NodeContext(
            nodeId = TestNodeId.A,
            appVersion = 12,
            createdAt = 1000L,
            maxHlc = expectedHlc,
            lastServerResponseTime = Instant.fromEpochMilliseconds(8888L),
        )

        // When
        manager.overwriteNodeContext(populatedDomainContext)
        val fetchedContext = manager.getOrEstablishContext()

        // Then
        assertEquals(populatedDomainContext.nodeId, fetchedContext.nodeId)
        assertEquals(populatedDomainContext.appVersion, fetchedContext.appVersion)
        assertEquals(populatedDomainContext.createdAt, fetchedContext.createdAt)
        assertEquals(
            populatedDomainContext.lastInboundWatermark,
            fetchedContext.lastInboundWatermark
        )
        assertEquals(populatedDomainContext.maxHlc, fetchedContext.maxHlc)
        assertEquals(
            populatedDomainContext.lastServerResponseTime,
            fetchedContext.lastServerResponseTime
        )
    }

    // -----------------------------------------------------------
    // CONCURRENCY
    // -----------------------------------------------------------

    @Test
    fun should_initialize_single_id_when_async_polls_to_manager() =
        runEnv { scope ->
            val defaultManager =
                DefaultNodeContextManager(dao, idGen, Dispatchers.IO, Mutex(), logger)
            val threads = 8
            val readySignals = List(threads) { CompletableDeferred<Unit>() }
            val gate = CompletableDeferred<Unit>()

            val workerDeferreds = List(threads) { index ->
                scope.async(Dispatchers.Default) {
                    readySignals[index].complete(Unit)
                    gate.await()
                    defaultManager.getOrEstablishContext()
                }
            }
            readySignals.awaitAll()

            // When
            gate.complete(Unit)
            val completedResult = workerDeferreds.awaitAll()
            val expectedNodeId = completedResult.first().nodeId

            // Then
            // Assert every single concurrent caller received the exact same identity instance
            completedResult.forEach { context ->
                assertEquals(
                    expectedNodeId,
                    context.nodeId,
                    "Multi-threaded initialization returned mismatched IDs."
                )
            }

            // Verify physical storage invariance directly
            assertEquals(
                1,
                db.getPhysicalRowCount(nodeTableName),
                "Database must physically contain exactly one configuration row."
            )
        }

    // -----------------------------------------------------------
    // LOGGING
    // -----------------------------------------------------------

    @OptIn(ExperimentalKermitApi::class)
    @Test
    fun should_logDebugMessage_when_daoReturnsZeroRowsUpdated() = runEnv {
        // Given
        val hlc = TestHlcFactory.create(ts = 1000L, count = 1)
        manager.updateHlcFloor(hlc)

        // When
        manager.updateHlcFloor(hlc)

        // Then
        val updateIgnoredLog = writer.logs.any { it.message.contains("ignored") }
        assertTrue(updateIgnoredLog)
    }

}