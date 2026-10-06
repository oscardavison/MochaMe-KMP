package com.mochame.sync.api.utils


import co.touchlab.kermit.Logger
import com.mochame.sync.api.exceptions.MochaException
import kotlinx.io.Source


/**
 * Reads a raw LEB128 Varint (up to 64 bits / 10 bytes) from the source.
 * Handles both keys and 64-bit payload values (int32, int64, bool, enum, sint).
 */
fun Source.readProtobufVarint(logger: Logger): Long {
    var value = 0L
    var shift = 0
    var byteCount = 0

    try {
        while (true) {
            val byte = this.readByte().toLong()
            byteCount++

            val payload7Bit = byte and 0x7FL
            value = value or (payload7Bit shl shift)
            val hasMoreBytes = (byte and 0x80L) != 0L

            logger.v {
                val hex = (byte and 0xFFL).toString(16).padStart(2, '0').uppercase()
                val binary = (byte and 0xFFL).toString(2).padStart(8, '0')
                "Varint byte #$byteCount [0x$hex | 0b$binary] -> " +
                        "7-bit payload=$payload7Bit, shift=$shift, accumulative value=$value" +
                        if (hasMoreBytes) " [MSB=1: continuation]" else " [MSB=0: final byte]"
            }

            if (!hasMoreBytes) break

            shift += 7
            if (byteCount >= 10) {
                throw Exception("Varint overflow: stream exceeded 10 bytes for a single 64-bit value")
            }
        }

        logger.v { "Varint total decoded value=$value across $byteCount byte(s)" }
        return value

    } catch (e: Exception) {
        logger.e(e) { "Binary Corruption: Failed to read Varint at shift $shift after $byteCount byte(s)" }
        throw MochaException.Transient.StateIssue("Varint overflow or unexpected EOF", e)
    }
}

/**
 *  * WireType 0 (Varint): Call readProtobufVarint() again on the value until MSB = 0.
 *  * WireType 1 (Fixed 64-bit): Unconditionally skip 8 bytes.
 *  * WireType 2 (Length-delimited): Read a Varint for length L, then skip L bytes.
 *  * WireType 5 (Fixed 32-bit): Unconditionally skip 4 bytes.
 */
fun Source.skipProtobufValue(wireType: Int, logger: Logger) {
    logger.v { "Skipping payload value for wireType=$wireType" }

    when (wireType) {
        0 -> { // Varint (int32, int64, bool, enum, timestamp)
            val skippedVarint = this.readProtobufVarint(logger)
            logger.v { "Skipped WireType 0 (Varint) value=$skippedVarint" }
        }
        1 -> { // 64-bit fixed (double, fixed64, sfixed64)
            this.skip(8)
            logger.v { "Skipped WireType 1 (64-bit fixed: 8 bytes)" }
        }
        2 -> { // Length-delimited (string, bytes, embedded sub-messages)
            val length = this.readProtobufVarint(logger)
            this.skip(length)
            logger.v { "Skipped WireType 2 (Length-Delimited: $length payload bytes)" }
        }
        5 -> { // 32-bit fixed (float, fixed32, sfixed32)
            this.skip(4)
            logger.v { "Skipped WireType 5 (32-bit fixed: 4 bytes)" }
        }
        else -> {
            val errorMsg = "Unsupported Wire Type: $wireType"
            logger.e { errorMsg }
            throw MochaException.Transient.StateIssue(errorMsg)
        }
    }
}