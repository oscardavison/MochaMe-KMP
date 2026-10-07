package com.mochame.sync.domain.crdt

import com.mochame.support.MochaPlatformTest
import com.mochame.sync.api.models.HLC
import com.mochame.sync.api.models.NodeId
import com.mochame.utils.fixtures.TestHlcFactory
import com.mochame.utils.fixtures.TestNodeId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.uuid.Uuid

class FieldHlcMapTest : MochaPlatformTest() {

    private fun emptyMap() = FieldHlcMap(ByteArray(0))

    private fun createHlc(
        ts: Long = 1000L,
        count: Int = 0,
        nodeId: NodeId = TestNodeId.A
    ): HLC = TestHlcFactory.create(ts = ts, count = count, nodeId = nodeId)

    @Test
    fun should_instantiateSuccessfully_when_byteArraySizeIsMultipleOfRecordSize() {
        // Given
        val validSizes = listOf(
            0,
            FieldHlcMap.RECORD_SIZE,
            FieldHlcMap.RECORD_SIZE * 2
        )

        // When & Then
        for (size in validSizes) {
            val map = FieldHlcMap(ByteArray(size))
            assertEquals(size, map.bytes.size)
        }
    }

    @Test
    fun should_throwIllegalArgumentException_when_byteArraySizeIsNotMultipleOfRecordSize() {
        // Given
        val invalidSizes = listOf(1, 10, 26, 28, 53)

        // When & Then
        for (size in invalidSizes) {
            val exception = assertFailsWith<IllegalArgumentException> {
                FieldHlcMap(ByteArray(size))
            }
            assertEquals(
                "ByteArray size ($size) must be a multiple of ${FieldHlcMap.RECORD_SIZE}",
                exception.message
            )
        }
    }

    @Test
    fun should_maintainImmutabilityOfSourceInstance_when_updatingExistingTag() {
        // Given
        val hlc1 = createHlc(ts = 1000L, count = 1, nodeId = TestNodeId.A)
        val hlc2 = createHlc(ts = 2000L, count = 2, nodeId = TestNodeId.B)

        // When
        val original = emptyMap().updateTag(tagId = 3, hlc = hlc1)
        val updated = original.updateTag(tagId = 3, hlc = hlc2)

        // Then
        assertEquals(hlc1, original.getHlc(tagId = 3))
        assertEquals(hlc2, updated.getHlc(tagId = 3))
        assertNotSame(original.bytes, updated.bytes)
        assertFalse(original.bytes.contentEquals(updated.bytes))
    }

    @Test
    fun should_maintainImmutabilityOfSourceInstance_when_appendingNewTag() {
        // Given
        val hlc1 = createHlc(ts = 1000L, count = 1, nodeId = TestNodeId.A)
        val hlc2 = createHlc(ts = 2000L, count = 2, nodeId = TestNodeId.B)

        // When
        val initial = emptyMap().updateTag(tagId = 1, hlc = hlc1)
        val expanded = initial.updateTag(tagId = 2, hlc = hlc2)

        // Then
        assertEquals(FieldHlcMap.RECORD_SIZE, initial.bytes.size)
        assertEquals(hlc1, initial.getHlc(tagId = 1))
        assertNull(initial.getHlc(tagId = 2))

        assertEquals(FieldHlcMap.RECORD_SIZE * 2, expanded.bytes.size)
        assertEquals(hlc1, expanded.getHlc(tagId = 1))
        assertEquals(hlc2, expanded.getHlc(tagId = 2))
    }

    @Test
    fun should_allowValidTagBoundaries_when_accessingOrUpdatingMap() {
        // Given
        val sampleHlc = createHlc()

        // When
        val minTagMap = emptyMap().updateTag(tagId = 0, hlc = sampleHlc)
        val maxTagMap = emptyMap().updateTag(tagId = 127, hlc = sampleHlc)

        // Then
        assertEquals(sampleHlc, minTagMap.getHlc(tagId = 0))
        assertEquals(sampleHlc, maxTagMap.getHlc(tagId = 127))
    }

    @Test
    fun should_throwIllegalArgumentException_when_tagIdIsOutOfBoundsForGetHlc() {
        // Given
        val invalidTags = listOf(-1, 128, -100, 255)

        // When & Then
        for (invalidTag in invalidTags) {
            assertFailsWith<IllegalArgumentException> {
                emptyMap().getHlc(tagId = invalidTag)
            }
        }
    }

    @Test
    fun should_throwIllegalArgumentException_when_tagIdIsOutOfBoundsForUpdateTag() {
        // Given
        val sampleHlc = createHlc()
        val invalidTags = listOf(-1, 128, -100, 255)

        // When & Then
        for (invalidTag in invalidTags) {
            assertFailsWith<IllegalArgumentException> {
                emptyMap().updateTag(tagId = invalidTag, hlc = sampleHlc)
            }
        }
    }

