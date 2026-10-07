package com.mochame.sync.utils

import com.mochame.sync.api.metadata.MutationOp
import com.mochame.sync.api.metadata.toTagList
import com.mochame.sync.api.metadata.toTagSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BitmaskUtilsTest {

    @Test
    fun should_accumulateAndProbeTags_when_taggingBitmask() {
        // Given
        var mask = 0L

        // Then
        assertFalse(mask.hasTag(0))
        assertFalse(mask.hasTag(63))

        // When
        mask = mask.withTag(0)

        // Then
        assertTrue(mask.hasTag(0))
        assertEquals(1L, mask)

        // When
        mask = mask.withTag(3)

        // Then
        assertTrue(mask.hasTag(0))
        assertTrue(mask.hasTag(3))
        assertFalse(mask.hasTag(4))
        assertEquals(0b00001001L, mask)

        // When
        mask = mask.withTag(63)

        // Then
        assertTrue(mask.hasTag(63))
        assertTrue(mask.hasTag(3))
        assertTrue(mask.hasTag(0))
    }

    @Test
    fun should_handleNegativeAndOutOfBoundsTags_when_taggingBitmask() {
        // Given & When
        val zeroMask1 = bitmaskOf(-1, -5, -64)
        val zeroMask2 = listOf(-1, -5, -64).toBitmask()

        // Then
        assertEquals(0L, zeroMask1)
        assertEquals(0L, zeroMask2)

        // When
        val mixedMask = bitmaskOf(-1, 3, -10, 4)

        // Then
        assertEquals(bitmaskOf(3, 4), mixedMask)
        assertFalse(mixedMask.hasTag(0))
        assertTrue(mixedMask.hasTag(3))
        assertTrue(mixedMask.hasTag(4))

        // When
        val rawNegativeShiftMask = 0L.withTag(-1)

        // Then
        assertTrue(rawNegativeShiftMask.hasTag(63))
        assertTrue(rawNegativeShiftMask.hasTag(-1))
        assertFalse(rawNegativeShiftMask.hasTag(0))
    }

    @Test
    fun should_buildBitmaskFromVarargAndList_when_providedTags() {
        // Given & When
        val emptyVararg = bitmaskOf()
        val emptyListMask = emptyList<Int>().toBitmask()

        // Then
        assertEquals(0L, emptyVararg)
        assertEquals(0L, emptyListMask)

        // When
        val maskVararg = bitmaskOf(3, 52, 4)
        val maskList = listOf(52, 4, 3, 3).toBitmask()

        // Then
        assertEquals(maskVararg, maskList)
        assertTrue(maskList.hasTag(3))
        assertTrue(maskList.hasTag(4))
        assertTrue(maskList.hasTag(52))
        assertFalse(maskList.hasTag(5))

        // When
        val maskWithOutOfBounds = listOf(-1, 0, 63, 64, 100).toBitmask()

        // Then
        assertTrue(maskWithOutOfBounds.hasTag(0))
        assertTrue(maskWithOutOfBounds.hasTag(63))
        assertFalse(maskWithOutOfBounds.hasTag(1))
    }

    @Test
    fun should_decompressAndDiagnoseBitmask_when_convertingToSummary() {
        // Given & When & Then
        assertEquals(emptyList(), 0L.toTagList())
        assertEquals("OP:UPSERT []", 0L.toTagSummary(MutationOp.UPSERT))
        assertEquals("OP:DELETE []", 0L.toTagSummary(MutationOp.DELETE))

        // When
        val mask = bitmaskOf(0, 3, 4, 63)

        // Then
        assertEquals(listOf(0, 3, 4, 63), mask.toTagList())
        assertEquals("OP:UPSERT [0,3,4,63]", mask.toTagSummary(MutationOp.UPSERT))
        assertEquals("OP:DELETE [0,3,4,63]", mask.toTagSummary(MutationOp.DELETE))
    }
}