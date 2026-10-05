@file:OptIn(ExperimentalKermitApi::class)

package com.mochame.sync.infrastructure.serialization

import co.touchlab.kermit.ExperimentalKermitApi
import com.mochame.support.MochaPlatformTest
import com.mochame.support.runUnitEnvironment
import com.mochame.sync.api.metadata.FeatureContext
import com.mochame.sync.api.metadata.MutationOp
import com.mochame.sync.di.codec.CodecFixtureTestEnv
import com.mochame.sync.di.codec.CodecTestModule
import com.mochame.sync.internal.fixtures.assertDecodedIntentParity
import com.mochame.sync.internal.fixtures.createTestSyncIntent
import com.mochame.sync.internal.fixtures.serialization.FakeIntentCodec
import com.mochame.sync.internal.fixtures.serialization.toRouterWithVersion
import com.mochame.sync.domain.model.SyncIntent
import com.mochame.utils.fixtures.TestHlcFactory
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.protobuf.ProtoBuf
import org.koin.plugin.module.dsl.modules
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private inline fun runEnv(crossinline block: CodecFixtureTestEnv.(TestScope) -> Unit) =
    runUnitEnvironment<CodecFixtureTestEnv>(
        koinSetup = { modules(CodecTestModule::class) },
        block = block
    )

@ExperimentalSerializationApi
internal class BatchCodecV1Test : MochaPlatformTest() {

    // -----------------------------------------------------------
    // STANDARD BATCHING
    // -----------------------------------------------------------

    @Test
    fun should_encode_and_decode_single_intent_batch_with_exact_parity() = runEnv {
        val singleIntent = createTestSyncIntent(
            payload = byteArrayOf(0x01, 0x02, 0x03)
        )
        val originalBatch = listOf(singleIntent)

        // Act
        val batchBytes = realBatchCodec.encode(originalBatch)
        val decodedBatch = realBatchCodec.decode(batchBytes)

        assertEquals(1, decodedBatch.size)
        assertDecodedIntentParity(originalBatch[0], decodedBatch[0])
    }

    @Test
    fun should_preserve_strict_causal_ordering_and_count_across_multi_intent_batch() = runEnv {
        // Arrange: 10 chronological intents with distinct sequence indices
        val hlcSequence = TestHlcFactory.chronologicalSequence(size = 10)
        val originalBatch = hlcSequence.mapIndexed { index, hlc ->
            createTestSyncIntent(
                hlc = hlc,
                payload = byteArrayOf(index.toByte(), (index * 2).toByte())
            )
        }

        // Act
        val batchBytes = realBatchCodec.encode(originalBatch)
        val decodedBatch = realBatchCodec.decode(batchBytes)

        // Assert
        assertEquals(originalBatch.size, decodedBatch.size, "Batch size mismatch")

        originalBatch.indices.forEach { index ->
            val expected = originalBatch[index]
            val actual = decodedBatch[index]
            assertDecodedIntentParity(expected, actual)
        }
    }

    @Test
    fun should_preserve_heterogeneous_batch_with_mixed_operations_and_nullability() = runEnv {
        // Arrange: Batch containing mixed ops, populated payload, empty payload, and overflow blob
        val intentUpsert = createTestSyncIntent(
            candidateKey = 0L,
            featureContext = FeatureContext.UNRECOGNIZED_MODEL,
            payload = byteArrayOf(0xDE.toByte(), 0xAD.toByte()),
            overflowBlobId = null,
        )

        val intentDelete = createTestSyncIntent(
            candidateKey = 1L,
            featureContext = FeatureContext.UNRECOGNIZED_MODEL,
            payload = byteArrayOf(), // Empty array
            overflowBlobId = null,
            op = MutationOp.DELETE
        )

        val intentOverflow = createTestSyncIntent(
            candidateKey = 2L,
            featureContext = FeatureContext.UNRECOGNIZED_MODEL,
            payload = null, // Overflow payload
            overflowBlobId = "blob-ref-xyz-99"
        )

        val originalBatch = listOf(intentUpsert, intentDelete, intentOverflow)

        // Act
        val batchBytes = realBatchCodec.encode(originalBatch)
        val decodedBatch = realBatchCodec.decode(batchBytes)

        // Assert
        assertEquals(3, decodedBatch.size)

        // Index 0: UPSERT with populated bytes
        assertDecodedIntentParity(intentUpsert, decodedBatch[0])
        assertContentEquals(byteArrayOf(0xDE.toByte(), 0xAD.toByte()), decodedBatch[0].payload)

        // Index 1: DELETE with empty payload
        assertDecodedIntentParity(intentDelete, decodedBatch[1])
        assertEquals(0, decodedBatch[1].payload?.size)
        assertEquals(1L, decodedBatch[1].candidateKey)

        // Index 2: Overflow blob with null payload
        assertDecodedIntentParity(intentOverflow, decodedBatch[2])
        assertEquals("blob-ref-xyz-99", decodedBatch[2].overflowBlobId)
        assertEquals(2L, decodedBatch[2].candidateKey)
    }

