@file:OptIn(ExperimentalKermitApi::class)

package com.mochame.sync.infrastructure.serialization

import co.touchlab.kermit.ExperimentalKermitApi
import com.mochame.support.MochaPlatformTest
import com.mochame.support.runUnitEnvironment
import com.mochame.sync.api.exceptions.MochaException
import com.mochame.sync.di.codec.CodecFixtureTestEnv
import com.mochame.sync.di.codec.CodecTestModule
import com.mochame.sync.internal.fixtures.assertDecodedIntentParity
import com.mochame.sync.internal.fixtures.createTestSyncIntent
import com.mochame.sync.internal.fixtures.serialization.FakeBatchCodec
import com.mochame.sync.internal.fixtures.testBatch
import com.mochame.sync.internal.fixtures.serialization.toRouterWithVersion
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.protobuf.ProtoBuf
import org.koin.plugin.module.dsl.modules
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

private inline fun runEnv(crossinline block: CodecFixtureTestEnv.(TestScope) -> Unit) =
    runUnitEnvironment<CodecFixtureTestEnv>(
        koinSetup = { modules(CodecTestModule::class) },
        block = block
    )


@ExperimentalSerializationApi
class PayloadCodecTest : MochaPlatformTest() {

    @Test
    fun should_roundTripPayload_when_usingDefaultSingleVersionCodec() = runEnv {
        // Arrange
        val originalIntents = testBatch(2)

        // Act
        val wireBytes = realPayloadCodec.encode(originalIntents)
        val decodedIntents = realPayloadCodec.decode(wireBytes)

        // Assert
        assertEquals(2, decodedIntents.size)

        assertDecodedIntentParity(originalIntents[0], decodedIntents[0])
        assertDecodedIntentParity(originalIntents[1], decodedIntents[1])
    }

    @Test
    fun should_stampLatestVersionAndDelegatedPayload_on_encode_when_multiVersionRouterConfigured() =
        runEnv {
            // Arrange: Build multi-version router (V1 = Real, V2 = Fake) -> latestVersion = 2
            val multiVersionRouter = realBatchCodec.toRouterWithVersion(v2 = fakeBatchCodec, logger)
            val payloadCodecFixture = DefaultPayloadCodec(multiVersionRouter, logger)

            // Act
            val wireBytes = payloadCodecFixture.encode(testBatch())
            val rawVersionedPayload =
                ProtoBuf.decodeFromByteArray(VersionedPayload.serializer(), wireBytes)

            // Assert: Outer frame hoists latestVersion (2) and wraps FakeBatchCodec encode result
            assertEquals(
                multiVersionRouter.latestVersion,
                rawVersionedPayload.batchVersion,
                "VersionedPayload must stamp batchCodecRouter.latestVersion in Tag 1"
            )
            assertContentEquals(
                FakeBatchCodec.BYTES_PRESET,
                rawVersionedPayload.payload,
                "VersionedPayload payload bytes must match versionEncode output"
            )
        }

    @Test
    fun should_routeToCorrectBatchCodecVersion_on_decode_when_differentVersionsInjected() = runEnv {
        // Arrange
        val batchRouterFixture = realBatchCodec.toRouterWithVersion(v2 = fakeBatchCodec, logger)
        val payloadCodecFixture = DefaultPayloadCodec(batchRouterFixture, logger)
        val v1Intent = createTestSyncIntent()

        val v1BatchBytes = realBatchCodec.encode(listOf(v1Intent))
        val v1WireFrame = ProtoBuf.encodeToByteArray(
            VersionedPayload.serializer(),
            VersionedPayload(1, v1BatchBytes)
        )
        val v2WireFrame = ProtoBuf.encodeToByteArray(
            VersionedPayload.serializer(),
            VersionedPayload(batchRouterFixture.latestVersion, FakeBatchCodec.BYTES_PRESET)
        )

        // Act
        val decodedV1 = payloadCodecFixture.decode(v1WireFrame)
        val decodedV2 = payloadCodecFixture.decode(v2WireFrame)

        // Assert
        assertEquals(1, decodedV1.size)
        assertDecodedIntentParity(v1Intent, decodedV1[0])

        assertEquals(2, decodedV2.size)
        assertEquals(0L, decodedV2[0].candidateKey)
        assertEquals(1L, decodedV2[1].candidateKey)

        assertEquals(1, writer.logs.count { it.message.contains("decoding legacy") })
    }

    @Test
    fun should_throwSerializationException_when_outerBytesAreCorrupt() = runEnv {
        // Arrange
        val corruptedWireBytes = byteArrayOf(0xFF.toByte(), 0x00, 0xFE.toByte())

        // Act & Assert: Outer VersionedPayload parsing fails prior to client processing
        assertFailsWith<SerializationException> {
            realPayloadCodec.decode(corruptedWireBytes)
        }
    }

    @Test
    fun should_propagateUnknownProtocolVersion_when_batchVersionIsUnmapped() = runEnv {
        // Arrange: Encode an outer payload frame claiming version 99
        val unmappedVersionFrame = ProtoBuf.encodeToByteArray(
            VersionedPayload.serializer(),
            VersionedPayload(batchVersion = 99, payload = byteArrayOf(0x01, 0x02))
        )

        // Act & Assert: BatchCodecRouter rejects version 99 and throws UnknownProtocolVersion
        assertFailsWith<MochaException.Persistent.UnknownProtocolVersion> {
            realPayloadCodec.decode(unmappedVersionFrame)
        }
    }

}