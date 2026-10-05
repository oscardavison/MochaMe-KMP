package com.mochame.sync.spi

import co.touchlab.kermit.ExperimentalKermitApi
import co.touchlab.kermit.Logger
import co.touchlab.kermit.TestLogWriter
import com.mochame.logger.LogTags
import com.mochame.logger.test.TestLoggerModule
import com.mochame.logger.withTags
import com.mochame.sync.api.models.HLC
import com.mochame.sync.spi.node.NodeId
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Module
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

// -----------------------------------------------------------
// DI
// -----------------------------------------------------------

@Module(includes = [TestLoggerModule::class])
@ComponentScan("com.mochame.sync.spi")
internal class SyncApiTestModule

@OptIn(ExperimentalKermitApi::class)
@Factory
internal class SyncApiTestEnv(
    val writer: TestLogWriter,
    val untaggedLogger: Logger
)

// -----------------------------------------------------------
// UTILS
// -----------------------------------------------------------

internal object TestNodeId {
    val A = NodeId(Uuid.fromLongs(1L, 1L))
    val B = NodeId(Uuid.fromLongs(2L, 2L))
}

internal fun createHlc(
    ts: Long = 1000L,
    count: Int = 1,
    nodeId: NodeId = TestNodeId.A
): HLC = HLC(ts = ts, count = count, nodeId = nodeId)

@OptIn(ExperimentalKermitApi::class)
internal fun TestLogWriter.assertFieldRejectionLogCount(expectedCount: Int) =
    assertEquals(expectedCount, this.logs.count { it.message.contains("Field Rejected") })

internal val SyncApiTestEnv.logger
    get() = untaggedLogger.withTags(LogTags.Layer.SERI, LogTags.Domain.SYNC, "TeCdc1")
