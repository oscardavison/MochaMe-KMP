package com.mochame.server.relay

import co.touchlab.kermit.Logger
import com.mochame.server.database.ServerDatabase
import com.mochame.server.utils.ServerConfig
import com.mochame.sync.protocol.WireFrame
import com.mochame.sync.protocol.WireFrameFactory
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.routing.Route
import io.ktor.server.routing.routing
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.CloseReason.Codes.INTERNAL_ERROR
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import kotlinx.coroutines.yield
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

/**
 * Installs WebSocket server transport features and registers the sync relay routes.
 */
fun Application.configureSyncRelay(
    database: ServerDatabase,
    relayManager: RelayManager,
    databaseActor: DatabaseActor,
    logger: Logger
) {
    install(WebSockets) {
        pingPeriod = 15.seconds
        timeout = 30.seconds
        maxFrameSize = Long.MAX_VALUE
        masking = false
    }

    routing {
        syncRelayRoute(database, relayManager, databaseActor, logger)
    }
}

/**
 * Route definition for real-time peer synchronization and historical delta catch-up.
 *
 * Endpoint: `/sync/{groupId}/{nodeId}?since={watermark}`
 *
 * ###### Lifecycle Pipeline:
 * * Handshake & Parameter Extraction: Validates path variables and query parameters.
 * * State Guards: Verifies requested *since* watermark has not been pruned. Rejects clients whose lag exceeds snapshot thresholds.
 * * Backfill Streaming: Chunks and streams historical deltas sequentially.
 * * Live Transition: Flushes backfill buffers and enables live peer synchronization.
 * * Inbound Lifecycle: Forwards binary frames to the single-writer [DatabaseActor].
 *
 *
 * ###### Manual Session Teardown
 * * Slow Consumer / Saturated OutboundChannel: [RelayManager.broadcast] detects that
 *   [handle.enqueueBroadcast] returned [OutboundSaturated] or [StagingSaturated].
 *   It calls terminate, which invokes [handle.close].
 *
 * * Duplicate Peer connection: A new socket connects claiming nodeId = "A",
 *   but [RelayManager] already has an active [SessionHandle] for "A".
 *   The manager evicts the old stale handle by calling oldHandle.close(Normal, "Replaced by new connection").
 *
 * * Deserialization Violation: The sessions inbound stream consumer gets invalid bytes or
 *   invalid opcodes and terminates the session.
 */
fun Route.syncRelayRoute(
    database: ServerDatabase,
    relayManager: RelayManager,
    databaseActor: DatabaseActor,
    logger: Logger,
    config: ServerConfig = ServerConfig.Default
) {
    webSocket("/sync/{groupId}/{nodeId}") {
        val groupId = call.parameters["groupId"] ?: return@webSocket close()
        val nodeId = call.parameters["nodeId"] ?: return@webSocket close()
        val sinceWatermark = call.request.queryParameters["since"]?.toLongOrNull() ?: 0L

        val minWatermark = database.getMinWatermark(groupId)
        if (sinceWatermark > 0 && minWatermark != null && sinceWatermark < minWatermark) {
            logger.w { "Node '$nodeId' watermark $sinceWatermark is older than min retained: $minWatermark" }
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "DELTA_HISTORY_EXPIRED"))
            return@webSocket
        }

        val pendingDeltaCount = database.countDeltasSince(groupId, nodeId, sinceWatermark)
        if (pendingDeltaCount > config.maxBackfillThreshold) {
            logger.w { "Node '$nodeId' backlog ($pendingDeltaCount) exceeds limit (${config.maxBackfillThreshold})." }
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "SNAPSHOT_REQUIRED"))
            return@webSocket
        }

        val sessionHandle = SessionHandle(nodeId, groupId, this, config)

        try {
            relayManager.register(sessionHandle)

            val backfilledSuccessfully = streamBackfill(
                sessionHandle = sessionHandle,
                database = database,
                sinceWatermark = sinceWatermark,
                config = config,
                logger = logger
            )

            if (!backfilledSuccessfully) {
                relayManager.terminate(
                    handle = sessionHandle,
                    code = INTERNAL_ERROR,
                    reason = "BACKFILL_FAILURE"
                )
            }

            consumeInboundStream(
                sessionHandle = sessionHandle,
                databaseActor = databaseActor,
                logger = logger
            )
        } catch (e: CancellationException) {
            logger.i { "Session cancelled for node '$nodeId'" }
            throw e
        } catch (e: Exception) {
            logger.d { "Closing session for node '$nodeId' in group '$groupId':${e.message}" }
            relayManager.terminate(sessionHandle, INTERNAL_ERROR, e.message ?: "Unhandled error")
        } finally {
            relayManager.terminate(sessionHandle, CloseReason.Codes.NORMAL, "Closed connection")
        }
    }
}

