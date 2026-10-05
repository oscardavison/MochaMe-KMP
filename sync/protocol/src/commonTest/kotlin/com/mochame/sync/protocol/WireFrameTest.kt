package com.mochame.sync.protocol

import com.mochame.support.MochaPlatformTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WireFrameTest : MochaPlatformTest() {

    // ===================================================================
    // CONVERSION INTEGRITY / ROUND-TRIP SERIALIZATION
    // ===================================================================

    @Test
    fun should_preserveDataFields_when_roundTrippingBackfillCompleteFrame() {
        // Given
        val originalFrame = WireFrame.BackfillComplete

        // When
        val encodedBytes = originalFrame.encode()
        val unwrappedFrame = WireFrameFactory.unwrap(encodedBytes)

        // Then
        assertEquals(originalFrame, unwrappedFrame)
    }

    @Test
    fun should_preserveDataFields_when_roundTrippingAckFrame() {
        // Given
        val batchId = 123456789012345L
        val watermark = 987654321098765L
        val originalFrame = WireFrame.Ack(batchId = batchId, watermark = watermark)

        // When
        val encodedBytes = originalFrame.encode()
        val unwrappedFrame = WireFrameFactory.unwrap(encodedBytes)

        // Then
        assertIs<WireFrame.Ack>(unwrappedFrame)
        assertEquals(batchId, unwrappedFrame.batchId)
        assertEquals(watermark, unwrappedFrame.watermark)
        assertEquals(originalFrame, unwrappedFrame)
    }

    @Test
    fun should_preserveDataFields_when_roundTrippingDeltaFrame() {
        // Given
        val watermark = 42L
        val payload = "sync delta payload bytes".encodeToByteArray()
        val originalFrame = WireFrame.Delta(watermark = watermark, payload = payload)

        // When
        val encodedBytes = originalFrame.encode()
        val unwrappedFrame = WireFrameFactory.unwrap(encodedBytes)

        // Then
        assertIs<WireFrame.Delta>(unwrappedFrame)
        assertEquals(watermark, unwrappedFrame.watermark)
        assertTrue(payload.contentEquals(unwrappedFrame.payload))
    }

    @Test
    fun should_preserveDataFields_when_roundTrippingClientSubmitFrame() {
        // Given
        val batchId = 100L
        val payload = "client intent payload bytes".encodeToByteArray()
        val originalFrame = WireFrame.ClientSubmit(batchId = batchId, payload = payload)

        // When
        val encodedBytes = originalFrame.encode()
        val unwrappedFrame = WireFrameFactory.unwrap(encodedBytes)

        // Then
        assertIs<WireFrame.ClientSubmit>(unwrappedFrame)
        assertEquals(batchId, unwrappedFrame.batchId)
        assertTrue(payload.contentEquals(unwrappedFrame.payload))
    }

    @Test
    fun should_encodeAndDecodeConsistently_when_usingFactoryMethodsDirectly() {
        // Given
        val batchId = 55L
        val watermark = 77L
        val payload = byteArrayOf(1, 2, 3, 4, 5)

        // When
        val ackBytes = WireFrameFactory.ack(batchId, watermark)
        val deltaBytes = WireFrameFactory.delta(watermark, payload)
        val clientBytes = WireFrameFactory.client(batchId, payload)
        val backfillBytes = WireFrameFactory.backfillComplete()

        // Then
        val ackFrame = WireFrameFactory.unwrap(ackBytes)
        assertIs<WireFrame.Ack>(ackFrame)
        assertEquals(batchId, ackFrame.batchId)
        assertEquals(watermark, ackFrame.watermark)

        val deltaFrame = WireFrameFactory.unwrap(deltaBytes)
        assertIs<WireFrame.Delta>(deltaFrame)
        assertEquals(watermark, deltaFrame.watermark)
        assertTrue(payload.contentEquals(deltaFrame.payload))

        val clientFrame = WireFrameFactory.unwrap(clientBytes)
        assertIs<WireFrame.ClientSubmit>(clientFrame)
        assertEquals(batchId, clientFrame.batchId)
        assertTrue(payload.contentEquals(clientFrame.payload))

        val backfillFrame = WireFrameFactory.unwrap(backfillBytes)
        assertEquals(WireFrame.BackfillComplete, backfillFrame)
    }

    // ===================================================================
    // PAYLOAD SIZING & WIRE FORMAT SPECIFICATION
    // ===================================================================

    @Test
    fun should_produceExact1BytePayload_when_encodingBackfillCompleteFrame() {
        // Given
        val frame = WireFrame.BackfillComplete

        // When
        val bytes = frame.encode()

        // Then
        assertEquals(1, bytes.size)
        assertEquals(0x01.toByte(), bytes[0])
    }

    @Test
    fun should_produceExact17BytePayload_when_encodingAckFrame() {
        // Given
        val frame = WireFrame.Ack(batchId = 1L, watermark = 2L)

        // When
        val bytes = frame.encode()

        // Then
        assertEquals(17, bytes.size)
        assertEquals(0x02.toByte(), bytes[0])
    }

    @Test
    fun should_produceExact9PlusNBytePayload_when_encodingDeltaFrame() {
        // Given
        val payloadSizes = listOf(1, 10, 256, 1024)

        for (size in payloadSizes) {
            val payload = ByteArray(size) { it.toByte() }
            val frame = WireFrame.Delta(watermark = 100L, payload = payload)

            // When
            val bytes = frame.encode()

            // Then
            assertEquals(
                expected = 9 + size,
                actual = bytes.size,
                message = "Delta frame size must equal header (9B) + payload ($size B)"
            )
            assertEquals(0x03.toByte(), bytes[0])
        }
    }

    @Test
    fun should_produceExact9PlusNBytePayload_when_encodingClientSubmitFrame() {
        // Given
        val payloadSizes = listOf(1, 10, 256, 1024)

        for (size in payloadSizes) {
            val payload = ByteArray(size) { it.toByte() }
            val frame = WireFrame.ClientSubmit(batchId = 50L, payload = payload)

            // When
            val bytes = frame.encode()

            // Then
            assertEquals(
                expected = 9 + size,
                actual = bytes.size,
                message = "ClientSubmit frame size must equal header (9B) + payload ($size B)"
            )
            assertEquals(0x04.toByte(), bytes[0])
        }
    }

    // ===================================================================
    // MALFORMED FRAME BOUNDARIES & VALIDATION
    // ===================================================================

    @Test
    fun should_throwIllegalArgumentException_when_unwrappingEmptyByteArray() {
        // Given
        val emptyBytes = ByteArray(0)

        // When & Then
        assertFailsWith<IllegalArgumentException> {
            WireFrameFactory.unwrap(emptyBytes)
        }
    }

    @Test
    fun should_throwIllegalStateException_when_unwrappingUnknownOpcode() {
        // Given
        val invalidOpcodeBytes = byteArrayOf(0x99.toByte(), 0, 0, 0)

        // When & Then
        assertFailsWith<IllegalStateException> {
            WireFrameFactory.unwrap(invalidOpcodeBytes)
        }
    }

    @Test
    fun should_throwIllegalArgumentException_when_ackFrameSizeIsNot17Bytes() {
        // Given
        val invalidAckBytesList = listOf(
            ByteArray(16) { if (it == 0) 0x02 else 0 },
            ByteArray(18) { if (it == 0) 0x02 else 0 },
            ByteArray(1) { 0x02 }
        )

        // When & Then
        for (bytes in invalidAckBytesList) {
            assertFailsWith<IllegalArgumentException> {
                WireFrameFactory.unwrap(bytes)
            }
        }
    }

    @Test
    fun should_throwIllegalArgumentException_when_deltaOrClientSubmitFrameSizeIsLessThan9Bytes() {
        // Given
        val invalidSizes = listOf(1, 5, 8)

        // When & Then
        for (size in invalidSizes) {
            val invalidDeltaBytes = ByteArray(size) { if (it == 0) 0x03 else 0 }
            assertFailsWith<IllegalArgumentException> {
                WireFrameFactory.unwrap(invalidDeltaBytes)
            }

            val invalidClientBytes = ByteArray(size) { if (it == 0) 0x04 else 0 }
            assertFailsWith<IllegalArgumentException> {
                WireFrameFactory.unwrap(invalidClientBytes)
            }
        }
    }

    @Test
    fun should_throwIllegalArgumentException_when_encodingDeltaOrClientWithEmptyPayload() {
        // Given
        val emptyPayload = ByteArray(0)

        // When & Then
        assertFailsWith<IllegalArgumentException> {
            WireFrameFactory.delta(watermark = 1L, payload = emptyPayload)
        }

        assertFailsWith<IllegalArgumentException> {
            WireFrameFactory.client(batchId = 1L, payload = emptyPayload)
        }
    }

}