    @Test
    fun should_growByteArrayByRecordSize_when_appendingNewTags() {
        // Given
        val initialMap = emptyMap()
        val hlc1 = createHlc(ts = 1000L, count = 1, nodeId = TestNodeId.A)
        val hlc2 = createHlc(ts = 2000L, count = 2, nodeId = TestNodeId.B)
        val hlc3 = createHlc(ts = 3000L, count = 3, nodeId = TestNodeId.A)

        // When
        val map1 = initialMap.updateTag(tagId = 1, hlc = hlc1)
        val map2 = map1.updateTag(tagId = 2, hlc = hlc2)
        val map3 = map2.updateTag(tagId = 3, hlc = hlc3)

        // Then
        assertEquals(FieldHlcMap.RECORD_SIZE, map1.bytes.size)
        assertEquals(FieldHlcMap.RECORD_SIZE * 2, map2.bytes.size)
        assertEquals(FieldHlcMap.RECORD_SIZE * 3, map3.bytes.size)
        assertEquals(hlc1, map3.getHlc(tagId = 1))
        assertEquals(hlc2, map3.getHlc(tagId = 2))
        assertEquals(hlc3, map3.getHlc(tagId = 3))
    }

    @Test
    fun should_maintainExactByteArraySize_when_updatingExistingTagsInPlace() {
        // Given
        val hlc1 = createHlc(ts = 1000L, count = 1, nodeId = TestNodeId.A)
        val hlc2 = createHlc(ts = 2000L, count = 2, nodeId = TestNodeId.B)
        val twoRecordMap = emptyMap()
            .updateTag(tagId = 10, hlc = hlc1)
            .updateTag(tagId = 20, hlc = hlc2)

        // When
        val updatedHlc1 = createHlc(ts = 5000L, count = 10, nodeId = TestNodeId.B)
        val mapAfterUpdatingTag10 = twoRecordMap.updateTag(tagId = 10, hlc = updatedHlc1)

        // Then
        assertEquals(FieldHlcMap.RECORD_SIZE * 2, mapAfterUpdatingTag10.bytes.size)
        assertEquals(updatedHlc1, mapAfterUpdatingTag10.getHlc(tagId = 10))
        assertEquals(hlc2, mapAfterUpdatingTag10.getHlc(tagId = 20))

        // When
        val updatedHlc2 = createHlc(ts = 6000L, count = 20, nodeId = TestNodeId.A)
        val mapAfterUpdatingTag20 = mapAfterUpdatingTag10.updateTag(tagId = 20, hlc = updatedHlc2)

        // Then
        assertEquals(FieldHlcMap.RECORD_SIZE * 2, mapAfterUpdatingTag20.bytes.size)
        assertEquals(updatedHlc1, mapAfterUpdatingTag20.getHlc(tagId = 10))
        assertEquals(updatedHlc2, mapAfterUpdatingTag20.getHlc(tagId = 20))
    }

    @Test
    fun should_preserveAllHlcFieldsWithoutLossOfPrecision_when_roundTrippingMaxBoundaries() {
        // Given
        val maxHlc = HLC(ts = Long.MAX_VALUE, count = 65535, nodeId = NodeId(Uuid.random()))
        val tagId = 42

        // When
        val map = emptyMap().updateTag(tagId = tagId, hlc = maxHlc)
        val retrievedHlc = map.getHlc(tagId = tagId)

        // Then
        assertEquals(maxHlc, retrievedHlc)
        assertEquals(Long.MAX_VALUE, retrievedHlc?.ts)
        assertEquals(65535, retrievedHlc?.count)
        assertEquals(maxHlc.nodeId, retrievedHlc?.nodeId)
    }

    @Test
    fun should_preserveAllHlcFieldsWithoutLossOfPrecision_when_roundTrippingMinBoundaries() {
        // Given
        val minHlc = HLC(ts = 0L, count = 0, nodeId = NodeId(Uuid.fromLongs(0L, 0L)))
        val tagId = 0

        // When
        val map = emptyMap().updateTag(tagId = tagId, hlc = minHlc)
        val retrievedHlc = map.getHlc(tagId = tagId)

        // Then
        assertEquals(minHlc, retrievedHlc)
        assertEquals(0L, retrievedHlc?.ts)
        assertEquals(0, retrievedHlc?.count)
        assertEquals(minHlc.nodeId, retrievedHlc?.nodeId)
    }

