@file:OptIn(ExperimentalKermitApi::class)

package com.mochame.sync.infrastructure.serialization

import co.touchlab.kermit.ExperimentalKermitApi
import com.mochame.support.MochaPlatformTest
import com.mochame.support.runUnitEnvironment
import com.mochame.sync.api.exceptions.MochaException
import com.mochame.sync.di.codec.CodecRouterTestModule
import com.mochame.sync.di.codec.CodecFixtureTestEnv
import com.mochame.sync.internal.fixtures.assertDecodedIntentParity
import com.mochame.sync.internal.fixtures.createTestSyncIntent
import com.mochame.sync.internal.fixtures.serialization.FakeBatchCodec
import com.mochame.sync.internal.fixtures.serialization.toRouterWithVersion
import kotlinx.coroutines.test.TestScope
import org.koin.plugin.module.dsl.modules
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private inline fun runEnv(crossinline block: CodecFixtureTestEnv.(TestScope) -> Unit) =
    runUnitEnvironment<CodecFixtureTestEnv>(
        koinSetup = { modules(CodecRouterTestModule::class) },
        block = block
    )


class BatchCodecRouterTest : MochaPlatformTest() {

    @Test
    fun should_roundTripPayload_when_usingDefaultVersionRouter() = runEnv {
        // Arrange
        val intents = listOf(createTestSyncIntent())

        // Act
        val bytes = batchRouter.versionEncode(intents)
        val decoded = batchRouter.versionDecode(bytes, version = batchRouter.latestVersion)

        // Assert
        assertEquals(1, decoded.size)
        assertDecodedIntentParity(intents[0], decoded[0])
    }

    @Test
    fun should_delegateRoutedEncodeToLatestVersion_when_multiVersionRouterConfigured() = runEnv {
        // Arrange: Router registry [null, realV1, fakeV2], latestVersion = 2
        val multiVersionRouter = realBatchCodec.toRouterWithVersion(v2 = fakeBatchCodec, logger)

        // Act: versionEncode must select latestVersion (V2)
        val bytes = multiVersionRouter.versionEncode(emptyList())

        // Assert
        assertContentEquals(FakeBatchCodec.BYTES_PRESET, bytes)
    }

    @Test
    fun should_dispatchRoutedDecodeToCorrectVersionCodec_when_validVersionProvided() = runEnv {
        // Arrange
        val multiVersionRouter = realBatchCodec.toRouterWithVersion(v2 = fakeBatchCodec, logger)
        val v1Intent = createTestSyncIntent()
        val v1Bytes = realBatchCodec.encode(listOf(v1Intent))

        // Act
        val decodedV1 = multiVersionRouter.versionDecode(v1Bytes, version = 1)
        val decodedV2 = multiVersionRouter.versionDecode(FakeBatchCodec.BYTES_PRESET, version = 2)

        // Assert
        assertEquals(1, decodedV1.size)
        assertDecodedIntentParity(v1Intent, decodedV1[0])

        assertEquals(2, decodedV2.size)
        assertEquals(0L, decodedV2[0].candidateKey)
        assertEquals(1L, decodedV2[1].candidateKey)
    }

    @Test
    fun should_throwUnknownProtocolVersion_when_versionIsUnregisteredOrOutOfBounds() = runEnv {
        // Arrange
        val multiVersionRouter = realBatchCodec.toRouterWithVersion(v2 = fakeBatchCodec, logger)
        val sampleBytes = FakeBatchCodec.BYTES_PRESET

        // Assert: Verify all out-of-bounds index lookups throw domain versioning exception
        listOf(-1, 0, 99).forEach { invalidVersion ->
            assertFailsWith<MochaException.Persistent.UnknownProtocolVersion> {
                multiVersionRouter.versionDecode(sampleBytes, version = invalidVersion)
            }
        }
    }
}