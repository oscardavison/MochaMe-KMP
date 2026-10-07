package com.mochame.sync.infrastructure.serialization

import com.mochame.support.MochaPlatformTest
import com.mochame.support.runUnitEnvironment
import com.mochame.sync.api.FieldResolver
import com.mochame.sync.api.codec.BaseFeatureCodec.Companion.TAG_CREATED_AT
import com.mochame.sync.api.codec.diff
import com.mochame.sync.api.metadata.MutationOp
import com.mochame.sync.api.metadata.toTagSummary
import com.mochame.sync.di.codec.CodecTestModule
import com.mochame.sync.internal.fixtures.serialization.FeatureCodecV1
import com.mochame.sync.internal.fixtures.serialization.FeatureCodecV1.Companion.TAG_COUNT_VALUE
import com.mochame.sync.internal.fixtures.serialization.FeatureCodecV1.Companion.TAG_TEXT_VALUE
import com.mochame.sync.internal.fixtures.serialization.FeatureEntity
import com.mochame.sync.internal.fixtures.serialization.FeatureEntityDeltaV1
import com.mochame.sync.utils.toBitmask
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import org.koin.plugin.module.dsl.modules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

private inline fun runEnv(crossinline block: suspend FeatureCodecV1.(TestScope) -> Unit) =
    runUnitEnvironment<FeatureCodecV1>(
        koinSetup = { modules(CodecTestModule::class) },
        block = block
    )

@ExperimentalSerializationApi
class FeatureCodecTest : MochaPlatformTest() {

    // -------------------------------------------------------------------
    // DELTA ENCODING & DESERIALIZATION
    // -------------------------------------------------------------------

    @Test
    fun should_encodeAndDeserializeFullPayload_when_insertingNewEntity() = runEnv {
        // Given
        val newEntity = FeatureEntity()

        // When
        val bytes = encode(new = newEntity, old = null)
        val delta = deserializeDelta(bytes)

        // Then
        assertNotNull(bytes)
        assertEquals(newEntity.id, delta.id)
        assertEquals(newEntity.textValue, delta.textValue)
        assertEquals(newEntity.countValue, delta.countValue)
        assertNull(delta.isDeleted)
        assertNotNull(delta.createdAt)
        assertEquals(newEntity.createdAt, Instant.fromEpochMilliseconds(delta.createdAt))
    }

    @Test
    fun should_encodeAndDeserializeSparseDelta_when_partiallyUpdatingFields() = runEnv {
        // Given
        val oldEntity = FeatureEntity()
        val newEntity = oldEntity.copy(countValue = 20)

        // When
        val fullBytes = encode(new = oldEntity, old = null)
        val sparseBytes = encode(new = newEntity, old = oldEntity)
        val delta = deserializeDelta(sparseBytes)

        // Then
        assertNotNull(fullBytes)
        assertNotNull(sparseBytes)
        assertTrue(sparseBytes.size < fullBytes.size)
        assertEquals(oldEntity.id, delta.id)
        assertNull(delta.isDeleted)
        assertNull(delta.textValue)
        assertEquals(20, delta.countValue)
    }

    @Test
    fun should_encodeDeletionOnly_when_entityIsDeleted() = runEnv {
        // Given
        val oldEntity = FeatureEntity()
        val deletedEntity = oldEntity.copy(
            textValue = "modified but deleted",
            countValue = 999,
            isDeleted = true
        )

        // When
        val bytes = encode(new = deletedEntity, old = oldEntity)
        val delta = deserializeDelta(bytes)

        // Then
        assertNotNull(bytes)
        assertEquals(oldEntity.id, delta.id)
        assertEquals(true, delta.isDeleted)
        assertNull(delta.textValue)
        assertNull(delta.countValue)
    }

    @Test
    fun should_throwSerializationException_when_decodingCorruptBytes() = runEnv {
        // Given
        val corruptBytes = byteArrayOf(0x00, 0x80.toByte(), 0xFF.toByte())

        // When & Then
        assertFailsWith<SerializationException> {
            deserializeDelta(corruptBytes)
        }
    }

