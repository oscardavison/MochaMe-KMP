package com.mochame.sync.api

import com.mochame.support.MochaPlatformTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ByteArrayExtensionsTest : MochaPlatformTest() {

    // ===================================================================
    // 64-BIT LONG / ULONG
    // ===================================================================

    @Test
    fun should_writeBytesInExplicitBigEndianOrder_when_writingLong() {
        // Given: Asymmetric 64-bit value
        val bytes = ByteArray(8)
        val value = 0x0102030405060708L

        // When
        bytes.writeLongAt(offset = 0, value = value)

        // Then: MSB first
        bytes.assertBytesAt(
            offset = 0,
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08
        )
    }

    @Test
    fun should_roundTripLongBoundariesWithoutLoss_when_writingAndReading() {
        val boundaryValues = listOf(
            0L,
            -1L,
            Long.MAX_VALUE,                     // 0x7FFFFFFFFFFFFFFF
            Long.MIN_VALUE,                     // 0x8000000000000000
            0x8080808080808080UL.toLong(),      // Repetitive 0x80 byte pattern
            0x0123456789ABCDEFL
        )

        val bytes = ByteArray(8)

        for (value in boundaryValues) {
            bytes.writeLongAt(offset = 0, value = value)
            val actual = bytes.readLongAt(offset = 0)

            assertEquals(
                expected = value,
                actual = actual,
                message = "Failed to round-trip Long: $value (0x${value.toString(16)})"
            )
        }
    }

    @Test
    fun should_differentiateSignedAndUnsignedLongReads_when_topBitIsSet() {
        // Given: 0xFFFFFFFFFFFFFFFF (Signed = -1L, Unsigned = ULong.MAX_VALUE)
        val bytes = ByteArray(8)
        bytes.writeLongAt(offset = 0, value = -1L)

        // Then 1: Memory inspection
        bytes.assertBytesAt(offset = 0, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF)

        // Then 2: Signed vs Unsigned contrast
        val signedActual: Long = bytes.readLongAt(offset = 0)
        val unsignedActual: ULong = bytes.readULong(offset = 0)

        assertEquals(-1L, signedActual, "readLongAt must evaluate 0xFF..FF as signed -1L")
        assertEquals(ULong.MAX_VALUE, unsignedActual, "readULong must evaluate 0xFF..FF as ULong.MAX_VALUE")
    }

    @Test
    fun should_preserveInterleavedHighAndLowBits_when_roundTrippingLongAtOffset() {
        // Given: Interleaved negative/positive byte sequence at unaligned offset
        val uLongValue = 0x8001FE028003FF04uL
        val offset = 3
        val bytes = ByteArray(16)

        // When
        bytes.writeLongAt(offset = offset, value = uLongValue.toLong())
        val actualLong = bytes.readLongAt(offset = offset)
        val actualULong = bytes.readULong(offset = offset)

        // Then
        bytes.assertBytesAt(
            offset = offset,
            0x80, 0x01, 0xFE, 0x02, 0x80, 0x03, 0xFF, 0x04
        )
        assertEquals(uLongValue.toLong(), actualLong)
        assertEquals(uLongValue, actualULong)
    }

    // ===================================================================
    // 32-BIT INT / UINT
    // ===================================================================

    @Test
    fun should_writeBytesInExplicitBigEndianOrder_when_writingInt() {
        // Given: Asymmetric 32-bit value
        val bytes = ByteArray(4)
        val value = 0x12345678

        // When
        bytes.writeIntAt(offset = 0, value = value)

        // Then: MSB first
        bytes.assertBytesAt(offset = 0, 0x12, 0x34, 0x56, 0x78)
    }

    @Test
    fun should_roundTripIntBoundariesWithoutLoss_when_writingAndReading() {
        val boundaryValues = listOf(
            0,
            -1,
            Int.MAX_VALUE,              // 0x7FFFFFFF
            Int.MIN_VALUE,              // 0x80000000
            0x80808080.toInt(),         // Repetitive 0x80 byte pattern
            0x12345678
        )

        val bytes = ByteArray(4)

        for (value in boundaryValues) {
            bytes.writeIntAt(offset = 0, value = value)
            val actual = bytes.readIntAt(offset = 0)

            assertEquals(
                expected = value,
                actual = actual,
                message = "Failed to round-trip Int: $value (0x${value.toString(16)})"
            )
        }
    }

    @Test
    fun should_differentiateSignedAndUnsignedIntReads_when_topBitIsSet() {
        // Given: 0xFFFFFFFF (Signed = -1, Unsigned = UInt.MAX_VALUE)
        val bytes = ByteArray(4)
        bytes.writeIntAt(offset = 0, value = -1)

        // Then 1: Memory inspection
        bytes.assertBytesAt(offset = 0, 0xFF, 0xFF, 0xFF, 0xFF)

        // Then 2: Signed vs Unsigned contrast
        val signedActual: Int = bytes.readIntAt(offset = 0)
        val unsignedActual: UInt = bytes.readUIntAt(offset = 0)

        assertEquals(-1, signedActual, "readIntAt must evaluate 0xFFFFFFFF as signed -1")
        assertEquals(UInt.MAX_VALUE, unsignedActual, "readUIntAt must evaluate 0xFFFFFFFF as UInt.MAX_VALUE")
    }

    @Test
    fun should_preserveInterleavedHighAndLowBits_when_roundTrippingIntAtOffset() {
        // Given: Interleaved negative/positive byte sequence at unaligned offset
        val uIntValue = 0x8001FE02u
        val offset = 2
        val bytes = ByteArray(8)

        // When
        bytes.writeIntAt(offset = offset, value = uIntValue.toInt())
        val actualInt = bytes.readIntAt(offset = offset)
        val actualUInt = bytes.readUIntAt(offset = offset)

        // Then
        bytes.assertBytesAt(offset = offset, 0x80, 0x01, 0xFE, 0x02)
        assertEquals(uIntValue.toInt(), actualInt)
        assertEquals(uIntValue, actualUInt)
    }

    // ===================================================================
    // 16-BIT SHORT / USHORT
    // ===================================================================

    @Test
    fun should_writeBytesInExplicitBigEndianOrder_when_writingShort() {
        // Given: Asymmetric 16-bit value
        val bytes = ByteArray(2)
        val value = 0x1234

        // When
        bytes.writeIntAsShortAt(offset = 0, value = value)

        // Then: MSB first
        bytes.assertBytesAt(offset = 0, 0x12, 0x34)
    }

    @Test
    fun should_roundTripShortBoundariesWithoutLoss_when_writingAndReading() {
        val boundaryValues = listOf(
            0,      // Min Unsigned Short (0x0000)
            1,
            32767,  // Max Signed Short (0x7FFF)
            32768,  // Signed boundary (0x8000)
            65000,  // HLC clock counter scenario (0xFDE8)
            65535   // Max Unsigned Short (0xFFFF)
        )

        val bytes = ByteArray(2)

        for (value in boundaryValues) {
            bytes.writeIntAsShortAt(offset = 0, value = value)
            val actual = bytes.readUShortAt(offset = 0)

            assertEquals(
                expected = value,
                actual = actual,
                message = "Failed to preserve unsigned 16-bit value: $value (0x${value.toString(16)})"
            )
        }
    }

    @Test
    fun should_differentiateSignedAndUnsignedShortReads_when_topBitIsSet() {
        // Given: 0xFFFF (Signed = -1, Unsigned = 65,535)
        val bytes = ByteArray(2)
        bytes.writeIntAsShortAt(offset = 0, value = 65535)

        // Then 1: Memory inspection
        bytes.assertBytesAt(offset = 0, 0xFF, 0xFF)

        // Then 2: Signed vs Unsigned contrast
        val signedActual: Short = bytes.readShortAt(offset = 0)
        val unsignedActual: Int = bytes.readUShortAt(offset = 0)

        assertEquals((-1).toShort(), signedActual, "readShortAt must evaluate 0xFFFF as signed -1")
        assertEquals(65535, unsignedActual, "readUShortAt must evaluate 0xFFFF as positive 65,535")
    }

    @Test
    fun should_preserveInterleavedHighAndLowBits_when_roundTrippingShortAtOffset() {
        // Given: High/Low byte pattern (0x80FE -> 32,768 + 254 = 33,022) at unaligned offset
        val value = 0x80FE
        val offset = 3
        val bytes = ByteArray(6)

        // When
        bytes.writeIntAsShortAt(offset = offset, value = value)
        val actual = bytes.readUShortAt(offset = offset)

        // Then
        bytes.assertBytesAt(offset = offset, 0x80, 0xFE)
        assertEquals(value, actual)
    }

    // ===================================================================
    // OFFSET
    // ===================================================================

    @Test
    fun should_performReadAndWriteOperationsAtArbitraryOffsets_when_offsetIsUnaligned() {
        // Given: A buffer padded with sentinel bytes (0xAA) to detect overrun/bleed
        val buffer = ByteArray(32) { 0xAA.toByte() }

        val longVal = 0x0102030405060708L
        val intVal = 0x11223344
        val uShortVal = 65000 // 0xFDE8

        // When: Write at arbitrary offsets
        buffer.writeLongAt(offset = 1, value = longVal)
        buffer.writeIntAt(offset = 10, value = intVal)
        buffer.writeIntAsShortAt(offset = 15, value = uShortVal)

        // Then: Byte slices
        buffer.assertBytesAt(offset = 1, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08)
        buffer.assertBytesAt(offset = 10, 0x11, 0x22, 0x33, 0x44)
        buffer.assertBytesAt(offset = 15, 0xFD, 0xE8)

        // Then: Values read back
        assertEquals(longVal, buffer.readLongAt(offset = 1))
        assertEquals(intVal, buffer.readIntAt(offset = 10))
        assertEquals(uShortVal, buffer.readUShortAt(offset = 15))

        // Then: Sentinel byte integrity
        assertEquals(0xAA.toByte(), buffer[0], "Byte before offset 1 must remain untouched")
        assertEquals(0xAA.toByte(), buffer[9], "Byte between Long and Int must remain untouched")
        assertEquals(0xAA.toByte(), buffer[14], "Byte between Int and Short must remain untouched")
        assertEquals(0xAA.toByte(), buffer[17], "Byte after Short must remain untouched")
    }

    @Test
    fun should_roundTripLongAtHighOffsetThirteen_when_simulatingRecordPayload() {
        // Given: An offset of 13 in a 27-byte record
        val offset = 13
        val bytes = ByteArray(27)
        val value = 0x7FEDCBA987654321L

        // When
        bytes.writeLongAt(offset = offset, value = value)
        val actual = bytes.readLongAt(offset = offset)

        // Then
        bytes.assertBytesAt(
            offset = offset,
            0x7F, 0xED, 0xCB, 0xA9, 0x87, 0x65, 0x43, 0x21
        )
        assertEquals(value, actual)
    }

    // ===================================================================
    // BOUNDARY EXCEPTIONS
    // ===================================================================

    @Test
    fun should_throwIndexOutOfBoundsException_when_readingOrWritingLongBeyondBounds() {
        val value = 0x0102030405060708L

        assertFailsWith<IndexOutOfBoundsException> { ByteArray(7).readLongAt(offset = 0) }
        assertFailsWith<IndexOutOfBoundsException> { ByteArray(7).writeLongAt(offset = 0, value = value) }
        assertFailsWith<IndexOutOfBoundsException> { ByteArray(8).readLongAt(offset = 1) }
        assertFailsWith<IndexOutOfBoundsException> { ByteArray(8).writeLongAt(offset = 1, value = value) }
    }

    @Test
    fun should_throwIndexOutOfBoundsException_when_readingOrWritingIntBeyondArrayBounds() {
        val intVal = 0x12345678

        assertFailsWith<IndexOutOfBoundsException> { ByteArray(3).readIntAt(offset = 0) }
        assertFailsWith<IndexOutOfBoundsException> { ByteArray(3).writeIntAt(offset = 0, value = intVal) }
        assertFailsWith<IndexOutOfBoundsException> { ByteArray(4).readIntAt(offset = 1) }
        assertFailsWith<IndexOutOfBoundsException> { ByteArray(4).writeIntAt(offset = 1, value = intVal) }
    }

    @Test
    fun should_throwIndexOutOfBoundsException_when_readingOrWritingShortBeyondArrayBounds() {
        val shortVal = 32000

        assertFailsWith<IndexOutOfBoundsException> { ByteArray(1).readUShortAt(offset = 0) }
        assertFailsWith<IndexOutOfBoundsException> { ByteArray(1).writeIntAsShortAt(offset = 0, value = shortVal) }
        assertFailsWith<IndexOutOfBoundsException> { ByteArray(2).readUShortAt(offset = 1) }
        assertFailsWith<IndexOutOfBoundsException> { ByteArray(2).writeIntAsShortAt(offset = 1, value = shortVal) }
    }
}

// ===================================================================
// TEST HELPERS
// ===================================================================

/**
 * Asserts that [expected] bytes match the slice in [ByteArray] starting at [offset].
 * Accepts [Int] hex literals (e.g., 0x80, 0xFF) for clean test ergonomics.
 */
internal fun ByteArray.assertBytesAt(offset: Int, vararg expected: Int) {
    expected.forEachIndexed { i, expectedInt ->
        val actualIndex = offset + i
        val expectedByte = expectedInt.toByte()
        val actualByte = this[actualIndex]

        assertEquals(
            expected = expectedByte,
            actual = actualByte,
            message = "Mismatch at offset $actualIndex (relative index $i). Expected 0x${expectedInt.toHex()}, got 0x${actualByte.toHex()}"
        )
    }
}

internal fun Int.toHex(): String =
    (this and 0xFF).toString(16).padStart(2, '0').uppercase()

internal fun Byte.toHex(): String =
    (this.toInt() and 0xFF).toString(16).padStart(2, '0').uppercase()