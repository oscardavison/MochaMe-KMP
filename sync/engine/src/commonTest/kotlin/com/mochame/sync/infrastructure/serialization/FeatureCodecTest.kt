package com.mochame.sync.infrastructure.serialization

import com.mochame.support.MochaPlatformTest
import com.mochame.support.runUnitEnvironment
import com.mochame.sync.api.models.instant
import com.mochame.sync.api.metadata.MutationOp
import com.mochame.sync.api.metadata.toTagSummary
import com.mochame.sync.api.internal.bitmaskOf
import com.mochame.sync.api.internal.toBitmask
import com.mochame.sync.api.internal.withTag
import com.mochame.sync.di.codec.CodecTestModule
import com.mochame.sync.internal.fixtures.serialization.FeatureCodecV1
import com.mochame.sync.internal.fixtures.serialization.FeatureCodecV1.Companion.TAG_COUNT_VALUE
import com.mochame.sync.internal.fixtures.serialization.FeatureCodecV1.Companion.TAG_TEXT_VALUE
import com.mochame.sync.internal.fixtures.serialization.FeatureEntity
import com.mochame.sync.internal.fixtures.serialization.FeatureEntityDeltaV1
import com.mochame.sync.internal.fixtures.serialization.assertDecodeParity
import com.mochame.sync.internal.fixtures.serialization.deriveContext
import com.mochame.sync.spi.infrastructure.serialization.BaseFeatureCodec.Companion.TAG_CREATED_AT
import com.mochame.sync.spi.infrastructure.serialization.BaseFeatureCodec.Companion.TAG_IS_DELETED
import com.mochame.sync.spi.infrastructure.serialization.diff
import com.mochame.sync.spi.models.DecodeContext
import com.mochame.utils.fixtures.TestHlcFactory
import kotlinx.coroutines.test.TestScope
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.protobuf.ProtoBuf
import org.koin.plugin.module.dsl.modules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Instant


// -----------------------------------------------------------
// SUT ENVIRONMENT
// -----------------------------------------------------------
private inline fun runEnv(crossinline block: suspend FeatureCodecV1.(TestScope) -> Unit) =
    runUnitEnvironment<FeatureCodecV1>(
        koinSetup = { modules(CodecTestModule::class) },
        block = block
    )

@ExperimentalSerializationApi
class FeatureCodecTest : MochaPlatformTest() {

    // -------------------------------------------------------------------
    // DELTA ENCODING TESTS
    // -------------------------------------------------------------------

    @Test
    fun should_encodeFullPayload_when_insertingNewEntity() = runEnv {
        val newEntity = FeatureEntity()

        val bytes = encode(new = newEntity, old = null)
        assertNotNull(bytes)
        val delta = ProtoBuf.decodeFromByteArray(FeatureEntityDeltaV1.serializer(), bytes)

        assertEquals(newEntity.id, delta.id)
        assertEquals(newEntity.textValue, delta.textValue)
        assertEquals(newEntity.countValue, delta.countValue)
        assertNull(delta.isDeleted)
        assertNotNull(delta.createdAt)
        assertEquals(newEntity.createdAt, Instant.fromEpochMilliseconds(delta.createdAt))
    }

    @Test
    fun should_encodeSparseDelta_when_partiallyUpdatingFields() = runEnv {
        // Given
        val oldEntity = FeatureEntity()
        val newEntity = oldEntity.copy(countValue = 20)

        // When Encode
        val fullBytes = encode(new = oldEntity, old = null)
        val sparseBytes = encode(new = newEntity, old = oldEntity)
        assertNotNull(fullBytes)
        assertNotNull(sparseBytes)
        assertTrue(
            sparseBytes.size < fullBytes.size,
            "Sparse delta must be smaller than full payload"
        )

        // When Decode
        val delta =
            ProtoBuf.decodeFromByteArray(FeatureEntityDeltaV1.serializer(), sparseBytes)
        assertEquals(oldEntity.id, delta.id)
        assertNull(delta.isDeleted, "Unchanged deletion state must be omitted")
        assertNull(delta.textValue, "Unchanged textValue must be omitted")
        assertEquals(20, delta.countValue)
    }

