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
 * Inlines the [bytes] property. Immutable, defining the CRDT structure at the field level for LWW.
 *
 * |ProtoTag(1 Byte)|Timestamp(8 Bytes)|Count(2 Bytes)|NodeId(16 Bytes) - 27 Bytes per field.
 */
@JvmInline
@PublishedApi
internal value class FieldHlcMap(val bytes: ByteArray) {

    init {
        require(bytes.size % RECORD_SIZE == 0) {
            "ByteArray size (${bytes.size}) must be a multiple of $RECORD_SIZE"
        }
    }

    fun getHlc(tagId: Int): HLC? {
        require(tagId in 0..127)

        val index = findTagIndex(tagId) ?: return null
        return readHlcAt(index)
    }

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

    /** True if any record other than [excludeTag] carries an HLC strictly newer than [horizon]. */
    fun hasTagNewerThan(horizon: HLC, excludeTag: Int): Boolean {
        var i = 0
        while (i < bytes.size) {
            val tag = bytes[i].toInt()
            if (tag != excludeTag) {
                val hlc = readHlcAt(i)
                if (hlc > horizon) return true
            }
            i += RECORD_SIZE
        }
        return false
    }

    fun withoutTag(tagId: Int): FieldHlcMap {
        val i = findTagIndex(tagId) ?: return this
        return FieldHlcMap(bytes.copyOfRange(0, i) + bytes.copyOfRange(i + RECORD_SIZE, bytes.size))
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
        const val RECORD_SIZE = 27
        val EMPTY = FieldHlcMap(ByteArray(0))
    }
}