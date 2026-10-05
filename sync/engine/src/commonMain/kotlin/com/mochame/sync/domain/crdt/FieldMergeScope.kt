package com.mochame.sync.domain.crdt

import co.touchlab.kermit.Logger
import com.mochame.sync.api.FieldResolver
import com.mochame.sync.api.internal.hasTag
import com.mochame.sync.api.models.HLC
import com.mochame.sync.api.models.instant
import com.mochame.sync.spi.infrastructure.serialization.BaseFeatureCodec.Companion.TAG_IS_DELETED
import kotlin.time.Instant

/**
 * Internal CRDT evaluation engine.
 *
 * Implements [FieldResolver] for domain properties while managing tombstone
 * transitions and creation horizon resolution.
 */
internal class FieldMergeScope(
    existingBytes: ByteArray,
    val incomingHlc: HLC,
    private val changedMask: Long,
    private val isDelete: Boolean,
    private val logger: Logger
) : FieldResolver {

    private var index = FieldHlcMap(existingBytes)
    private val lastDeleteHlc = index.getHlc(TAG_IS_DELETED)


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
    override fun <V> resolve(tagId: Int, incomingVal: V?, existingVal: V?): V? {
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

        if (lastDeleteHlc != null && incomingHlc <= lastDeleteHlc) {
            return existingVal
        }

        val localTagHlc = index.getHlc(tagId)
        return if (localTagHlc == null || incomingHlc > localTagHlc) {
            index = index.updateTag(tagId, incomingHlc)
            incomingVal
        } else {
            existingVal
        }
    }

    fun resolveDeleteState(
        deltaIsDeleted: Boolean?,
        existingIsDeleted: Boolean?,
        candidateKey: Long
    ): Boolean {
        val isNewer = lastDeleteHlc == null || incomingHlc > lastDeleteHlc

        return when {
            // Explicit delete intent
            deltaIsDeleted == true -> {
                if (isNewer) {
                    updateTag(TAG_IS_DELETED, incomingHlc)
                    val hasSurvivingField = hasTagNewerThan(incomingHlc, TAG_IS_DELETED)
                    !hasSurvivingField
                } else {
                    existingIsDeleted ?: true
                }
            }

            // Explicit un-delete intent (e.g., CLI or UI toggling isDeleted back to false)
            deltaIsDeleted == false -> {
                if (isNewer) {
                    logger.i { "Restored [key=$candidateKey]: explicit restore (HLC=$incomingHlc) overrides delete (HLC=$lastDeleteHlc)" }
                    false
                } else {
                    existingIsDeleted ?: false
                }
            }

            // Implicit revival: Incoming upsert arrives against an existing soft delete
            existingIsDeleted == true -> {
                if (isNewer) {
                    updateTag(TAG_IS_DELETED, incomingHlc)
                    logger.i { "Restored [key=$candidateKey]: incoming edit (HLC=$incomingHlc) overrides delete (HLC=$lastDeleteHlc)" }
                    false
                } else {
                    true
                }
            }

            else -> false
        }
    }

    fun resolveCreatedAt(deltaCreatedAt: Long?, existingCreatedAt: Instant?): Instant = when {
        existingCreatedAt == null && deltaCreatedAt != null -> {
            Instant.fromEpochMilliseconds(deltaCreatedAt)
        }
        existingCreatedAt != null && deltaCreatedAt == null -> {
            existingCreatedAt
        }
        existingCreatedAt != null && deltaCreatedAt != null -> {
            minOf(existingCreatedAt, Instant.fromEpochMilliseconds(deltaCreatedAt))
        }
        else -> incomingHlc.instant
    }

    /**
     * Explicitly stamps [tagId] with [hlc] in the active merge index.
     */
    internal fun updateTag(tagId: Int, hlc: HLC) {
        index = index.updateTag(tagId, hlc)
    }

    internal fun hasTagNewerThan(horizon: HLC, excludeTag: Int): Boolean =
        index.hasTagNewerThan(horizon, excludeTag)

    fun buildResultBlob(): ByteArray = index.bytes
}