    @Test
    fun should_encodeDeletionOnly_when_entityIsDeleted() = runEnv {
        val oldEntity = FeatureEntity()
        val deletedEntity = oldEntity.copy(
            textValue = "modified but deleted",
            countValue = 999,
            isDeleted = true
        )

        val bytes = encode(new = deletedEntity, old = oldEntity)
        assertNotNull(bytes)

        val delta = ProtoBuf.decodeFromByteArray(FeatureEntityDeltaV1.serializer(), bytes)
        assertEquals(oldEntity.id, delta.id)
        assertEquals(true, delta.isDeleted)
        assertNull(delta.textValue, "Payload fields must be stripped on delete")
        assertNull(delta.countValue, "Payload fields must be stripped on delete")
    }

    // -------------------------------------------------------------------
    // DECODING & HYDRATION TESTS
    // -------------------------------------------------------------------

    @Test
    fun should_hydrateFullEntityAndInternalizeFieldLWW_when_existingStateIsNull() = runEnv {
        // Given
        val insertEntity = FeatureEntity(
            id = 1000L,
            textValue = "initial payload",
            countValue = 42,
            fieldHlcs = ByteArray(0)
        )
        val inboundBytes = encode(new = insertEntity, old = null)
        val context =
            insertEntity.deriveContext(changedMask = bitmaskOf(TAG_TEXT_VALUE, TAG_COUNT_VALUE))

        // When
        val hydrated = decode(bytes = inboundBytes, context = context, existing = null)

        // Then: Values match and fieldHlcs is populated with an opaque non-empty blob
        hydrated.assertDecodeParity(insertEntity)
        assertTrue(
            hydrated.fieldHlcs.isNotEmpty(),
            "Codec must generate an opaque fieldHlcs tracking blob"
        )
    }

    @Test
    fun should_mergeSparseDeltaAndPreserveUnchangedFields_when_incomingHlcIsNewer() = runEnv {
        // Given:  Hydrate initial entity (gets real underlying binary blob)
        val hlcs = TestHlcFactory.chronologicalSequence(2)
        val fieldHlclessEntity =
            FeatureEntity(id = 1000L, hlc = hlcs[0], countValue = 10, textValue = "keep me")
        val initialEntity = decode(
            bytes = encode(new = fieldHlclessEntity, old = null),
            context = fieldHlclessEntity.deriveContext(
                changedMask = bitmaskOf(TAG_TEXT_VALUE, TAG_COUNT_VALUE)
            ),
            existing = null
        )

        // Given: Remote delta mutates only countValue at a newer HLC (hlcs[1] > hlcs[0])
        val updatedSparseEntity = initialEntity.copy(countValue = 50, hlc = hlcs[1])
        val sparseBytes = encode(new = updatedSparseEntity, old = initialEntity)
        val remoteContext =
            updatedSparseEntity.deriveContext(changedMask = bitmaskOf(TAG_COUNT_VALUE))

        // When: Decode sparse delta over existing entity
        val merged = decode(bytes = sparseBytes, context = remoteContext, existing = initialEntity)

        // Then: Mutated field updates while omitted field remains intact
        assertEquals(50, merged.countValue, "Mutated countValue must update to 50")
        assertEquals("keep me", merged.textValue, "Omitted textValue must remain intact")
        assertNotSame(
            merged.fieldHlcs,
            initialEntity.fieldHlcs,
            "Binary Blob should adjust for changed causality."
        )
    }

    @Test
    fun should_rejectSparseFieldUpdate_when_incomingHlcIsOlderThanLocalField() = runEnv {
        // Given: Local entity hydrated at newer clock (hlcs[1])
        val hlcs = TestHlcFactory.chronologicalSequence(2)
        val olderHlc = hlcs[0]
        val newerHlc = hlcs[1]

        val existingEntity = decode(
            bytes = encode(new = FeatureEntity(id = 1000L, countValue = 100), old = null),
            context = DecodeContext(
                candidateKey = 1000L,
                hlc = newerHlc,
                op = MutationOp.UPSERT,
                featureSchemaVersion = 1,
                changedMask = 0L.withTag(TAG_COUNT_VALUE)
            ),
            existing = null
        )

        // Given: Inbound stale delta attempting to overwrite countValue with older clock (olderHlc)
        val staleSparseBytes = encode(
            new = existingEntity.copy(countValue = 20, hlc = olderHlc),
            old = existingEntity
        )
        val staleContext = DecodeContext(
            candidateKey = existingEntity.id,
            hlc = olderHlc,
            op = MutationOp.UPSERT,
            featureSchemaVersion = 1,
            changedMask = 0L.withTag(TAG_COUNT_VALUE)
        )

        // When: Decode stale update against newer existing entity
        val decoded =
            decode(bytes = staleSparseBytes, context = staleContext, existing = existingEntity)

        // Then: LWW rejects the stale value; existing local value (100) is preserved
        assertEquals(
            100,
            decoded.countValue,
            "Stale countValue (20) must be rejected by LWW in favor of local (100)"
        )
        assertSame(
            decoded.fieldHlcs,
            existingEntity.fieldHlcs,
            "Binary Blob should adjust for changed causality."
        )
    }

