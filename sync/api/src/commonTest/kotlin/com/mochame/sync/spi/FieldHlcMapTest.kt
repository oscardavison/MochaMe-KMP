package com.mochame.sync.spi.infrastructure.serialization

import com.mochame.support.MochaPlatformTest
import com.mochame.sync.api.models.HLC
import com.mochame.sync.spi.*
import com.mochame.sync.spi.node.NodeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.uuid.Uuid

class FieldHlcMapTest : MochaPlatformTest() {

    // ===================================================================
    // INIT REQUIREMENTS
    // ===================================================================

    @Test
    fun should_instantiateSuccessfully_when_byteArraySizeIsMultipleOfRecordSize() {
        val validSizes = listOf(
            0,
            FieldHlcMap.RECORD_SIZE,    // 27 bytes / 1 record
            FieldHlcMap.RECORD_SIZE * 2 // 54 bytes / 2 records
        )

        for (size in validSizes) {
            val map = FieldHlcMap(ByteArray(size))
            assertEquals(
                expected = size,
                actual = map.bytes.size,
                message = "FieldHlcMap must initialize successfully for valid size: $size"
            )
        }
    }

    @Test
    fun should_throwIllegalArgumentException_when_byteArraySizeIsNotMultipleOfRecordSize() {
        val invalidSizes = listOf(
            1,  // Truncated single byte
            10, // Partial record
            26, // 1 byte short of 1 record
            28, // 1 byte over 1 record
            53  // 1 byte short of 2 records
        )

        for (size in invalidSizes) {
            val exception = assertFailsWith<IllegalArgumentException>(
                message = "Expected instantiation to fail for invalid size $size"
            ) {
                FieldHlcMap(ByteArray(size))
            }

            assertEquals(
                expected = "ByteArray size ($size) must be a multiple of ${FieldHlcMap.RECORD_SIZE}",
                actual = exception.message
            )
        }
    }

    // ===================================================================
    // ARRAY IMMUTABILITY
    // ===================================================================

    @Test
    fun should_maintainImmutabilityOfSourceInstance_when_updatingExistingTag() {
        // Given
        val hlc1 = createHlc(ts = 1000L, count = 1, nodeId = TestNodeId.A)
        val hlc2 = createHlc(ts = 2000L, count = 2, nodeId = TestNodeId.B)

        // When
        val original = FieldHlcMap.EMPTY.updateTag(tagId = 3, hlc = hlc1)
        val updated = original.updateTag(tagId = 3, hlc = hlc2)

        // Then 1: Verify original instance was not mutated in-place
        assertEquals(hlc1, original.getHlc(tagId = 3), "Original map must still return hlc1")
        assertEquals(hlc2, updated.getHlc(tagId = 3), "Updated map must return hlc2")

        // Then 2: Verify separate memory references and distinct byte contents
        assertNotSame(
            original.bytes,
            updated.bytes,
            "Backing arrays must be distinct object references"
        )
        assertFalse(
            actual = original.bytes.contentEquals(updated.bytes),
            message = "Backing array contents must differ after tag update"
        )
    }

    @Test
    fun should_maintainImmutabilityOfSourceInstance_when_appendingNewTag() {
        // Given
        val hlc1 = createHlc(ts = 1000L, count = 1, nodeId = TestNodeId.A)
        val hlc2 = createHlc(ts = 2000L, count = 2, nodeId = TestNodeId.B)

        // When
        val initial = FieldHlcMap.EMPTY.updateTag(tagId = 1, hlc = hlc1)
        val expanded = initial.updateTag(tagId = 2, hlc = hlc2)

        // Then: Initial map retains size 27 and only contains tag 1
        assertEquals(FieldHlcMap.RECORD_SIZE, initial.bytes.size)
        assertEquals(hlc1, initial.getHlc(tagId = 1))
        assertEquals(
            null,
            initial.getHlc(tagId = 2),
            "Initial map must not contain newly appended tag 2"
        )

        // Then: Expanded map has size 54 and contains both tags
        assertEquals(FieldHlcMap.RECORD_SIZE * 2, expanded.bytes.size)
        assertEquals(hlc1, expanded.getHlc(tagId = 1))
        assertEquals(hlc2, expanded.getHlc(tagId = 2))
    }

    // ===================================================================
    // TAG BOUNDARY (0..127)
    // ===================================================================

