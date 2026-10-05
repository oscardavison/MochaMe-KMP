package com.mochame.sync.spi.infrastructure.serialization

import co.touchlab.kermit.Logger
import com.mochame.sync.api.FieldResolver
import com.mochame.sync.api.internal.hasTag
import com.mochame.sync.api.models.HLC
import com.mochame.sync.spi.infrastructure.serialization.BaseFeatureCodec.Companion.TAG_IS_DELETED

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
internal class FieldMergeScope(
    existingBytes: ByteArray,
    val incomingHlc: HLC,
    val changedMask: Long,
    val logger: Logger,
    val isDelete: Boolean = false,
) : FieldResolver {
    internal var index = FieldHlcMap(existingBytes)
    internal val lastDeleteHlc = index.getHlc(TAG_IS_DELETED)

    /**
     * Evaluates field resolution using Last-Write-Wins:
     * - Deletion sweep (`isDelete == true`): Nullifies the field and updates its tag to [incomingHlc]
     *   if the local tag HLC is older than or equal to [incomingHlc]. If a local edit occurred strictly
     *   after [incomingHlc], [existing] survives.
     * - Incoming upserts must be greater than the local deletion horizon.
     * - Sparse upsert (`isDelete == false`):
     *   - If [tagId] is absent from [changedMask], retains [existing] without modifying the tag index.
     *   - If [tagId] is present in [changedMask], accepts [incoming] and updates the tag index if
     *     the local tag HLC is null or [incomingHlc] > local tag HLC. Otherwise, retains [existing].
     */
    override fun <V> resolve(
        tagId: Int,
        incoming: V?,
        existing: V?,
    ): V? {
        if (isDelete) {
            val localTagHlc = index.getHlc(tagId)
            return if (localTagHlc == null || incomingHlc >= localTagHlc) {
                index = index.updateTag(tagId, incomingHlc)
                null
            } else {
                existing
            }
        }

        if (!changedMask.hasTag(tagId)) return existing

        if (lastDeleteHlc != null && incomingHlc <= lastDeleteHlc) {
            logger.v { "Field Rejected [tag=$tagId]. Inbound HLC ($incomingHlc) <= Delete Horizon ($lastDeleteHlc)" }
            return existing
        }

        val localTagHlc = index.getHlc(tagId)
        return if (localTagHlc == null || incomingHlc > localTagHlc) {
            index = index.updateTag(tagId, incomingHlc)
            incoming
        } else {
            logger.v { "Field Rejected [tag=$tagId]. Local HLC ($localTagHlc) >= Inbound ($incomingHlc)" }
            existing
        }
    }

    /**
     * Returns the recorded [HLC] for [tagId] in the active merge index.
     */
    internal fun getHlc(tagId: Int): HLC? = index.getHlc(tagId)

    /**
     * Explicitly stamps [tagId] with [hlc] in the active merge index.
     */
    internal fun updateTag(tagId: Int, hlc: HLC) {
        index = index.updateTag(tagId, hlc)
    }

    internal fun hasTagNewerThan(horizon: HLC, excludeTag: Int): Boolean =
        index.hasTagNewerThan(horizon, excludeTag)

    /**
     * Explicit reference to the current inline [ByteArray] representation.
     */
    internal fun buildResultBlob(): ByteArray = index.bytes
}