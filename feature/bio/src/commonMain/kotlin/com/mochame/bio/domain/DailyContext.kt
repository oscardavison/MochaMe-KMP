package com.mochame.bio.domain

import com.mochame.sync.api.models.HLC
import com.mochame.sync.api.models.LocalFirstEntity
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Represents the biological capacity and readiness for a single day.
 * This acts as the framing context for all telemetry logged during this period.
 */
data class DailyContext(
    override val id: Long, // The Epoch Day
    override val hlc: HLC = HLC.EMPTY,
    override val isDeleted: Boolean = false,
    override val createdAt: Instant = Clock.System.now(),
    override val lastModified: Long = 0L,
    override val fieldHlcs: ByteArray = ByteArray(0),
    val sleepHours: Double? = null,
    val readinessScore: Int? = null,
    val isNapped: Boolean? = null,
    val notes: String? = null
) : LocalFirstEntity<DailyContext> {

    override fun withHlcMetadata(hlc: HLC, fieldBlob: ByteArray): DailyContext =
        copy(hlc = hlc, lastModified = hlc.ts, fieldHlcs = fieldBlob)

    override fun withDeleteState(isDeleted: Boolean): DailyContext = if (isDeleted) {
        copy(
            isDeleted = true,
            sleepHours = null,
            readinessScore = null,
            isNapped = null,
            notes = null
        )
    } else {
        copy(isDeleted = false)
    }

    override fun withSyncHeader(
        hlc: HLC,
        lastModified: Long,
        createdAt: Instant,
        isDeleted: Boolean,
        fieldHlcs: ByteArray
    ): DailyContext = copy(
        hlc = hlc,
        lastModified = lastModified,
        createdAt = createdAt,
        isDeleted = isDeleted,
        fieldHlcs = fieldHlcs
    )

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DailyContext) return false
        return id == other.id &&
                hlc == other.hlc &&
                isDeleted == other.isDeleted &&
                createdAt == other.createdAt &&
                lastModified == other.lastModified &&
                fieldHlcs.contentEquals(other.fieldHlcs) &&
                sleepHours == other.sleepHours &&
                readinessScore == other.readinessScore &&
                isNapped == other.isNapped &&
                notes == other.notes
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + hlc.hashCode()
        result = 31 * result + isDeleted.hashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + lastModified.hashCode()
        result = 31 * result + fieldHlcs.contentHashCode()
        result = 31 * result + sleepHours.hashCode()
        result = 31 * result + readinessScore.hashCode()
        result = 31 * result + isNapped.hashCode()
        result = 31 * result + notes.hashCode()
        return result
    }
}