    @Test
    fun should_trackDeletionAndEnforceRestoreLww_when_processingDeletionLifecycle() = runEnv {
        val hlcs = TestHlcFactory.chronologicalSequence(3)
        val hlcInsert = hlcs[0]
        val hlcDelete = hlcs[1]
        val hlcRestore = hlcs[2]
        val initialEntity =
            FeatureEntity(id = 1000L, hlc = hlcInsert, textValue = "alive", countValue = null)


        // 1. Initial Insert
        val decodedInitial = decode(
            bytes = encode(new = initialEntity, old = null),
            context = initialEntity.deriveContext(changedMask = 0L.withTag(TAG_TEXT_VALUE)),
            existing = null
        )
        assertFalse(decodedInitial.isDeleted)

        // 2. Delete (hlcDelete > hlcInsert)
        val deletedEntity = decodedInitial.copy(isDeleted = true, hlc = hlcDelete)
        val deleteBytes = encode(new = deletedEntity, old = decodedInitial)
        val decodedDeleted = decode(
            bytes = deleteBytes,
            context = deletedEntity.deriveContext(changedMask = 0L.withTag(TAG_IS_DELETED)),
            existing = decodedInitial
        )
        assertTrue(decodedDeleted.isDeleted, "Entity must be marked deleted")

        // 3. Stale Upsert Attempt (hlcInsert < hlcDelete)
        val staleEntity = decodedInitial.copy(textValue = "stale edit")
        val persistedDelete = decode(
            bytes = encode(new = staleEntity, old = null),
            context = staleEntity.deriveContext(changedMask = 0L.withTag(TAG_TEXT_VALUE)),
            existing = decodedDeleted
        )
        assertTrue(persistedDelete.isDeleted, "Stale upsert must not restore deleted entity")

        // 4. Valid Restore Upsert (hlcRestore > hlcDelete)
        val restoredEntity = decodedInitial.copy(textValue = "hello again", hlc = hlcRestore)
        val restoreBytes = encode(new = restoredEntity, old = null)

        val finalEntity = decode(
            bytes = restoreBytes,
            context = restoredEntity.deriveContext(
                changedMask = bitmaskOf(TAG_IS_DELETED, TAG_TEXT_VALUE)
            ),
            existing = persistedDelete
        )
        assertFalse(finalEntity.isDeleted, "Newer update must resurrect entity")
        assertEquals("hello again", finalEntity.textValue)
        assertNotSame(persistedDelete.fieldHlcs, finalEntity.fieldHlcs)
    }

    @Test
    fun should_overwriteExistingField_when_deltaContainsEmptyString() = runEnv {
        val existingEntity = FeatureEntity()

        // When (Device A: User clears field)
        val clearedEntity = existingEntity.copy(textValue = null)
        val bytes = encode(new = clearedEntity, old = existingEntity)

        // When (Device B: Decode)
        val context = clearedEntity.deriveContext(changedMask = 0b00010000L)
        val decoded = decode(bytes = bytes, context = context, existing = existingEntity)

        assertNull(decoded.textValue, "Empty string delta must set field to null")
        assertEquals(existingEntity.id, decoded.id)
    }