    // -------------------------------------------------------------------
    // DOMAIN MERGING
    // -------------------------------------------------------------------

    @Test
    fun should_mergeDomainFieldsUsingResolver_when_deltaProvided() = runEnv {
        // Given
        val existing = FeatureEntity(id = 100L, textValue = "original", countValue = 10)
        val delta = FeatureEntityDeltaV1(
            id = 100L,
            textValue = "updated",
            countValue = null
        )
        val testResolver = object : FieldResolver {
            override fun <V> resolve(tagId: Int, incoming: V?, existing: V?): V? = incoming ?: existing
        }

        // When
        val merged = mergeDomain(
            resolver = testResolver,
            delta = delta,
            candidateKey = 100L,
            existing = existing
        )

        // Then
        assertEquals(100L, merged.id)
        assertEquals("updated", merged.textValue)
        assertEquals(10, merged.countValue)
    }

    @Test
    fun should_mergeDomainFieldsAgainstNullExisting_when_insertDeltaProvided() = runEnv {
        // Given
        val delta = FeatureEntityDeltaV1(
            id = 200L,
            textValue = "new-record",
            countValue = 42
        )
        val testResolver = object : FieldResolver {
            override fun <V> resolve(tagId: Int, incoming: V?, existing: V?): V? = incoming ?: existing
        }

        // When
        val merged = mergeDomain(
            resolver = testResolver,
            delta = delta,
            candidateKey = 200L,
            existing = null
        )

        // Then
        assertEquals(200L, merged.id)
        assertEquals("new-record", merged.textValue)
        assertEquals(42, merged.countValue)
    }

    // -------------------------------------------------------------------
    // SUMMARY PARITY (IN-MEMORY vs BINARY RECONSTRUCTION)
    // -------------------------------------------------------------------

    @Test
    fun should_maintainSummaryParity_on_fullInsert() = runEnv {
        // Given
        val newEntity = FeatureEntity()
        val bytes = encode(new = newEntity, old = null)
        val changedTags = computeChangedTags(newEntity, null)

        // When
        val inMemorySummary = changedTags.toBitmask().toTagSummary(MutationOp.UPSERT)
        val binarySummary = reconstructSummary(bytes)
        val (inMemOp, inMemTags) = parseSummary(inMemorySummary)
        val (binOp, binTags) = parseSummary(binarySummary)

        // Then
        assertEquals(MutationOp.UPSERT.name, inMemOp)
        assertEquals(inMemOp, binOp)
        assertEquals(listOf(TAG_CREATED_AT, TAG_TEXT_VALUE, TAG_COUNT_VALUE), inMemTags)
        assertEquals(inMemTags, binTags)
    }

    @Test
    fun should_maintainSummaryParity_on_partialUpdate() = runEnv {
        // Given
        val oldEntity = FeatureEntity()
        val newEntity = oldEntity.copy(countValue = 99)
        val bytes = encode(new = newEntity, old = oldEntity)
        val changedTags = computeChangedTags(newEntity, oldEntity)

        // When
        val inMemorySummary = changedTags.toBitmask().toTagSummary(MutationOp.UPSERT)
        val binarySummary = reconstructSummary(bytes)
        val (inMemOp, inMemTags) = parseSummary(inMemorySummary)
        val (binOp, binTags) = parseSummary(binarySummary)

        // Then
        assertEquals("UPSERT", inMemOp)
        assertEquals(inMemOp, binOp)
        assertEquals(listOf(TAG_COUNT_VALUE), inMemTags)
        assertEquals(inMemTags, binTags)
    }

