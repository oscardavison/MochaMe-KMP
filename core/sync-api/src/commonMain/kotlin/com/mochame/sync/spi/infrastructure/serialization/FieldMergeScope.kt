package com.mochame.sync.spi.infrastructure.serialization

import co.touchlab.kermit.Logger
import com.mochame.sync.api.hlc.HLC
import com.mochame.sync.common.hasTag

/**
 * Scope provided to feature codecs during field-level delta merging.
 * Manages LWW evaluation and binary blob construction.
 *
 * @param isDelete True when the delta is a deletion: every domain tag is treated as a null-write at [incomingHlc].
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
     * Field LWW Rule:
     * - If field is missing from delta through Protocol Buffer implicit field usage (null), retain existing value.
     * - If field is present, accept if no local tag HLC exists OR incoming HLC > local tag HLC.
     * - Otherwise, reject incoming value and retain existing local value.
     */
    @Suppress("NOTHING_TO_INLINE")
    inline fun <V> eval(tagId: Int, incomingVal: V?, existingVal: V?): V? {
        // A delete delta carries no domain fields (incomingVal is null) and mask {2};
        if (!isDelete && !changedMask.hasTag(tagId)) return existingVal

        val localTagHlc = index.getHlc(tagId)

        return if (localTagHlc == null || incomingHlc > localTagHlc) {
            index = index.updateTag(tagId, incomingHlc)
            incomingVal
        } else {
            logger.v { "Field Rejected [tag=$tagId]. Local HLC ($localTagHlc) >= Inbound ($incomingHlc)" }
            existingVal
        }
    }

    /** Stamp the tag only if the incoming HLC is newer. */
    internal fun stampIfNewer(tagId: Int) {
        val local = index.getHlc(tagId)
        if (local == null || incomingHlc > local) {
            index = index.updateTag(tagId, incomingHlc)
        }
    }

    internal fun hasTagNewerThan(horizon: HLC, excludeTag: Int): Boolean =
        index.hasTagNewerThan(horizon, excludeTag)

    internal fun getTagHlc(tagId: Int): HLC? = index.getHlc(tagId)

    internal fun buildResultBlob(): ByteArray = index.bytes
}