    @Test
    fun should_resolveToEarlierCreatedAt_when_concurrentOfflineInsertsOccur() = runEnv {
        val candidateKey = 306L
        val earlyInstant = Instant.fromEpochMilliseconds(1_700_000_000_000L)
        val lateInstant = Instant.fromEpochMilliseconds(1_700_000_300_000L)

        // Given (Device B: Created entity offline with a later timestamp)
        val localEntityOnDeviceB = FeatureEntity(
            id = candidateKey,
            createdAt = lateInstant,
            textValue = "DEVICE_B_INSERT"
        )

        // Given (Device A: Created entity offline earlier)
        val remoteEntityOnDeviceA = FeatureEntity(
            id = candidateKey,
            createdAt = earlyInstant,
            textValue = "DEVICE_A_INSERT"
        )

        // When (Device A: Encodes its initial insert delta)
        val bytes = encode(new = remoteEntityOnDeviceA, old = null)

        // When (Device B: Receives and decodes Device A's insert against its existing offline record)
        val context = remoteEntityOnDeviceA.deriveContext()
        val decoded = decode(bytes = bytes, context = context, existing = localEntityOnDeviceB)

        // Then
        assertEquals(earlyInstant, decoded.createdAt)
        assertEquals(candidateKey, decoded.id)
    }

    @Test
    fun should_adoptRemoteCreatedAt_when_insertDeltaDecodedAgainstNullExisting() = runEnv {
        val candidateKey = 306L
        val remoteInstant = Instant.fromEpochMilliseconds(1_700_000_000_000L)

        // Given (Device A: Remote entity created with specific timestamp)
        val remoteEntityOnDeviceA = FeatureEntity(
            id = candidateKey,
            createdAt = remoteInstant,
            textValue = "DEVICE_A_INSERT"
        )

        // When (Device A: Encodes initial insert delta)
        val bytes = encode(new = remoteEntityOnDeviceA, old = null)

        // When (Device B: Receives and decodes Device A's delta with no local existing entity)
        val context = remoteEntityOnDeviceA.deriveContext(changedMask = 0L.withTag(TAG_TEXT_VALUE))
        val decoded = decode(bytes = bytes, context = context, existing = null)

        // Then
        assertEquals(remoteInstant, decoded.createdAt)
        assertEquals(candidateKey, decoded.id)
        assertEquals("DEVICE_A_INSERT", decoded.textValue)
    }

    @Test
    fun should_maintainOriginalCreatedAt_when_updateDeltaOmitsCreatedAt() = runEnv {
        val candidateKey = 306L
        val originalInstant = Instant.fromEpochMilliseconds(1_700_000_000_000L)

        // Given (Both devices share the same initial baseline state)
        val existingEntity = FeatureEntity(
            id = candidateKey,
            createdAt = originalInstant,
            textValue = "INITIAL_VALUE"
        )

        // When (Device B: Mutates domain field; buildUpdateDelta omits createdAt)
        val updatedOnDeviceB = existingEntity.copy(textValue = "UPDATED_VALUE")
        val bytes = encode(new = updatedOnDeviceB, old = existingEntity)

        // When (Device A: Decodes Device B's delta against its existing entity)
        val context = updatedOnDeviceB.deriveContext(changedMask = 0L.withTag(TAG_TEXT_VALUE))
        val decoded = decode(bytes = bytes, context = context, existing = existingEntity)

        // Then
        assertEquals(originalInstant, decoded.createdAt)
        assertEquals("UPDATED_VALUE", decoded.textValue, "Domain field must apply successfully")
        assertEquals(candidateKey, decoded.id)
    }

    @Test
    fun should_fallbackToHlcAndHealCreatedAt_when_updateDeltaArrivesBeforeInsert() = runEnv {
        val candidateKey = 306L
        val originalInstant = Instant.fromEpochMilliseconds(1_700_000_000_000L)
        val hlcs = TestHlcFactory.chronologicalSequence(2)

        // Given (Device B creates entity initially, then updates it)
        val originalEntity = FeatureEntity(
            id = candidateKey,
            hlc = hlcs[0],
            createdAt = originalInstant,
            textValue = "INITIAL_VALUE"
        )
        val insertBytes = encode(new = originalEntity, old = null)
        val insertContext = originalEntity.deriveContext()

        val updatedEntity = originalEntity.copy(textValue = "OUT_OF_ORDER_UPDATE", hlc = hlcs[1])
        val updateBytes = encode(new = updatedEntity, old = originalEntity)
        val updateContext = updatedEntity.deriveContext(changedMask = 0L.withTag(TAG_TEXT_VALUE))

        // When (Step 1: Device A receives update first with no existing record)
        val interimDecoded = decode(bytes = updateBytes, context = updateContext, existing = null)

        assertEquals(updateContext.hlc.instant, interimDecoded.createdAt)
        assertEquals("OUT_OF_ORDER_UPDATE", interimDecoded.textValue)

        // When (Step 2: Device A receives the delayed INSERT and merges against interim state)
        val finalDecoded =
            decode(bytes = insertBytes, context = insertContext, existing = interimDecoded)

        assertEquals(originalInstant, finalDecoded.createdAt)
        assertEquals(candidateKey, finalDecoded.id)
        assertEquals("OUT_OF_ORDER_UPDATE", finalDecoded.textValue)
    }

