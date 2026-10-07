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
import com.mochame.sync.internal.fixtures.serialization.FakeIntentCodec
import com.mochame.sync.internal.fixtures.serialization.toRouterWithVersion
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.ExperimentalSerializationApi
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


@ExperimentalSerializationApi
internal class IntentCodecRouterTest : MochaPlatformTest() {
    @Test
    fun should_roundTripPayload_when_usingDefaultVersionRouter() = runEnv {
        // Arrange
        val intent = createTestSyncIntent()

        // Act
        val bytes = intentCodecResolver.versionEncode(intent)
        val decoded = intentCodecResolver.versionDecode(bytes, version = intentCodecResolver.latestVersion)

        // Assert
        assertDecodedIntentParity(intent, decoded)
    }

    @Test
    fun should_delegateRoutedEncodeToLatestVersion_when_multiVersionRouterConfigured() = runEnv {
        // Arrange: Router registry [null, realV1, fakeV2], latestVersion = 2
        val multiVersionRouter = realIntentCodec.toRouterWithVersion(v2 = fakeIntentCodec, logger)

        // Act: versionEncode must select latestVersion (V2)
        val bytes = multiVersionRouter.versionEncode(createTestSyncIntent())

        // Assert
        assertContentEquals(FakeIntentCodec.BYTES_PRESET, bytes)
    }

    @Test
    fun should_dispatchRoutedDecodeToCorrectVersionCodec_when_validVersionProvided() = runEnv {
        // Arrange
        val multiVersionRouter = realIntentCodec.toRouterWithVersion(v2 = fakeIntentCodec, logger)
        val v1Intent = createTestSyncIntent()
        val v1Bytes = realIntentCodec.encode(v1Intent)

        // Act
        val decodedV1 = multiVersionRouter.versionDecode(v1Bytes, version = 1)
        val decodedV2 = multiVersionRouter.versionDecode(FakeIntentCodec.BYTES_PRESET, version = 2)

        // Assert
        assertDecodedIntentParity(v1Intent, decodedV1)
        assertEquals(FakeIntentCodec.MODEL_PRESET, decodedV2)
    }

    @Test
    fun should_throwUnknownProtocolVersion_when_versionIsUnregisteredOrOutOfBounds() = runEnv {
        // Arrange
        val multiVersionRouter = realIntentCodec.toRouterWithVersion(v2 = fakeIntentCodec, logger)
        val sampleBytes = FakeIntentCodec.BYTES_PRESET

        // Assert: Verify all out-of-bounds index lookups throw domain versioning exception
        listOf(-1, 0, 99).forEach { invalidVersion ->
            assertFailsWith<MochaException.Persistent.UnknownProtocolVersion> {
                multiVersionRouter.versionDecode(sampleBytes, version = invalidVersion)
            }
        }
    }
}