    // -----------------------------------------------------------
    // INVALID STATE
    // -----------------------------------------------------------

    @Test
    fun should_throw_illegal_argument_exception_when_encoding_empty_intent_list() = runEnv {
        val emptyIntents = emptyList<SyncIntent>()

        val exception = assertFailsWith<IllegalArgumentException> {
            realBatchCodec.encode(emptyIntents)
        }

        assertEquals(exception.message?.contains("Cannot serialise an empty payload"), true)
    }

    @Test
    fun should_return_empty_list_when_decoding_valid_batch_payload_with_zero_envelopes() = runEnv {
        // Arrange: Encode a single intent, then decode a manually encoded zero-envelope  payload
        val emptyBatchWirePayload = SyncBatchPayloadV1(
            envelopes = emptyList(),
            intentSchemaVersion = 1
        )
        val emptyBatchBytes = ProtoBuf.encodeToByteArray(
            SyncBatchPayloadV1.serializer(),
            emptyBatchWirePayload
        )

        // Act
        val decodedList = realBatchCodec.decode(emptyBatchBytes)

        assertTrue(decodedList.isEmpty(), "Decoded list must be empty")
    }

    @Test
    fun should_throw_serialization_exception_when_decoding_zero_length_byte_array() = runEnv {
        val zeroBytes = byteArrayOf()

        assertFailsWith<SerializationException> {
            realBatchCodec.decode(zeroBytes)
        }
    }

    // -----------------------------------------------------------
    // PARTIAL CORRUPTION
    // -----------------------------------------------------------

    @Test
    fun should_recover_valid_intents_and_drop_corrupted_middle_envelope() = runEnv {
        // Arrange
        val intent1 = createTestSyncIntent(
            candidateKey = 1L,
            hlc = TestHlcFactory.create(ts = 100L)
        )
        val intent3 = createTestSyncIntent(
            candidateKey = 3L,
            hlc = TestHlcFactory.create(ts = 300L)
        )

        val validBytes1 = intentRouter.versionEncode(intent1)
        val garbageBytes =
            byteArrayOf(0xFF.toByte(), 0x00, 0xFE.toByte(), 0x12) // Unparseable bytes
        val validBytes3 = intentRouter.versionEncode(intent3)

        val batchWirePayload = SyncBatchPayloadV1(
            envelopes = listOf(validBytes1, garbageBytes, validBytes3),
            intentSchemaVersion = 1
        )
        val batchBytes = ProtoBuf.encodeToByteArray(
            SyncBatchPayloadV1.serializer(),
            batchWirePayload
        )

        // Act
        val decodedList = realBatchCodec.decode(batchBytes)

        // Assert: Corrupted middle envelope dropped, surviving valid envelopes preserved
        assertEquals(2, decodedList.size, "Decoded payload must contain exactly 2 surviving intents")

        assertEquals(1L, decodedList[0].candidateKey)
        assertEquals(intent1.hlc, decodedList[0].hlc)

        assertEquals(3L, decodedList[1].candidateKey)
        assertEquals(intent3.hlc, decodedList[1].hlc)

        assertNotNull(writer.logs.find { it.message.contains("Batch decoding degraded: recovered 2/3 intents (1 skipped due to corruption)") })
    }

    @Test
    fun should_preserve_surviving_valid_intent_when_surrounded_by_corrupted_envelopes() = runEnv {
        // Arrange: [Corrupt, Valid, Corrupt]
        val validIntent = createTestSyncIntent(candidateKey = 5L)
        val validBytes = intentRouter.versionEncode(validIntent)

        val garbageBytes1 = byteArrayOf(0x00, 0x01)
        val garbageBytes2 = byteArrayOf(0xDE.toByte(), 0xAD.toByte())

        val batchWirePayload = SyncBatchPayloadV1(
            envelopes = listOf(garbageBytes1, validBytes, garbageBytes2),
            intentSchemaVersion = 1
        )
        val batchBytes = ProtoBuf.encodeToByteArray(
            SyncBatchPayloadV1.serializer(),
            batchWirePayload
        )

        // Act
        val decodedList = realBatchCodec.decode(batchBytes)

        // Assert
        assertEquals(1, decodedList.size)
        assertEquals(5L, decodedList[0].candidateKey)
    }