    @Test
    fun should_applyContextTimestamps_during_hydration() = runEnv {
        val hlcs = TestHlcFactory.chronologicalSequence(2)
        val existingEntity = FeatureEntity(hlc = hlcs[0])

        // When (Device A: Update and Encode)
        val updatedEntity = existingEntity.copy(hlc = hlcs[1], countValue = 6)
        val bytes = encode(new = updatedEntity, old = existingEntity)

        // When (Device B: Decode)
        val remoteContext = DecodeContext(
            candidateKey = updatedEntity.id,
            hlc = updatedEntity.hlc,
            op = MutationOp.UPSERT,
            featureSchemaVersion = 1,
            changedMask = 0L
        )
        val hydrated = decode(
            bytes = bytes,
            context = remoteContext,
            existing = existingEntity
        )

        assertEquals(
            hlcs[1],
            hydrated.hlc,
            "Entity HLC must take inbound DecodeContext HLC"
        )
        assertEquals(
            hlcs[1].ts,
            hydrated.lastModified,
            "Entity lastModified must take DecodeContext timestamp"
        )
    }

    @Test
    fun should_throwSerializationException_when_decodingCorruptBytes() = runEnv {
        val corruptBytes = byteArrayOf(0x00, 0x80.toByte(), 0xFF.toByte())
        val context = DecodeContext(
            candidateKey = 1000L,
            hlc = TestHlcFactory.create(),
            op = MutationOp.UPSERT,
            featureSchemaVersion = 1,
            changedMask = 0L
        )

        assertFailsWith<SerializationException> {
            decode(bytes = corruptBytes, context = context, existing = null)
        }
    }

    // -------------------------------------------------------------------
    // SUMMARY PARITY (IN-MEMORY vs BINARY RECONSTRUCTION)
    // -------------------------------------------------------------------

    @Test
    fun should_maintainSummaryParity_on_fullInsert() = runEnv {
        val newEntity = FeatureEntity()
        val bytes = encode(new = newEntity, old = null)
        val changedTags = computeChangedTags(newEntity, null)


        // When
        val inMemorySummary = changedTags.toBitmask().toTagSummary(MutationOp.UPSERT)
        val binarySummary = reconstructSummary(bytes)

        val (inMemOp, inMemTags) = parseSummary(inMemorySummary)
        val (binOp, binTags) = parseSummary(binarySummary)

        assertEquals(MutationOp.UPSERT.name, inMemOp, "Opcode must be UPSERT")
        assertEquals(inMemOp, binOp, "In-memory opcode must match binary peeking opcode")
        assertEquals(listOf(TAG_CREATED_AT, TAG_TEXT_VALUE, TAG_COUNT_VALUE), inMemTags)
        assertEquals(inMemTags, binTags, "In-memory changed tags must equal binary peeked tags")
    }

    @Test
    fun should_maintainSummaryParity_on_partialUpdate() = runEnv {
        val oldEntity = FeatureEntity()
        val newEntity = oldEntity.copy(countValue = 99)
        val bytes = encode(new = newEntity, old = oldEntity)
        val changedTags = computeChangedTags(newEntity, oldEntity)

        // When
        val inMemorySummary = changedTags.toBitmask().toTagSummary(MutationOp.UPSERT)
        val binarySummary = reconstructSummary(bytes)

        val (inMemOp, inMemTags) = parseSummary(inMemorySummary)
        val (binOp, binTags) = parseSummary(binarySummary)

        assertEquals("UPSERT", inMemOp)
        assertEquals(inMemOp, binOp)
        assertEquals(listOf(TAG_COUNT_VALUE), inMemTags)
        assertEquals(inMemTags, binTags)
    }

