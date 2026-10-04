package com.mochame.server.relay

import co.touchlab.kermit.Logger
import com.mochame.server.database.ServerDatabase
import com.mochame.server.utils.ServerConfig
import com.mochame.server.utils.ServerLogger
import com.mochame.sync.api.network.WireFrameFactory
import io.ktor.websocket.CloseReason.Codes.INTERNAL_ERROR
import io.ktor.websocket.Frame
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.channels.onClosed
import kotlinx.coroutines.channels.onFailure
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Inbound write request queued from a peer's WebSocket session to the database writer (via
 * [DatabaseActor.writeChannel]).
 *
 * @property groupId Group partition.
 * @property originNodeId Originating peer identifier.
 * @property batchId Client identifier for ACK response. Not persisted server side.
 * @property rawPayload Serialized delta content to persist and broadcast.
 * @property senderHandle Active session handle used to route writes to outbound channels, and call teardowns.
 */
data class DeltaWriteIntent(
    val groupId: String,
    val originNodeId: String,
    val batchId: Long,
    val rawPayload: ByteArray,
    val senderHandle: SessionHandle
)

/**
 * Single-writer component coordinating SQLite writes and outbound broadcasting via channels.
 *
 * Runs sequentially on a dedicated single-threaded dispatcher ([CoroutineDispatcher.limitedParallelism] = 1)
 * to client incoming [DeltaWriteIntent] payloads up to [ServerConfig.maxBroadcastingBatchSize].
 *
 * ##### Concurrency
 * - Inbound delta submissions must be dispatched via [writeChannel].
 * - Batch commits and downstream ACK/broadcast dispatching execute sequentially; no concurrent
 *   database writes occur within this actor, and no suspensions must be triggered or launched on this
 *   context within the broadcast call.
 *
 * ##### Failure
 * - **Write/Commit Failure:** Any database exception ([java.sql.SQLException] or runtime error)
 *   terminates active WebSocket sessions for all affected senders in the client with
 *   [CloseReason.Codes.INTERNAL_ERROR].
 * - **Outbound Channel Capacity:** If a sender's outbound buffer drops an ACK frame, that
 *   specific peer session is terminated to avoid silent state desynchronization. The behavior of the
 *   broadcasting call must fail fast on peer outbound channel capacity issues. The slowest peer
 *   must have no impact on this actor.
 */
class DatabaseActor(
    private val database: ServerDatabase,
    private val relayManager: RelayManager,
    private val logger: Logger = ServerLogger.base.withTag("Db_Actor"),
    private val config: ServerConfig = ServerConfig.Default,
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    /**
     * Ingestion channel for delta writes.
     * Backpressure begins when queue depth exceeds [ServerConfig.writeChannelCapacity] pending intents.
     * CIO workers should call [Channel.send] and suspend its websocket coroutines to enforce backpressure on its connections.
     */
    val writeChannel: SendChannel<DeltaWriteIntent>
        field = Channel<DeltaWriteIntent>(capacity = config.writeChannelCapacity)

    init {
        scope.launch(CoroutineName("DatabaseActor") + dispatcher.limitedParallelism(1)) {
            logger.i { "Starting database actor..." }
            val batch = ArrayList<DeltaWriteIntent>(config.maxBroadcastingBatchSize)

            for (firstIntent in writeChannel) {
                drainChannelIntoBatch(firstIntent, batch)

                try {
                    val watermarks = database.insertBatch(batch)
                    dispatchCommittedBatch(batch, watermarks)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    handleBatchFailure(batch, e)
                } finally {
                    batch.clear()
                }
            }
        }
    }

    private fun drainChannelIntoBatch(
        firstIntent: DeltaWriteIntent,
        batch: MutableList<DeltaWriteIntent>
    ) {
        batch.add(firstIntent)
        while (batch.size < config.maxBroadcastingBatchSize) {
            val next = writeChannel.tryReceive().getOrNull() ?: break
            batch.add(next)
        }
    }

    private fun dispatchCommittedBatch(batch: List<DeltaWriteIntent>, watermarks: List<Long>) {
        logger.d { formatBatchDispatchTable(batch, watermarks) }

        for (i in batch.indices) {
            val intent = batch[i]
            val watermark = watermarks[i]

            sendAck(intent, watermark)
            broadcastDelta(intent, watermark)
        }
    }

    private fun sendAck(intent: DeltaWriteIntent, watermark: Long) {
        val ackFrame = Frame.Binary(
            fin = true,
            data = WireFrameFactory.ack(intent.batchId, watermark)
        )
        intent.senderHandle.outboundChannel.trySend(ackFrame)
            .onClosed { cause ->
                relayManager.terminate(
                    intent.senderHandle,
                    INTERNAL_ERROR,
                    "ACK_SEND_FAILURE: Channel closed/cancelled (${cause?.message})"
                )
            }
            .onFailure { cause ->
                if (cause == null) {
                    relayManager.terminate(
                        intent.senderHandle,
                        INTERNAL_ERROR,
                        "ACK_SEND_FAILURE: Outbound buffer saturated"
                    )
                }
            }
    }

    private fun broadcastDelta(intent: DeltaWriteIntent, watermark: Long) {
        val deltaBytes = WireFrameFactory.delta(watermark, intent.rawPayload)

        relayManager.broadcast(
            groupId = intent.groupId,
            excludeNodeId = intent.originNodeId,
            watermark = watermark,
            data = deltaBytes
        )
    }

    private fun handleBatchFailure(batch: List<DeltaWriteIntent>, error: Exception) {
        val uniqueSenders = batch.map { it.senderHandle }.distinctBy { it.nodeId }
        logger.e(error) {
            """
            |Batch write failed (${error.message}) for the following senders. Terminating connections:
            |${uniqueSenders.joinToString(separator = "\n") { "|  - ${it.nodeId}" }}
            """.trimMargin()
        }

        val failureReason = "DATABASE_BATCH_WRITE_ERROR: ${error.message ?: "Commit failure"}"

        for (sender in uniqueSenders) {
            relayManager.terminate(
                handle = sender,
                code = INTERNAL_ERROR,
                reason = failureReason
            )
        }
    }

    private fun formatBatchDispatchTable(
        batch: List<DeltaWriteIntent>,
        watermarks: List<Long>
    ): String = buildString(
        capacity = 300 + batch.size * 120
    ) {
        appendLine()
        appendLine("┌──────┬──────────────┬──────────────────────┬──────────────────────┬──────────┐")
        appendLine("│ Idx  │ Watermark    │ Batch ID             │ Origin Node          │ Payload  │")
        appendLine("├──────┼──────────────┼──────────────────────┼──────────────────────┼──────────┤")
        for (i in batch.indices) {
            val intent = batch[i]
            val wm = watermarks.getOrNull(i)?.toString() ?: "N/A"

            val idxStr = i.toString().padEnd(4)
            val wmStr = wm.padEnd(12)
            val batchIdStr = intent.batchId.toString().padEnd(20)
            val nodeStr = intent.originNodeId.take(20).padEnd(20)
            val payloadSize = "${intent.rawPayload.size}b".padEnd(8)

            appendLine("│ $idxStr │ $wmStr │ $batchIdStr │ $nodeStr │ $payloadSize │")
        }
        append("└──────┴──────────────┴──────────────────────┴──────────────────────┴──────────┘")
    }
}