    @Test
    fun should_return_empty_list_when_all_envelopes_in_batch_are_corrupted() = runEnv {
        // Arrange: All envelopes in payload are garbage bytes
        val garbageEnvelopes = listOf(
            byteArrayOf(0x12, 0x34),
            byteArrayOf(0x56, 0x78),
            byteArrayOf(0x9A.toByte(), 0xBC.toByte())
        )

        val batchWirePayload = SyncBatchPayloadV1(
            envelopes = garbageEnvelopes,
            intentSchemaVersion = 1
        )
        val batchBytes = ProtoBuf.encodeToByteArray(
            SyncBatchPayloadV1.serializer(),
            batchWirePayload
        )

        // Act
        val decodedList = realBatchCodec.decode(batchBytes)

        assertTrue(
            decodedList.isEmpty(),
            "Batch with 100% corrupted envelopes must return emptyList"
        )
    }

    // -----------------------------------------------------------
    // VERSIONING
    // -----------------------------------------------------------

    @Test
    fun should_return_empty_list_and_abort_loop_when_batch_header_specifies_unregistered_schema_version() =
        runEnv {
            // Arrange: Valid intent bytes, but payload header specifies intentSchemaVersion = 99
            val validIntents = List(3) { createTestSyncIntent() }
            val validBytes = validIntents.map { intentRouter.versionEncode(it) }

            val batchWirePayload = SyncBatchPayloadV1(
                envelopes = validBytes,
                intentSchemaVersion = 99 // Unregistered router version
            )
            val batchBytes = ProtoBuf.encodeToByteArray(
                SyncBatchPayloadV1.serializer(),
                batchWirePayload
            )

            // Act
            val decodedList = realBatchCodec.decode(batchBytes)

            assertTrue(
                decodedList.isEmpty(),
                "Unsupported intentSchemaVersion must result in graceful degradation to emptyList"
            )
            val logsCount = writer.logs.count {
                it.message.contains("Aborting Batch Process. Batch Envelope holds invalid version: 99")
            }
            assertEquals(
                1,
                logsCount,
                "Batch Processing should have aborted on first throw of invalid version: 99."
            )
        }

    @Test
    fun should_stamp_router_latest_version_into_batch_payload_header_on_encode() = runEnv() {
        val intent = createTestSyncIntent()

        // Act
        val batchBytes = realBatchCodec.encode(listOf(intent))
        val rawBatchPayload = ProtoBuf.decodeFromByteArray(
            SyncBatchPayloadV1.serializer(),
            batchBytes
        )

        assertEquals(
            intentRouter.latestVersion,
            rawBatchPayload.intentSchemaVersion,
            "SyncBatchPayloadV1 header must stamp latestVersion from IntentCodecRouter"
        )
    }

    @Test
    fun should_dispatch_envelopes_to_correct_codec_version_based_on_header_version() = runEnv {
        // Arrange: BatchCodec with MultiVersioned Router
        val multiVersionIntentRouter = realIntentCodec.toRouterWithVersion(fakeIntentCodec, logger)
        val batchCodecFixture = BatchCodecV1(multiVersionIntentRouter, logger)
        // Payload stamped with intentSchemaVersion = 2
        val v2BatchWirePayload = SyncBatchPayloadV1(
            envelopes = listOf(FakeIntentCodec.BYTES_PRESET),
            intentSchemaVersion = 2
        )
        val v2BatchBytes = ProtoBuf.encodeToByteArray(
            SyncBatchPayloadV1.serializer(),
            v2BatchWirePayload
        )

        // Act
        val decodedList = batchCodecFixture.decode(v2BatchBytes)

        // Assert: FakeIntentCodec accepted bytes: require(bytes.contentEquals(BYTES_PRESET)), returning Preset
        assertEquals(FakeIntentCodec.MODEL_PRESET, decodedList[0])
        assertFailsWith<SerializationException> {
            realIntentCodec.decode(FakeIntentCodec.BYTES_PRESET)
        }
    }

}

