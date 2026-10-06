package com.mochame.sync.domain.crdt

import co.touchlab.kermit.Logger
import com.mochame.sync.api.FieldResolver
import com.mochame.sync.utils.hasTag
import com.mochame.sync.api.models.HLC
import com.mochame.sync.api.models.instant
import com.mochame.sync.api.codec.BaseFeatureCodec.Companion.TAG_IS_DELETED
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

    private var initialIndex = FieldHlcMap(existingBytes)
    private val lastDeleteHlc = initialIndex.getHlc(TAG_IS_DELETED)

    /** Stash for single allocation, stored via resolve method calls. */
    private val tagsToUpdate = mutableListOf<Int>()

    /**
     * Evaluates field resolution using Last-Write-Wins, storing tags in [tagsToUpdate]:
     * - Deletion sweep (`isDelete == true`): Nullifies the field and updates its tag to [incomingHlc]
     *   if the local tag HLC is older than or equal to [incomingHlc]. If a local edit occurred strictly
     *   after [incomingHlc], [existing] survives.
     * - Incoming upserts must be greater than the local deletion horizon.
     * - Sparse upsert (`isDelete == false`):
     *   - If [tagId] is absent from [changedMask], retains [existing] without modifying the tag index.
     *   - If [tagId] is present in [changedMask], accepts [incoming] and updates the tag index if
     *     the local tag HLC is null or [incomingHlc] > local tag HLC. Otherwise, retains [existing].
     */
    override fun <V> resolve(tagId: Int, incoming: V?, existing: V?): V? {
        if (isDelete) {
            val localTagHlc = initialIndex.getHlc(tagId)
            return if (localTagHlc == null || incomingHlc >= localTagHlc) {
                tagsToUpdate.add(tagId)
                null
            } else {
                existing
            }
        }

        if (!changedMask.hasTag(tagId)) return existing

        if (lastDeleteHlc != null && incomingHlc <= lastDeleteHlc) {
            return existing
        }

        val localTagHlc = initialIndex.getHlc(tagId)
        return if (localTagHlc == null || incomingHlc > localTagHlc) {
            tagsToUpdate.add(tagId)
            incoming
        } else {
            existing
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
                    tagsToUpdate.add(TAG_IS_DELETED)
                    val hasSurvivingField =
                        initialIndex.hasTagNewerThan(incomingHlc, TAG_IS_DELETED)
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
                    logger.i { "Restored [key=$candidateKey]: incoming edit (HLC=$incomingHlc) overrides delete (HLC=$lastDeleteHlc)" }
                    false
                } else {
                    true
                }
            }

            else -> false
        }
    }

    /**
     * Resolves entity creation timestamp:
     * - Retains existing timestamp if already present locally.
     * - Adopts incoming timestamp on initial insert.
     * - Uses `minOf(existing, incoming)` if both exist.
     * - Falls back to [context] HLC timestamp if an out-of-order field delta arrives without an existing record.
     */
    fun resolveCreatedAt(deltaCreatedAt: Long?, existingCreatedAt: Instant?): Instant = when {
        existingCreatedAt == null && deltaCreatedAt != null ->
            Instant.fromEpochMilliseconds(deltaCreatedAt)

        existingCreatedAt != null && deltaCreatedAt == null -> existingCreatedAt

        existingCreatedAt != null && deltaCreatedAt != null -> {
            minOf(existingCreatedAt, Instant.fromEpochMilliseconds(deltaCreatedAt))
        }

        else -> incomingHlc.instant.also { logger.w { "CreatedAt hit fallback of [HLC=$incomingHlc] - ensure not related to server monotonic ordering." } }
    }

    /**
     * Executes an array allocation and copy against the [initialIndex] for all [tagsToUpdate]
     * defined in intermediary resolve method calls.
     */
    fun buildResultBlob(): ByteArray {
        if (tagsToUpdate.isEmpty()) return initialIndex.bytes
        return initialIndex.updateTags(tagsToUpdate, incomingHlc).bytes
    }
}