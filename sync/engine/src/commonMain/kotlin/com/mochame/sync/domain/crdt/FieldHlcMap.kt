package com.mochame.sync.domain.crdt

import com.mochame.sync.api.internal.readLongAt
import com.mochame.sync.api.internal.readUShortAt
import com.mochame.sync.api.internal.writeIntAsShortAt
import com.mochame.sync.api.internal.writeLongAt
import com.mochame.sync.api.models.HLC
import com.mochame.sync.spi.node.NodeId
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
            "ByteArray size (${bytes.size}) must be a multiple of$RECORD_SIZE"
        }
    }

    fun getHlc(tagId: Int): HLC? {
        val index = findTagIndex(tagId) ?: return null
        return readHlcAt(index)
    }

    /**
     * Batch updates multiple tags using a single destination array allocation.
     */
    fun updateTags(tags: List<Int>, hlc: HLC): FieldHlcMap {
        if (tags.isEmpty()) return this

        val indices = tags.map { findTagIndex(it) }
        val newEntriesCount = indices.count { it == null }
        val targetSize = bytes.size + (newEntriesCount * RECORD_SIZE)

        val target = bytes.copyOf(targetSize)
        var appendOffset = bytes.size

        tags.forEachIndexed { i, tagId ->
            val existingIndex = indices[i]
            val writeIdx = existingIndex ?: appendOffset.also { appendOffset += RECORD_SIZE }
            writeRecordAt(target, writeIdx, tagId, hlc)
        }

        return FieldHlcMap(target)
    }

    fun updateTag(tagId: Int, hlc: HLC): FieldHlcMap = updateTags(listOf(tagId), hlc)

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