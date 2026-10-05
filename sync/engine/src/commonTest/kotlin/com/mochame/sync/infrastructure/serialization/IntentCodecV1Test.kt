package com.mochame.sync.infrastructure.serialization

import com.mochame.support.MochaPlatformTest
import com.mochame.support.runUnitEnvironment
import com.mochame.sync.api.metadata.FeatureContext
import com.mochame.sync.api.metadata.MutationOp
import com.mochame.sync.api.metadata.SyncStatus
import com.mochame.sync.di.codec.CodecTestModule
import com.mochame.sync.internal.fixtures.assertDecodedIntentParity
import com.mochame.sync.internal.fixtures.createTestSyncIntent
import com.mochame.utils.fixtures.TestHlcFactory
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import org.koin.plugin.module.dsl.modules
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull


// -------------------------------------------------------------------
// SUT ENVIRONMENT
// -------------------------------------------------------------------
private inline fun runEnv(crossinline block: suspend IntentCodecV1.(TestScope) -> Unit) =
    runUnitEnvironment<IntentCodecV1>(
        koinSetup = { modules(CodecTestModule::class) },
        block = block
    )


@ExperimentalSerializationApi
class IntentCodecV1Test : MochaPlatformTest() {

    // -------------------------------------------------------------------
    // PAYLOAD LIFECYCLE
    // -------------------------------------------------------------------

    @Test
    fun should_preserve_populated_payload_and_created_at_across_encode_decode() = runEnv {
        // Given
        val expectedHlc = TestHlcFactory.create(ts = 1740787200000L, count = 2)
        val originalPayload = byteArrayOf(0x01, 0x02, 0x0F, 0x7F, -0x80)
        val originalCreatedAt = 1740787000000L

        val originalIntent = createTestSyncIntent(
            hlc = expectedHlc,
            payload = originalPayload,
            createdAt = originalCreatedAt,
            overflowBlobId = null
        )

        // When
        val encodedBytes = encode(originalIntent)
        val decodedIntent = decode(encodedBytes)

        // Then
        assertDecodedIntentParity(originalIntent, decodedIntent)
        assertContentEquals(originalPayload, decodedIntent.payload)
    }

    @Test
    fun should_differentiate_and_preserve_empty_byte_array_payload() = runEnv {
        // Given
        val emptyPayload = byteArrayOf()
        val originalIntent = createTestSyncIntent(
            payload = emptyPayload,
            overflowBlobId = null
        )

        // When
        val encodedBytes = encode(originalIntent)
        val decodedIntent = decode(encodedBytes)

        // Then
        assertDecodedIntentParity(originalIntent, decodedIntent)
        assertEquals(0, decodedIntent.payload?.size)
        assertContentEquals(emptyPayload, decodedIntent.payload)
    }

    @Test
    fun should_preserve_null_payload_and_retain_overflow_blob_id() = runEnv {
        // Given
        val originalIntent = createTestSyncIntent(
            payload = null,
            overflowBlobId = "blob-overflow-uuid-9981"
        )

        // When
        val encodedBytes = encode(originalIntent)
        val decodedIntent = decode(encodedBytes)

        // Then
        assertDecodedIntentParity(originalIntent, decodedIntent)
        assertNull(decodedIntent.payload)
        assertEquals("blob-overflow-uuid-9981", decodedIntent.overflowBlobId)
    }

    @Test
    fun should_handle_large_payload_1mb_without_truncation_or_corruption() = runEnv {
        // Given
        val largePayload = ByteArray(1_024 * 1_024) { (it % 256).toByte() }
        val originalIntent = createTestSyncIntent(
            payload = largePayload,
            overflowBlobId = null
        )

        // When
        val encodedBytes = encode(originalIntent)
        val decodedIntent = decode(encodedBytes)

        // Then
        assertEquals(largePayload.size, decodedIntent.payload?.size)
        assertContentEquals(largePayload, decodedIntent.payload)
        assertDecodedIntentParity(originalIntent, decodedIntent)
    }

    // -------------------------------------------------------------------
    // FIELD LIFECYCLE
    // -------------------------------------------------------------------

    @Test
    fun should_reset_sync_status_to_RECEIVED_and_retry_count_to_zero_when_decoding_non_default_states() =
        runEnv {
            val originalIntent = createTestSyncIntent(
                status = SyncStatus.FAILED,
                retryCount = 7
            )

            // Act
            val bytes = encode(originalIntent)
            val decoded = decode(bytes)

            // Assert: Wire normalization forces RECEIVED and 0 retry count
            assertEquals(
                SyncStatus.RECEIVED,
                decoded.syncStatus,
                "syncStatus must normalize to RECEIVED upon decode"
            )
            assertEquals(
                0,
                decoded.retryCount,
                "retryCount must reset to 0 upon decode"
            )
        }

