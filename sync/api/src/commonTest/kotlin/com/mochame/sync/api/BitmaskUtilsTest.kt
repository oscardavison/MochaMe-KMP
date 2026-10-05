package com.mochame.sync.api

import com.mochame.sync.api.internal.bitmaskOf
import com.mochame.sync.api.internal.hasTag
import com.mochame.sync.api.internal.toBitmask
import com.mochame.sync.api.internal.withTag
import com.mochame.sync.api.metadata.MutationOp
import com.mochame.sync.api.metadata.toTagList
import com.mochame.sync.api.metadata.toTagSummary
import kotlin.collections.emptyList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BitmaskUtilsTest {

    @Test
    fun testTagProbingAndAccumulation() {
        var mask = 0L
        assertFalse(mask.hasTag(0))
        assertFalse(mask.hasTag(63))

        mask = mask.withTag(0)
        assertTrue(mask.hasTag(0))
        assertEquals(1L, mask)

        mask = mask.withTag(3)
        assertTrue(mask.hasTag(0))
        assertTrue(mask.hasTag(3))
        assertFalse(mask.hasTag(4))
        assertEquals(0b00001001, mask)

        mask = mask.withTag(63)
        assertTrue(mask.hasTag(63))
        assertTrue(mask.hasTag(3))
        assertTrue(mask.hasTag(0))
    }

    @Test
    fun testNegativeAndOutOfBoundsTaggingBehaviour() {
        assertEquals(0L, bitmaskOf(-1, -5, -64))
        assertEquals(0L, listOf(-1, -5, -64).toBitmask())

        val mixedMask = bitmaskOf(-1, 3, -10, 4)
        assertEquals(bitmaskOf(3, 4), mixedMask)
        assertFalse(mixedMask.hasTag(0))
        assertTrue(mixedMask.hasTag(3))
        assertTrue(mixedMask.hasTag(4))

        // -1 & 63 = 63, so withTag(-1) targets Bit 63
        val rawNegativeShiftMask = 0L.withTag(-1)
        assertTrue(rawNegativeShiftMask.hasTag(63))
        assertTrue(rawNegativeShiftMask.hasTag(-1))
        assertFalse(rawNegativeShiftMask.hasTag(0))
    }

    @Test
    fun testVarargAndListFactories() {
        assertEquals(0L, bitmaskOf())
        assertEquals(0L, emptyList<Int>().toBitmask())

        val maskVararg = bitmaskOf(3, 52, 4)
        val maskList = listOf(52, 4, 3, 3).toBitmask()

        assertEquals(maskVararg, maskList)
        assertTrue(maskList.hasTag(3))
        assertTrue(maskList.hasTag(4))
        assertTrue(maskList.hasTag(52))
        assertFalse(maskList.hasTag(5))

        val maskWithOutOfBounds = listOf(-1, 0, 63, 64, 100).toBitmask()
        assertTrue(maskWithOutOfBounds.hasTag(0))
        assertTrue(maskWithOutOfBounds.hasTag(63))
        assertFalse(maskWithOutOfBounds.hasTag(1))
    }

    @Test
    fun testDecompressionAndDiagnostics() {
        assertEquals(emptyList(), 0L.toTagList())
        assertEquals("OP:UPSERT []", 0L.toTagSummary(MutationOp.UPSERT))
        assertEquals("OP:DELETE []", 0L.toTagSummary(MutationOp.DELETE))

        val mask = bitmaskOf(0, 3, 4, 63)
        assertEquals(listOf(0, 3, 4, 63), mask.toTagList())
        assertEquals("OP:UPSERT [0,3,4,63]", mask.toTagSummary(MutationOp.UPSERT))
        assertEquals("OP:DELETE [0,3,4,63]", mask.toTagSummary(MutationOp.DELETE))
    }

}