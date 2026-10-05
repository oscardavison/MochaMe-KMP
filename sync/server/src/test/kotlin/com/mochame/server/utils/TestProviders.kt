package com.mochame.server.utils

import com.mochame.server.relay.DeltaWriteIntent
import com.mochame.server.relay.SessionHandle
import com.mochame.utils.fixtures.TestPayloads

val SessionHandle.fakeSession: FakeWebSocketSession
    get() = (this.session as? FakeWebSocketSession)
        ?: error("SessionHandle.session is not a FakeWebSocketSession (was: ${this.session::class.simpleName})")

fun createWriteIntent(
    handle: SessionHandle,
    batchId: Long = 1L,
    payload: ByteArray = TestPayloads.DEFAULT
): DeltaWriteIntent = DeltaWriteIntent(
    groupId = handle.groupId,
    originNodeId = handle.nodeId,
    batchId = batchId,
    rawPayload = payload,
    senderHandle = handle
)

fun createWriteIntent(
    groupId: String,
    nodeId: String,
    payload: ByteArray = TestPayloads.DEFAULT,
    batchId: Long = 1L
): DeltaWriteIntent {
    val handle = SessionHandle(nodeId, groupId, FakeWebSocketSession())
    return DeltaWriteIntent(groupId, nodeId, batchId, payload, handle)
}