package com.mochame.sync.domain.crdt

import com.mochame.sync.utils.readLongAt
import com.mochame.sync.utils.readUShortAt
import com.mochame.sync.utils.writeIntAsShortAt
import com.mochame.sync.utils.writeLongAt
import com.mochame.sync.api.models.HLC
import com.mochame.sync.api.models.NodeId
import kotlin.jvm.JvmInline
import kotlin.uuid.Uuid

/**
 * Binary index mapping Protobuf tags to their last modification [HLC].
 *
 * Backed by a contiguous [ByteArray] composed of 27-byte records:
 * `| tagId (1B) | timestamp (8B) | logical count (2B) | nodeId (16B) |`
 */
@JvmInline
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

    /**
     * Batch updates multiple tags.
     */
    fun updateTags(tags: List<Int>, hlc: HLC): FieldHlcMap {
        if (tags.isEmpty()) return this

        var newEntriesCount = 0
        val indices = IntArray(tags.size)

        for (i in tags.indices) {
            val idx = findTagIndex(tags[i])
            indices[i] = idx ?: -1
            if (idx == null) newEntriesCount++
        }

        val targetSize = bytes.size + (newEntriesCount * RECORD_SIZE)
        val target = bytes.copyOf(targetSize)
        var appendOffset = bytes.size

        for (i in tags.indices) {
            val existingIndex = indices[i]
            val writeIdx = if (existingIndex != -1) existingIndex else appendOffset.also { appendOffset += RECORD_SIZE }
            writeRecordAt(target, writeIdx, tags[i], hlc)
        }

        return FieldHlcMap(target)
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

    fun hasTagNewerThan(horizon: HLC, excludeTag: Int): Boolean {
        var i = 0
        while (i < bytes.size) {
            val tag = bytes[i].toInt()
            if (tag != excludeTag && readHlcAt(i) > horizon) return true
            i += RECORD_SIZE
        }
        return false
    }

    private fun findTagIndex(tagId: Int): Int? {
        var i = 0
        while (i < bytes.size) {
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
        return HLC(ts = ts, count = count, nodeId = NodeId(Uuid.fromLongs(msb, lsb)))
    }

    private fun writeRecordAt(target: ByteArray, offset: Int, tagId: Int, hlc: HLC) {
        target[offset] = tagId.toByte()
        target.writeLongAt(offset + 1, hlc.ts)
        target.writeIntAsShortAt(offset + 9, hlc.count)
        hlc.nodeId.value.toLongs { msb, lsb ->
            target.writeLongAt(offset + 11, msb)
            target.writeLongAt(offset + 19, lsb)
        }
    }

    companion object {
        const val RECORD_SIZE = 27
    }
}