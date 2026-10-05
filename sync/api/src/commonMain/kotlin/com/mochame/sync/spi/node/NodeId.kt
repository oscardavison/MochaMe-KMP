package com.mochame.sync.spi.node

import com.mochame.sync.api.exceptions.MochaException
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline
import kotlin.uuid.Uuid

/**
 * Strongly-typed domain wrapper around [Uuid] for node identification.
 *
 * This can be deleted if no other UUIDs are ever used in the system.
 */
@Serializable
@JvmInline
value class NodeId(val value: Uuid) : Comparable<NodeId> {

    override fun toString(): String = value.toString()

    override fun compareTo(other: NodeId): Int = value.compareTo(other.value)

    companion object {
        val ZERO: NodeId = NodeId(Uuid.fromLongs(0L, 0L))

        fun random(): NodeId = NodeId(Uuid.random())

        fun parse(str: String): NodeId = try {
            NodeId(Uuid.parse(str))
        } catch (e: Exception) {
            throw MochaException.Persistent.HlcParseException("Invalid NodeId UUID string: '$str'. ${e.message}")
        }
    }
}