    @Test
    fun should_preserve_candidate_key_and_feature_context_metadata_parity() = runEnv {
        // Arrange
        val expectedKey = 1L
        val expectedContext = FeatureContext.UNRECOGNIZED_MODEL

        val originalIntent = createTestSyncIntent(
            candidateKey = expectedKey,
            featureContext = expectedContext
        )

        // Act
        val bytes = encode(originalIntent)
        val decoded = decode(bytes)

        assertEquals(expectedKey, decoded.candidateKey, "candidateKey parity failed")
        assertEquals(
            originalIntent.featureContext.modelName,
            decoded.featureContext.modelName,
            "modelName parity failed"
        )
        assertEquals(
            originalIntent.featureContext.featureName,
            decoded.featureContext.featureName,
            "featureName parity failed across FeatureContext reconstruction"
        )
    }

    @Test
    fun should_preserve_enum_string_parity_for_all_mutation_operations() = runEnv {
        MutationOp.entries.forEachIndexed { index, op ->
            val originalIntent = createTestSyncIntent(
                candidateKey = index.toLong(),
                payload = byteArrayOf(0x01),
                op = op
            )

            // Act
            val bytes = encode(originalIntent)
            val decoded = decode(bytes)

            assertEquals(
                op,
                decoded.operation,
                "MutationOp parity failed for operation: ${op.name}"
            )
        }
    }

    @Test
    fun should_preserve_hlc_field_integrity_across_string_parse_cycle() = runEnv {
        val customHlc = TestHlcFactory.create(
            ts = 1740787200000L,
            count = 42,
        )
        val originalIntent = createTestSyncIntent(hlc = customHlc)

        // Act
        val bytes = encode(originalIntent)
        val decoded = decode(bytes)

        // Assert
        assertEquals(customHlc.ts, decoded.hlc.ts, "HLC physical timestamp mismatch")
        assertEquals(customHlc.count, decoded.hlc.count, "HLC logical counter mismatch")
        assertEquals(customHlc.nodeId, decoded.hlc.nodeId, "HLC node ID mismatch")
        assertEquals(customHlc, decoded.hlc, "HLC structural equality failed")
    }

    @Test
    fun should_preserve_original_created_at_without_overriding_with_system_clock() = runEnv {
        val historicalCreatedAt = 1600000000000L // Sun Sep 13 2020
        val originalIntent = createTestSyncIntent(
            createdAt = historicalCreatedAt
        )

        // Act
        val bytes = encode(originalIntent)
        val decoded = decode(bytes)

        assertEquals(
            historicalCreatedAt,
            decoded.createdAt,
            "createdAt must match the original intent timestamp and not be overriden by system clock"
        )
    }

    @Test
    fun should_strip_local_leased_at_stamp_during_wire_serialization_lifecycle() = runEnv {
        val batchLeasedAt = 1740788000000L
        val localBatchId = 88192L
        val leasedIntent = createTestSyncIntent(
            batchId = localBatchId,
            leasedAt = batchLeasedAt
        )

        // Act
        val bytes = encode(leasedIntent)
        val decoded = decode(bytes)

        assertNull(
            decoded.leasedAt,
            "leasedAt is local engine client metadata and must decode as null from wire payload"
        )
        assertNull(
            decoded.batchId,
            "syncId is local client execution metadata and must decode as null from wire payload"
        )
    }

    // -----------------------------------------------------------
    // PROTOBUF CORRUPTION
    // -----------------------------------------------------------

    @Test
    fun should_throw_serialization_exception_when_decoding_random_garbage_bytes() = runEnv {
        val garbageBytes = byteArrayOf(0xFF.toByte(), 0x00, 0xFE.toByte(), 0x12, 0x34)

        assertFailsWith<SerializationException> {
            decode(garbageBytes)
        }
    }

    @Test
    fun should_throw_serializationException_exception_when_decoding_truncated_protobuf_payload() =
        runEnv {
            val validIntent = createTestSyncIntent()
            val fullBytes = encode(validIntent)
            val truncatedBytes = fullBytes.copyOf(fullBytes.size / 2)

            assertFailsWith<SerializationException> { decode(truncatedBytes) }
        }

    @Test
    fun should_throw_serialization_exception_when_decoding_empty_byte_array() = runEnv {
        assertFailsWith<SerializationException> {
            decode(byteArrayOf())
        }
    }

    @Test
    fun should_preserve_boundary_integer_values_for_feature_schema_version() = runEnv {
        val boundaryVersions = listOf(
            Int.MIN_VALUE,
            -1,
            0,
            1,
            Int.MAX_VALUE
        )

        boundaryVersions.forEach { version ->
            val originalIntent = createTestSyncIntent(featureSchemaVersion = version)
            val bytes = encode(originalIntent)
            val decoded = decode(bytes)

            assertEquals(
                version,
                decoded.featureSchemaVersion,
                "featureSchemaVersion boundary integer $version failed round-trip"
            )
        }
    }
}