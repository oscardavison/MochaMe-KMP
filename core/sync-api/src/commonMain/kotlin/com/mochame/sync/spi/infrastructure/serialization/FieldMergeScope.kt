package com.mochame.sync.spi.infrastructure.serialization

import co.touchlab.kermit.Logger
import com.mochame.sync.api.hlc.HLC
import com.mochame.sync.common.hasTag

/**
 * Scope provided to feature codecs during field-level delta merging.
 *
 * Coordinates Last-Write-Wins (LWW) evaluation and binary tag index construction.
 *
 * @param existingBytes Serialized [FieldHlcMap] bytes from the local record.
 * @param incomingHlc Logical timestamp assigned to the inbound delta.
 * @param changedMask Bitmask of tag IDs included in the inbound delta.
 * @param isDelete True when merging a deletion intent; causes [eval] to run the tombstone sweep.
 */
class FieldMergeScope(
    existingBytes: ByteArray,
    val incomingHlc: HLC,
    val changedMask: Long,
    val logger: Logger,
    val isDelete: Boolean = false
) {
    @PublishedApi
    internal var index = FieldHlcMap(existingBytes)

    /**
     * Evaluates field resolution using Last-Write-Wins:
     * - Deletion sweep (`isDelete == true`): Nullifies the field and updates its tag to [incomingHlc]
     *   if the local tag HLC is older than or equal to [incomingHlc]. If a local edit occurred strictly
     *   after [incomingHlc], [existingVal] survives.
     * - Sparse upsert (`isDelete == false`):
     *   - If [tagId] is absent from [changedMask], retains [existingVal] without modifying the tag index.
     *   - If [tagId] is present in [changedMask], accepts [incomingVal] and updates the tag index if
     *     the local tag HLC is null or [incomingHlc] > local tag HLC. Otherwise, retains [existingVal].
     */
    @Suppress("NOTHING_TO_INLINE")
    inline fun <V> eval(tagId: Int, incomingVal: V?, existingVal: V?): V? {
        if (isDelete) {
            val localTagHlc = index.getHlc(tagId)
            return if (localTagHlc == null || incomingHlc >= localTagHlc) {
                index = index.updateTag(tagId, incomingHlc)
                null
            } else {
                existingVal
            }
        }

        if (!changedMask.hasTag(tagId)) return existingVal

        val localTagHlc = index.getHlc(tagId)
        return if (localTagHlc == null || incomingHlc > localTagHlc) {
            index = index.updateTag(tagId, incomingHlc)
            incomingVal
        } else {
            logger.v { "Field Rejected [tag=$tagId]. Local HLC ($localTagHlc) >= Inbound ($incomingHlc)" }
            existingVal
        }
    }

    /**
     * Returns the recorded [HLC] for [tagId] in the active merge index.
     */
    internal fun getTagHlc(tagId: Int): HLC? = index.getHlc(tagId)

    /**
     * Explicitly stamps [tagId] with [hlc] in the active merge index.
     */
    fun updateTag(tagId: Int, hlc: HLC) {
        index = index.updateTag(tagId, hlc)
    }

    /**
     * Explicit reference to the current inline [ByteArray] representation.
     */
    internal fun buildResultBlob(): ByteArray = index.bytes
}