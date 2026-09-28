package com.mochame.sync.spi.network

import com.mochame.sync.common.readLongAt
import com.mochame.sync.common.writeLongAt

/**
 * Parsed representations of possible frame types.
 */
sealed interface WireFrame {
    /** Indicates historical catch-up streaming is finished. */
    data object BackfillComplete : WireFrame

    /** Server acknowledgment confirming a committed client. */
    data class Ack(val batchId: Long, val watermark: Long) : WireFrame

    /** Broadcast delta from a remote peer. */
    data class Delta(val watermark: Long, val payload: ByteArray) : WireFrame

    /** Client intent received by the server. */
    data class ClientSubmit(val batchId: Long, val payload: ByteArray) : WireFrame
}

/**
 * [WireFrame] serialization and deserialization protocol.
 *
 * Wire format layouts (big-endian):
 * - **BackfillComplete:** `[0x01]` (1 byte)
 * - **Ack:** `[0x02][batchId: 8B][watermark: 8B]` (17 bytes)
 * - **Delta:** `[0x03][watermark: 8B][payload: NB]` (9+ bytes)
 * - **Batch (ClientSubmit):** `[0x04][batchId: 8B][payload: NB]` (9+ bytes)
 */
object WireFrameFactory {
    private const val OP_BACKFILL_COMPLETE: Byte = 0x01
    private const val OP_ACK: Byte = 0x02
    private const val OP_DELTA: Byte = 0x03
    private const val OP_CLIENT_SUBMIT: Byte = 0x04

    /**
     * Encodes a 1-byte delimiter frame signaling the end of historical backfill.
     * This implies the client is now receiving live peer broadcasts.
     */
    fun backfillComplete(): ByteArray = byteArrayOf(OP_BACKFILL_COMPLETE)

    /** Encodes an acknowledgment frame assigning a persistent watermark to a submitted client ID. */
    fun ack(batchId: Long, watermark: Long): ByteArray {
        val out = ByteArray(17)
        out[0] = OP_ACK
        out.writeLongAt(1, batchId)
        out.writeLongAt(9, watermark)
        return out
    }

    /** Encodes a downstream delta broadcast frame. */
    fun delta(watermark: Long, payload: ByteArray): ByteArray {
        require(payload.isNotEmpty())

        val out = ByteArray(9 + payload.size)
        out[0] = OP_DELTA
        out.writeLongAt(1, watermark)
        payload.copyInto(out, destinationOffset = 9)
        return out
    }

    /** Encodes an upstream client intent frame. */
    fun client(batchId: Long, payload: ByteArray): ByteArray {
        require(payload.isNotEmpty())

        val out = ByteArray(9 + payload.size)
        out[0] = OP_CLIENT_SUBMIT
        out.writeLongAt(1, batchId)
        payload.copyInto(out, destinationOffset = 9)
        return out
    }

    /**
     * Decodes a raw binary packet into its typed [WireFrame] representation.
     *
     * @throws IllegalArgumentException If the byte array is empty or does not meet the expected opcode size.
     * @throws IllegalStateException If the first byte does not match a known opcode.
     */
    fun unwrap(bytes: ByteArray): WireFrame {
        require(bytes.isNotEmpty()) { "Frame header error: Empty payload" }

        return when (bytes[0]) {
            OP_BACKFILL_COMPLETE -> WireFrame.BackfillComplete

            OP_ACK -> {
                require(bytes.size == 17) { "Ack frame header error: Expected 17 bytes, got ${bytes.size}" }
                val batchId = bytes.readLongAt(1)
                val watermark = bytes.readLongAt(9)
                WireFrame.Ack(batchId, watermark)
            }

            OP_DELTA -> {
                require(bytes.size >= 9) { "Delta frame header error: Expected >= 9 bytes, got ${bytes.size}" }
                val watermark = bytes.readLongAt(1)
                val payload = bytes.copyOfRange(9, bytes.size)
                WireFrame.Delta(watermark, payload)
            }

            OP_CLIENT_SUBMIT -> {
                require(bytes.size >= 9) { "ClientSubmit frame header error: Expected >= 9 bytes, got ${bytes.size}" }
                val batchId = bytes.readLongAt(1)
                val payload = bytes.copyOfRange(9, bytes.size)
                WireFrame.ClientSubmit(batchId, payload)
            }

            else -> error("Unknown wire frame opcode: 0x${bytes[0].toString(16)}")
        }
    }
}

fun WireFrame.encode(): ByteArray = when (this) {
    is WireFrame.BackfillComplete -> WireFrameFactory.backfillComplete()
    is WireFrame.Ack -> WireFrameFactory.ack(batchId, watermark)
    is WireFrame.Delta -> WireFrameFactory.delta(watermark, payload)
    is WireFrame.ClientSubmit -> WireFrameFactory.client(batchId, payload)
}