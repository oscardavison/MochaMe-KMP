package com.mochame.server.relay

import co.touchlab.kermit.Logger
import com.mochame.server.utils.ServerLogger
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import java.util.concurrent.ConcurrentHashMap

/**
 * Registry and router for active peer sessions.
 *
 * Manages group session maps, coordinates broadcast distribution across nodes,
 * and enforces backpressure for slow or unresponsive consumers.
 *
 * ###### Concurrency
 * - Registration, unregistration, and broadcasting lookups are thread-safe,
 *   backed by nested [ConcurrentHashMap] instances.
 * - Outbound delivery does not block; frames are pushed via non-blocking channel enqueues.
 */
class RelayManager(
    private val logger: Logger = ServerLogger.base.withTag("RelayMgr")
) {

    private val groupSessions =
        ConcurrentHashMap<String, ConcurrentHashMap<String, SessionHandle>>()

    /**
     * Registers a session under its [SessionHandle.groupId] and [SessionHandle.nodeId].
     *
     * If an existing session is registered under the same key, it is closed and replaced with [handle].
     *
     * @param handle The session handle to register.
     */
    fun register(handle: SessionHandle) {
        val group = groupSessions.computeIfAbsent(handle.groupId) { ConcurrentHashMap() }
        val previous = group.put(handle.nodeId, handle)

        logger.i { "Registered node '${handle.nodeId}' to group '${handle.groupId}'" }

        previous?.close(CloseReason.Codes.NORMAL, "Replaced by new connection")
    }

    /**
     * Unregisters a session if the currently mapped instance matches [handle] by reference.
     *
     * Closes [handle] regardless of whether it was actively present in the registry.
     */
    fun terminate(handle: SessionHandle, code: CloseReason.Codes, reason: String) {
        groupSessions.computeIfPresent(handle.groupId) { _, group ->
            if (group[handle.nodeId] === handle) {
                group.remove(handle.nodeId)
                logger.i { "Unregistered node '${handle.nodeId}' from group '${handle.groupId}'" }
            }
            if (group.isEmpty()) null else group
        }

        handle.close(code, reason)
    }

    /**
     * Broadcasts a frame to all peers in [groupId] excluding [excludeNodeId].
     *
     * Evaluates backpressure per peer via [SessionHandle.enqueueBroadcast]. If an outbound buffer
     * is at capacity, the attempt fails fast and the peer is terminated with
     * [CloseReason.Codes.TRY_AGAIN_LATER].
     */
    fun broadcast(groupId: String, excludeNodeId: String, watermark: Long, data: ByteArray) {
        val peers = groupSessions[groupId]?.values ?: return

        for (peer in peers) {
            if (peer.nodeId == excludeNodeId) continue
            val frame = Frame.Binary(fin = true, data = data)

            when (val result = peer.enqueueBroadcast(watermark, frame)) {
                EnqueueResult.Success -> {}

                is EnqueueResult.Closed -> {
                    logger.v { "Node '${peer.nodeId}' outbound channel already closed (${result::class.simpleName}). Confirming termination..." }
                    terminate(
                        handle = peer,
                        code = CloseReason.Codes.NORMAL,
                        reason = result.cause?.message ?: "Connection closed"
                    )
                }

                EnqueueResult.StagingSaturated -> {
                    logger.w { "Node '${peer.nodeId}' exceeded staging capacity during backfill. Config may require tuning. Confirming terminating..." }
                    terminate(
                        handle = peer,
                        code = CloseReason.Codes.TRY_AGAIN_LATER,
                        reason = "STAGING_OVERFLOW: Live peer broadcasts exceeded backfill buffer"
                    )
                }

                EnqueueResult.OutboundSaturated -> {
                    logger.w { "Node '${peer.nodeId}' outbound channel saturated. Confirming termination..." }
                    terminate(
                        handle = peer,
                        code = CloseReason.Codes.TRY_AGAIN_LATER,
                        reason = "SLOW_CONSUMER: Outbound channel at capacity"
                    )
                }
            }
        }
    }

    fun hasRegisteredSession(handle: SessionHandle): Boolean =
        groupSessions[handle.groupId]?.get(handle.nodeId) == handle

    /**
     * Returns the underlying active session handle map for [groupId], or null if absent.
     */
    fun getActiveGroup(groupId: String): Map<String, SessionHandle>? {
        val group = groupSessions[groupId] ?: return null
        return if (group.isEmpty()) null else group
    }
}