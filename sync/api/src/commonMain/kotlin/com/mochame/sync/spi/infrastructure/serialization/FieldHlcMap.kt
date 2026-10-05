package com.mochame.sync.spi.infrastructure.serialization

import com.mochame.sync.api.internal.readLongAt
import com.mochame.sync.api.internal.readUShortAt
import com.mochame.sync.api.internal.writeIntAsShortAt
import com.mochame.sync.api.internal.writeLongAt
import com.mochame.sync.api.models.HLC
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
    @PublishedApi
    internal fun getHlc(tagId: Int): HLC? {
        require(tagId in 0..127)

        val index = findTagIndex(tagId) ?: return null
        return readHlcAt(index)
    }

    /**
     * Returns a new [FieldHlcMap] with [tagId] updated in place or appended at [hlc].
     */
    @PublishedApi
    internal fun updateTag(tagId: Int, hlc: HLC): FieldHlcMap {
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
            val writeIdx = existingIndex ?: appendOffset.also { appendOffset += com.mochame.sync.domain.crdt.FieldHlcMap.Companion.RECORD_SIZE }
            writeRecordAt(target, writeIdx, tagId, hlc)
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
        /** Byte length of an individual tag record in the index. */
        const val RECORD_SIZE = 27
        /** Default empty index containing zero tag records. */
        val EMPTY = FieldHlcMap(ByteArray(0))
    }
}