/**
 * Paginates historical deltas from SQLite and delivers them to the peer's outbound channel,
 * culminating in the live broadcast transition handshake.
 *
 * @return `true` if backfill succeeded and the session is live; `false` if the connection dropped or failed.
 */
private suspend fun streamBackfill(
    sessionHandle: SessionHandle,
    database: ServerDatabase,
    sinceWatermark: Long,
    config: ServerConfig,
    logger: Logger
): Boolean {
    var currentWatermark = sinceWatermark
    var maxSeenWatermark = sinceWatermark
    var totalBackfilledCount = 0

    while (true) {
        val chunk = database.getDeltasSince(
            groupId = sessionHandle.groupId,
            excludeNodeId = sessionHandle.nodeId,
            sinceWatermark = currentWatermark,
            limit = config.maxBackfillChunkSize
        )

        if (chunk.isEmpty()) break

        for (delta in chunk) {
            sessionHandle.outboundChannel.send(
                Frame.Binary(
                    fin = true,
                    data = WireFrameFactory.delta(delta.watermark, delta.payload)
                )
            )
            if (delta.watermark > maxSeenWatermark) {
                maxSeenWatermark = delta.watermark
            }
        }

        currentWatermark = maxSeenWatermark
        totalBackfilledCount += chunk.size

        if (chunk.size < config.maxBackfillChunkSize) break
        yield()
    }

    if (totalBackfilledCount > 0) {
        logger.i { "Backfill complete for node '${sessionHandle.nodeId}': streamed $totalBackfilledCount deltas" }
    }

    sessionHandle.outboundChannel.send(
        Frame.Binary(fin = true, data = WireFrameFactory.backfillComplete())
    )

    return when (val result = sessionHandle.completeBackfill(maxSeenWatermark)) {
        is BackfillResult.Success -> {
            logger.v { "Node '${sessionHandle.nodeId}' backfill complete" }
            true
        }

        is BackfillResult.ChannelClosed -> {
            logger.i { "Node '${sessionHandle.nodeId}' disconnected during backfill completion." }
            false
        }

        is BackfillResult.Failure -> {
            logger.e(result.cause) { "Backfill error for node '${sessionHandle.nodeId}': (${result::class.simpleName})" }
            false
        }
    }
}

/**
 * Loops over incoming WebSocket frames, validates header layouts, and enqueues intents
 * to the [DatabaseActor].
 */
private suspend fun DefaultWebSocketServerSession.consumeInboundStream(
    sessionHandle: SessionHandle,
    databaseActor: DatabaseActor,
    logger: Logger
) {
    for (frame in incoming) {
        if (frame !is Frame.Binary) continue

        val wireBytes = frame.data

        if (wireBytes.size < 9) {
            logger.w { "Header metadata unparsable on frame from '${sessionHandle.nodeId}' (${wireBytes.size}b). Discarding." }
            sessionHandle.close(
                CloseReason.Codes.PROTOCOL_ERROR,
                "Expected >= 9 Bytes [0x04][batchId: 8B]"
            )
            continue
        }

        val clientSubmission = try {
            WireFrameFactory.unwrap(wireBytes) as WireFrame.ClientSubmit
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.w(e) { "Invalid frame from '${sessionHandle.nodeId}' (${wireBytes.size}b): ${e.message}. Discarding." }
            continue
        }

        val intent = DeltaWriteIntent(
            groupId = sessionHandle.groupId,
            originNodeId = sessionHandle.nodeId,
            batchId = clientSubmission.batchId,
            rawPayload = clientSubmission.payload,
            senderHandle = sessionHandle
        )

        databaseActor.writeChannel.send(intent)
    }
}