    @Test
    fun should_preserveMultipleDistinctRecords_when_roundTrippingContiguousTags() {
        // Given
        val hlc0 = HLC(ts = 100_000L, count = 0, nodeId = NodeId(Uuid.fromLongs(1L, 11L)))
        val hlc1 = HLC(ts = Long.MAX_VALUE - 1, count = 65534, nodeId = NodeId(Uuid.fromLongs(2L, 22L)))
        val hlc2 = HLC(ts = 500_000L, count = 32768, nodeId = NodeId(Uuid.fromLongs(3L, 33L)))

        // When
        val map = emptyMap()
            .updateTag(tagId = 0, hlc = hlc0)
            .updateTag(tagId = 1, hlc = hlc1)
            .updateTag(tagId = 127, hlc = hlc2)

        // Then
        assertEquals(hlc0, map.getHlc(tagId = 0))
        assertEquals(hlc1, map.getHlc(tagId = 1))
        assertEquals(hlc2, map.getHlc(tagId = 127))
        assertNull(map.getHlc(tagId = 5))
    }

    @Test
    fun should_produceIdenticalIndex_when_batchUpdateTagsMatchesSequentialUpdateTag() {
        // Given
        val initialHlc = createHlc(ts = 500L, count = 0)
        val newHlc = createHlc(ts = 2000L, count = 1)
        val tagsToUpdate = listOf(1, 4, 7, 10)

        val seedMap = emptyMap()
            .updateTag(tagId = 1, hlc = initialHlc)
            .updateTag(tagId = 4, hlc = initialHlc)

        // When: sequential updates
        var sequentialMap = seedMap
        for (tag in tagsToUpdate) {
            sequentialMap = sequentialMap.updateTag(tag, newHlc)
        }

        // When: batch update
        val batchMap = seedMap.updateTags(tagsToUpdate, newHlc)

        // Then: both must have the same size and identical tag lookups
        assertEquals(sequentialMap.bytes.size, batchMap.bytes.size)
        for (tag in tagsToUpdate) {
            assertEquals(newHlc, batchMap.getHlc(tag))
            assertEquals(sequentialMap.getHlc(tag), batchMap.getHlc(tag))
        }
        assertTrue(batchMap.bytes.contentEquals(sequentialMap.bytes))
    }

    @Test
    fun should_returnSameInstance_when_updatingEmptyTagsList() {
        // Given
        val map = emptyMap().updateTag(tagId = 1, hlc = createHlc(ts = 100L))
        val hlc = createHlc(ts = 500L)

        // When
        val result = map.updateTags(emptyList(), hlc)

        // Then
        assertSame(map.bytes, result.bytes)
    }

    @Test
    fun should_batchUpdateMixOfExistingAndNewTags_when_callingUpdateTags() {
        // Given
        val hlc1 = createHlc(ts = 100L)
        val hlc2 = createHlc(ts = 200L)
        val batchHlc = createHlc(ts = 800L)

        val initial = emptyMap()
            .updateTag(tagId = 1, hlc = hlc1)
            .updateTag(tagId = 2, hlc = hlc2)

        // When: Tag 2 is existing, Tag 3 and 5 are new
        val updated = initial.updateTags(listOf(2, 3, 5), batchHlc)

        // Then: Size grows from 2 records (54B) to 4 records (108B)
        assertEquals(FieldHlcMap.RECORD_SIZE * 4, updated.bytes.size)
        assertEquals(hlc1, updated.getHlc(tagId = 1))
        assertEquals(batchHlc, updated.getHlc(tagId = 2))
        assertEquals(batchHlc, updated.getHlc(tagId = 3))
        assertEquals(batchHlc, updated.getHlc(tagId = 5))
    }

    @Test
    fun should_correctlyDetectNewerTag_when_evaluatingHasTagNewerThan() {
        // Given
        val hlcOld = createHlc(ts = 1000L)
        val hlcHorizon = createHlc(ts = 2000L)
        val hlcNew = createHlc(ts = 3000L)

        val map = emptyMap()
            .updateTag(tagId = 1, hlc = hlcOld)
            .updateTag(tagId = 2, hlc = hlcNew)

        // When & Then
        // Tag 2 is newer than hlcHorizon and not excluded
        assertTrue(map.hasTagNewerThan(horizon = hlcHorizon, excludeTag = -1))

        // Tag 2 is newer than hlcHorizon, but excluded
        assertFalse(map.hasTagNewerThan(horizon = hlcHorizon, excludeTag = 2))

        // Horizon is higher than all tags
        val hlcFuture = createHlc(ts = 4000L)
        assertFalse(map.hasTagNewerThan(horizon = hlcFuture, excludeTag = -1))

        // Empty map has no newer tags
        assertFalse(emptyMap().hasTagNewerThan(horizon = hlcOld, excludeTag = -1))
    }
}