    @Test
    fun should_allowValidTagBoundaries_when_accessingOrUpdatingMap() {
        val sampleHlc = createHlc()

        // 1. Minimum valid tag: tagId = 0
        val minTagMap = FieldHlcMap.EMPTY.updateTag(tagId = 0, hlc = sampleHlc)
        assertEquals(sampleHlc, minTagMap.getHlc(tagId = 0))

        // 2. Maximum valid tag: tagId = 127 (0x7F - highest positive signed Byte)
        val maxTagMap = FieldHlcMap.EMPTY.updateTag(tagId = 127, hlc = sampleHlc)
        assertEquals(sampleHlc, maxTagMap.getHlc(tagId = 127))
    }

    @Test
    fun should_throwIllegalArgumentException_when_tagIdIsOutOfBoundsForGetHlc() {
        val invalidTags = listOf(-1, 128, -100, 255)

        for (invalidTag in invalidTags) {
            assertFailsWith<IllegalArgumentException>(
                message = "Expected getHlc to throw IllegalArgumentException for tagId: $invalidTag"
            ) {
                FieldHlcMap.EMPTY.getHlc(tagId = invalidTag)
            }
        }
    }

    @Test
    fun should_throwIllegalArgumentException_when_tagIdIsOutOfBoundsForUpdateTag() {
        val sampleHlc = createHlc()
        val invalidTags = listOf(-1, 128, -100, 255)

        for (invalidTag in invalidTags) {
            assertFailsWith<IllegalArgumentException>(
                message = "Expected updateTag to throw IllegalArgumentException for tagId: $invalidTag"
            ) {
                FieldHlcMap.EMPTY.updateTag(tagId = invalidTag, hlc = sampleHlc)
            }
        }
    }

    // ===================================================================
    // ALLOCATION: APPEND VS. IN-PLACE UPDATE
    // ===================================================================

    @Test
    fun should_growByteArrayByRecordSize_when_appendingNewTags() {
        // Given: Empty initial map (0 bytes)
        val initialMap = FieldHlcMap.EMPTY
        assertEquals(0, initialMap.bytes.size)

        val hlc1 = createHlc(ts = 1000L, count = 1, nodeId = TestNodeId.A)
        val hlc2 = createHlc(ts = 2000L, count = 2, nodeId = TestNodeId.B)
        val hlc3 = createHlc(ts = 3000L, count = 3, nodeId = TestNodeId.A)

        // When & Then 1: Append Tag 1 (0 -> 27 bytes)
        val mapWithOneTag = initialMap.updateTag(tagId = 1, hlc = hlc1)
        assertEquals(FieldHlcMap.RECORD_SIZE, mapWithOneTag.bytes.size)
        assertEquals(hlc1, mapWithOneTag.getHlc(tagId = 1))

        // When & Then 2: Append Tag 2 (27 -> 54 bytes)
        val mapWithTwoTags = mapWithOneTag.updateTag(tagId = 2, hlc = hlc2)
        assertEquals(FieldHlcMap.RECORD_SIZE * 2, mapWithTwoTags.bytes.size)
        assertEquals(hlc1, mapWithTwoTags.getHlc(tagId = 1))
        assertEquals(hlc2, mapWithTwoTags.getHlc(tagId = 2))

        // When & Then 3: Append Tag 3 (54 -> 81 bytes)
        val mapWithThreeTags = mapWithTwoTags.updateTag(tagId = 3, hlc = hlc3)
        assertEquals(FieldHlcMap.RECORD_SIZE * 3, mapWithThreeTags.bytes.size)
        assertEquals(hlc1, mapWithThreeTags.getHlc(tagId = 1))
        assertEquals(hlc2, mapWithThreeTags.getHlc(tagId = 2))
        assertEquals(hlc3, mapWithThreeTags.getHlc(tagId = 3))
    }

