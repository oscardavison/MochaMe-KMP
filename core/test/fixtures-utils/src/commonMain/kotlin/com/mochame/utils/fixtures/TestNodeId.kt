package com.mochame.utils.fixtures

import com.mochame.sync.api.models.NodeId
import kotlin.uuid.Uuid

object TestNodeId {

    /** "00000000-0000-0000-0000-000000000001" */
    val A = NodeId(Uuid.fromLongs(0x0000000000000000L, 0x0000000000000001L))

    /** "00000000-0000-0000-0000-000000000002" */
    val B = NodeId(Uuid.fromLongs(0x0000000000000000L, 0x0000000000000002L))

    /** Helper for generating custom sequential node IDs in tests */
    fun sequence(id: Long): NodeId = NodeId(Uuid.fromLongs(0L, id))
}