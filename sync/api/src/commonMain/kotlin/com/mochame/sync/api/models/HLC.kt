package com.mochame.sync.api.models

import com.mochame.sync.api.exceptions.MochaException
import com.mochame.sync.spi.node.NodeId
import kotlinx.serialization.Serializable
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.uuid.Uuid

/**
 * A Hybrid Logical Clock (HLC) timestamp that provides strict ordering across distributed nodes.
 *
 * Serialized format: "ts:count:nodeId"
 *
 * @property ts Wall-clock time in milliseconds.
 * @property count Logical counter used to distinguish events occurring in the same millisecond.
 * @property nodeId Unique identifier for the originating device to prevent collisions.
 */
@Serializable
data class HLC(
    val ts: Long,
    val count: Int,
    val nodeId: NodeId,
) : Comparable<HLC> {

    /**
     * Converts the HLC to its sortable string representation.
     * * TS: 15 digits
     * * Count: 5 digits (maps to MAX_COUNTER 65535)
     */
    override fun toString(): String {
        val paddedTs = ts.toString().padStart(15, '0')
        val paddedCount = count.toString(16).uppercase().padStart(4, '0')
        return "$paddedTs:$paddedCount:$nodeId"
    }

    /**
     * 1. Physical Timestamp ([ts])
     * 2. Logical Counter ([count])
     * 3. Device Identifier ([nodeId])
     */
    override fun compareTo(other: HLC): Int {
        val tsCmp = ts.compareTo(other.ts)
        if (tsCmp != 0) return tsCmp

        val countCmp = count.compareTo(other.count)
        if (countCmp != 0) return countCmp

        return nodeId.compareTo(other.nodeId)
    }

    companion object {
        /**
         * Safely parses a serialized HLC string.
         * @throws MochaException.Persistent.HlcParseException if not valid.
         */
        fun parse(hlcString: String): HLC {
            val parts = hlcString.split(":")

            if (parts.size != 3) throw MochaException.Persistent.HlcParseException("Format mismatch: $hlcString")

            val ts = parts[0].toLongOrNull()
                ?: throw MochaException.Persistent.HlcParseException("Invalid timestamp: ${parts[0]}")

            val count = parts[1].takeIf { it.length == 4 }?.toIntOrNull(radix = 16)
                ?: throw MochaException.Persistent.HlcParseException("Invalid counter: ${parts[1]}")

            val nodeId = NodeId.parse(parts[2])

            return HLC(ts, count, nodeId)
        }

        /**
         * Necessary as a Feature state comes down from the UI layer, with no stamp.
         * I'm not sure if this is necessary and may be confusing over null.
         */
        val EMPTY = HLC(0, 0, NodeId.ZERO)

        /**
         * Internal limits
         */
        const val MAX_COUNTER_INT = 65535
        const val MAX_COUNTER_STRING = "FFFF"
        val ONE_DAY = 1.days
        /** Saturday, March 1, 2025 (at 00:00:00 UTC). **/
        val APP_RELEASE_TIME = Instant.fromEpochMilliseconds(1740787200000L)
        val MAX_DRIFT = 60.seconds
    }
}

val HLC.instant: Instant
    get() = Instant.fromEpochMilliseconds(ts)

fun HLC(ts: Instant, count: Int, nodeId: NodeId): HLC =
    HLC(ts.toEpochMilliseconds(), count, nodeId)

fun HLC(ts: Long, count: Int, nodeId: Uuid): HLC =
    HLC(ts, count, NodeId(nodeId))

/*
    Since creating the FieldHlcMap, would probably be good to do something like:

    @JvmInline
value class NodeId(val value: Uuid) {
    override fun toString(): String = value.toString()

    companion object {
        fun random(): NodeId = NodeId(Uuid.random())
        fun parse(str: String): NodeId = NodeId(Uuid.parse(str))
    }
}

 */