    @Test
    fun should_maintainExactByteArraySize_when_updatingExistingTagsInPlace() {
        // Given: A map initialized with two records (54 bytes)
        val hlc1 = createHlc(ts = 1000L, count = 1, nodeId = TestNodeId.A)
        val hlc2 = createHlc(ts = 2000L, count = 2, nodeId = TestNodeId.B)

        val twoRecordMap = FieldHlcMap.EMPTY
            .updateTag(tagId = 10, hlc = hlc1)
            .updateTag(tagId = 20, hlc = hlc2)

        assertEquals(FieldHlcMap.RECORD_SIZE * 2, twoRecordMap.bytes.size)

        // When 1: Update existing Tag 10 with a new HLC
        val updatedHlc1 = createHlc(ts = 5000L, count = 10, nodeId = TestNodeId.B)
        val mapAfterUpdatingTag10 = twoRecordMap.updateTag(tagId = 10, hlc = updatedHlc1)

        // Then
        assertEquals(FieldHlcMap.RECORD_SIZE * 2, mapAfterUpdatingTag10.bytes.size)
        assertEquals(updatedHlc1, mapAfterUpdatingTag10.getHlc(tagId = 10))
        assertEquals(
            hlc2,
            mapAfterUpdatingTag10.getHlc(tagId = 20),
            "Adjacent tag 20 must remain untouched"
        )

        // When 2: Update existing Tag 20 with a new HLC
        val updatedHlc2 = createHlc(ts = 6000L, count = 20, nodeId = TestNodeId.A)
        val mapAfterUpdatingTag20 = mapAfterUpdatingTag10.updateTag(tagId = 20, hlc = updatedHlc2)

        // Then 2
        assertEquals(FieldHlcMap.RECORD_SIZE * 2, mapAfterUpdatingTag20.bytes.size)
        assertEquals(updatedHlc1, mapAfterUpdatingTag20.getHlc(tagId = 10))
        assertEquals(updatedHlc2, mapAfterUpdatingTag20.getHlc(tagId = 20))
    }

    // ===================================================================
    // STRUCT SERIALIZATION ROUND-TRIP
    // ===================================================================

    @Test
    fun should_preserveAllHlcFieldsWithoutLossOfPrecision_when_roundTrippingMaxBoundaries() {
        // Given: Maximum boundary values for ts (Long.MAX_VALUE), count (65,535 / UShort.MAX), and random UUID
        val maxHlc = HLC(ts = Long.MAX_VALUE, count = 65535, nodeId = NodeId(Uuid.random()))
        val tagId = 42

        // When
        val map = FieldHlcMap.EMPTY.updateTag(tagId = tagId, hlc = maxHlc)
        val retrievedHlc = map.getHlc(tagId = tagId)

        // Then: Exact equality verification across all individual fields
        assertEquals(maxHlc, retrievedHlc)
        assertEquals(Long.MAX_VALUE, retrievedHlc?.ts)
        assertEquals(65535, retrievedHlc?.count)
        assertEquals(maxHlc.nodeId, retrievedHlc?.nodeId)
    }

    @Test
    fun should_preserveAllHlcFieldsWithoutLossOfPrecision_when_roundTrippingMinBoundaries() {
        // Given: Minimum boundary values (ts = 0L, count = 0, Nil UUID)
        val minHlc = HLC(ts = 0L, count = 0, nodeId = NodeId(Uuid.fromLongs(0L, 0L)))
        val tagId = 0

        // When
        val map = FieldHlcMap.EMPTY.updateTag(tagId = tagId, hlc = minHlc)
        val retrievedHlc = map.getHlc(tagId = tagId)

        // Then
        assertEquals(minHlc, retrievedHlc)
        assertEquals(0L, retrievedHlc?.ts)
        assertEquals(0, retrievedHlc?.count)
        assertEquals(minHlc.nodeId, retrievedHlc?.nodeId)
    }

    @Test
    fun should_preserveMultipleDistinctRecords_when_roundTrippingContiguousTags() {
        // Given: Three distinct records with unique HLC properties
        val hlc0 = HLC(ts = 100_000L, count = 0, nodeId = NodeId(Uuid.fromLongs(1L, 11L)))
        val hlc1 =
            HLC(ts = Long.MAX_VALUE - 1, count = 65534, nodeId = NodeId(Uuid.fromLongs(2L, 22L)))
        val hlc2 = HLC(ts = 500_000L, count = 32768, nodeId = NodeId(Uuid.fromLongs(3L, 33L)))

        // When
        val map = FieldHlcMap.EMPTY
            .updateTag(tagId = 0, hlc = hlc0)
            .updateTag(tagId = 1, hlc = hlc1)
            .updateTag(tagId = 127, hlc = hlc2)

        // Then
        assertEquals(hlc0, map.getHlc(tagId = 0))
        assertEquals(hlc1, map.getHlc(tagId = 1))
        assertEquals(hlc2, map.getHlc(tagId = 127))
        assertEquals(null, map.getHlc(tagId = 5), "Unregistered tag must return null")
    }

}
