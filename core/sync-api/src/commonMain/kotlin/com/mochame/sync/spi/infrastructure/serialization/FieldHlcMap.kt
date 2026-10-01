package com.mochame.sync.spi.infrastructure.serialization

import com.mochame.sync.api.hlc.HLC
import com.mochame.sync.common.readLongAt
import com.mochame.sync.common.readUShortAt
import com.mochame.sync.common.writeLongAt
import com.mochame.sync.common.writeIntAsShortAt
import com.mochame.sync.spi.node.NodeId
import kotlin.jvm.JvmInline
import kotlin.uuid.Uuid

/**
 * Immutable binary index mapping Protobuf field tag numbers to their last-modified [HLC].
 *
 * Backed by a contiguous [ByteArray] composed of fixed 27-byte records:
 * `| tagId (1B) | timestamp (8B) | logical count (2B) | nodeId (16B) |`
 */
@JvmInline
@PublishedApi
internal value class FieldHlcMap(val bytes: ByteArray) {

    init {
        require(bytes.size % RECORD_SIZE == 0) {
            "ByteArray size (${bytes.size}) must be a multiple of $RECORD_SIZE"
        }
    }

    /**
     * Returns the [HLC] recorded for [tagId], or `null` if the tag has not been recorded.
     */
    fun getHlc(tagId: Int): HLC? {
        require(tagId in 0..127)

        val index = findTagIndex(tagId) ?: return null
        return readHlcAt(index)
    }

    /**
     * Returns a new [FieldHlcMap] with [tagId] updated in place or appended at [hlc].
     */
    fun updateTag(tagId: Int, hlc: HLC): FieldHlcMap {
        require(tagId in 0..127)

        val index = findTagIndex(tagId)
        val target = bytes.copyOf(index?.let { bytes.size } ?: (bytes.size + RECORD_SIZE))
        val writeIdx = index ?: bytes.size

        target[writeIdx] = tagId.toByte()
        target.writeLongAt(writeIdx + 1, hlc.ts)
        target.writeIntAsShortAt(writeIdx + 9, hlc.count)

        hlc.nodeId.value.toLongs { msb, lsb ->
            target.writeLongAt(writeIdx + 11, msb)
            target.writeLongAt(writeIdx + 19, lsb)
        }

        return FieldHlcMap(target)
    }

    private fun findTagIndex(tagId: Int): Int? {
        var i = 0
        while (i < bytes.size) {
            // no bitmask operation needed here due to requirement
            if (bytes[i].toInt() == tagId) return i
            i += RECORD_SIZE
        }
        return null
    }

    private fun readHlcAt(offset: Int): HLC {
        val ts = bytes.readLongAt(offset + 1)
        val count = bytes.readUShortAt(offset + 9)
        val msb = bytes.readLongAt(offset + 11)
        val lsb = bytes.readLongAt(offset + 19)
        val nodeId = NodeId(Uuid.fromLongs(msb, lsb))

        return HLC(ts = ts, count = count, nodeId = nodeId)
    }

    companion object {
        /** Byte length of an individual tag record in the index. */
        const val RECORD_SIZE = 27
        /** Default empty index containing zero tag records. */
        val EMPTY = FieldHlcMap(ByteArray(0))
    }
}