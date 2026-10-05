package com.mochame.sync.spi.orchestration

import kotlinx.coroutines.Job

/**
 * Contract for orchestrating and observing sync operations.
 */
interface SyncCoordinator {
    /**
     * Starts listening for sync trigger signals and processes outbound intent batches.
     */
    fun startOutboundListener(): Job

    /**
     * Processes the pending sync intent queue until exhausted.
     */
    suspend fun processQueueUntilExhausted()

    /**
     * Ingests and processes raw inbound bytes received from the remote sync server.
     */
    suspend fun handleInboundBytes(watermark: Long, inbound: ByteArray)

    suspend fun handleInboundAck(batchId: Long, watermark: Long)

    suspend fun abortInFlightBatch(exception: Exception)
}