    @Test
    fun should_maintainSummaryParity_on_tombstoneDelete() = runEnv {
        val oldEntity = FeatureEntity()
        val deletedEntity = oldEntity.withDeleteState(true)
        val bytes = encode(new = deletedEntity, old = oldEntity)
        val changedTags = computeChangedTags(deletedEntity, oldEntity)

        // When
        val inMemorySummary = changedTags.toBitmask().toTagSummary(MutationOp.DELETE)
        val binarySummary = reconstructSummary(bytes)

        val (inMemOp, inMemTags) = parseSummary(inMemorySummary)
        val (binOp, binTags) = parseSummary(binarySummary)

        assertEquals("DELETE", inMemOp)
        assertEquals(inMemOp, binOp, "Both summary paths must resolve tombstone DELETE")
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

        assertEquals(
            "OP:INVALID_EMPTY_BYTES",
            summary,
            "Empty ByteArray must short-circuit upfront"
        )
    }

    @Test
    fun should_returnCorruptPacket_when_varintIsTruncated() = runEnv {
        // Construct a varint with MSB 0x80 set, indicating more bytes follow, but terminate the array abruptly
        val truncatedVarintBytes = byteArrayOf(0x80.toByte())

        val summary = reconstructSummary(truncatedVarintBytes)

        assertEquals(
            "OP:CORRUPT_PACKET",
            summary,
            "Truncated MSB varint must trigger OP:CORRUPT_PACKET"
        )
    }

    @Test
    fun should_returnCorruptPacket_when_wireTypeIsInvalid() = runEnv {
        // Construct illegal wire key: Tag = 1, WireType = 6 -> (1 shl 3) or 6 = 14 (0x0E)
        val invalidWireTypeBytes = byteArrayOf(0x0E.toByte(), 0x01.toByte())

        val summary = reconstructSummary(invalidWireTypeBytes)

        assertEquals(
            "OP:CORRUPT_PACKET",
            summary,
            "Unsupported wire type (6) must trigger OP:CORRUPT_PACKET"
        )
    }

    @Test
    fun should_skipLengthDelimitedPayloads_without_corruptingBuffer() = runEnv {
        // Construct a large string payload (Wire Type 2) to force multi-byte length skipping
        val largeText = "A".repeat(2048)
        val entityWithLargePayload = FeatureEntity(
            textValue = largeText,             // Tag 4 (Wire Type 2, length 2048)
            countValue = 999                   // Tag 5 (Wire Type 0)
        )

        val bytes = encode(new = entityWithLargePayload, old = null)
        assertTrue(bytes.size > 2048, "Payload must be larger than 2KB")

        val summary = reconstructSummary(bytes)

        // Verify peeking engine correctly skipped 2048 bytes of string payload and aligned to countValue tag
        assertTrue(summary.startsWith("OP:UPSERT"))
        assertTrue(summary.contains("$TAG_TEXT_VALUE") && summary.contains("$TAG_COUNT_VALUE"))
    }

    @Test
    fun should_cleanlyResetBuffer_across_sequentialPeeks() = runEnv {
        // Given Payload A: Large payload containing Tags 4, 5
        val largeEntity = FeatureEntity(
            textValue = "Long text string to pad the buffer size", // Tag 4
            countValue = 99999 // Tag 5
        )
        val largeBytes = encode(new = largeEntity, old = null)
        assertNotNull(largeBytes)

        // Given Payload B: Small payload containing only Tag 5
        val smallEntity = largeEntity.copy(
            countValue = 3 // Tag 5
        )
        val smallBytes = encode(new = smallEntity, old = largeEntity)
        assertNotNull(smallBytes)

        val summary1 = reconstructSummary(largeBytes)
        assertEquals("OP:UPSERT [3,4,5]", summary1)
        val summary2 = reconstructSummary(smallBytes)
        assertEquals("OP:UPSERT [5]", summary2)
    }

    // -------------------------------------------------------------------
    // DIFF
    // -------------------------------------------------------------------

    @Test
    fun diff_verification() {
        assertEquals("new", "new" diff "old")
        assertNull("same" diff "same")
        assertNull(null diff "old") // Explicit unset produces null for sparse wire delta
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