    @Test
    fun should_maintainSummaryParity_on_tombstoneDelete() = runEnv {
        // Given
        val oldEntity = FeatureEntity()
        val deletedEntity = oldEntity.withDeleteState(true)
        val bytes = encode(new = deletedEntity, old = oldEntity)
        val changedTags = computeChangedTags(deletedEntity, oldEntity)

        // When
        val inMemorySummary = changedTags.toBitmask().toTagSummary(MutationOp.DELETE)
        val binarySummary = reconstructSummary(bytes)
        val (inMemOp, inMemTags) = parseSummary(inMemorySummary)
        val (binOp, binTags) = parseSummary(binarySummary)

        // Then
        assertEquals("DELETE", inMemOp)
        assertEquals(inMemOp, binOp)
        assertEquals(1, inMemTags.size)
        assertEquals(1, binTags.size)
    }

    // -------------------------------------------------------------------
    // BINARY CORRUPTION & BUFFER PEEKING
    // -------------------------------------------------------------------

    @Test
    fun should_returnInvalidEmptyBytes_when_payloadIsEmpty() = runEnv {
        val emptyBytes = ByteArray(0)

        val summary = reconstructSummary(emptyBytes)

        assertEquals("OP:INVALID_EMPTY_BYTES", summary)
    }

    @Test
    fun should_returnCorruptPacket_when_varintIsTruncated() = runEnv {
        // Given: Truncated MSB varint
        val truncatedVarintBytes = byteArrayOf(0x80.toByte())

        // When
        val summary = reconstructSummary(truncatedVarintBytes)

        // Then
        assertEquals("OP:CORRUPT_PACKET", summary)
    }

    @Test
    fun should_returnCorruptPacket_when_wireTypeIsInvalid() = runEnv {
        // Given: Unsupported wire type 6 -> (1 shl 3) or 6 = 14 (0x0E)
        val invalidWireTypeBytes = byteArrayOf(0x0E.toByte(), 0x01.toByte())

        // When
        val summary = reconstructSummary(invalidWireTypeBytes)

        // Then
        assertEquals("OP:CORRUPT_PACKET", summary)
    }

    @Test
    fun should_skipLengthDelimitedPayloadsWithoutCorruptingBuffer_when_payloadIsLarge() = runEnv {
        // Given
        val largeText = "A".repeat(2048)
        val entityWithLargePayload = FeatureEntity(
            textValue = largeText,
            countValue = 999
        )
        val bytes = encode(new = entityWithLargePayload, old = null)

        // When
        val summary = reconstructSummary(bytes)

        // Then
        assertTrue(bytes.size > 2048)
        assertTrue(summary.startsWith("OP:UPSERT"))
        assertTrue(summary.contains("$TAG_TEXT_VALUE") && summary.contains("$TAG_COUNT_VALUE"))
    }

    @Test
    fun should_cleanlyResetBuffer_when_peekingSequentially() = runEnv {
        // Given
        val largeEntity = FeatureEntity(
            textValue = "Long text string to pad the buffer size",
            countValue = 99999
        )
        val largeBytes = encode(new = largeEntity, old = null)
        val smallEntity = largeEntity.copy(countValue = 3)
        val smallBytes = encode(new = smallEntity, old = largeEntity)

        // When
        val summary1 = reconstructSummary(largeBytes)
        val summary2 = reconstructSummary(smallBytes)

        // Then
        assertEquals("OP:UPSERT [3,4,5]", summary1)
        assertEquals("OP:UPSERT [5]", summary2)
    }

    // -------------------------------------------------------------------
    // DIFF OPERATOR
    // -------------------------------------------------------------------

    @Test
    fun should_produceExpectedDiffOutcome_when_diffingValues() {
        // Given & When & Then
        assertEquals("new", "new" diff "old")
        assertNull("same" diff "same")
        assertNull(null diff "old")
        assertNull(null diff null)
    }

    // --- HELPER ---
    private fun parseSummary(summary: String): Pair<String, List<Int>> {
        val parts = summary.split(" ")
        val rawOp = parts[0].removePrefix("OP:")

        val tags = if (parts.size > 1) {
            parts[1]
                .removePrefix("[")
                .removeSuffix("]")
                .split(",")
                .filter { it.isNotBlank() }
                .map { it.toInt() }
                .filter { it != 1 }
        } else {
            emptyList()
        }

        return rawOp to tags
    }
}
