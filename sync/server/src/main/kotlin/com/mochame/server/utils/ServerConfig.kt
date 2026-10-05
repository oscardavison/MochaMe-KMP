package com.mochame.server.utils

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/**
 * Thresholds and chunk sizes, network bindings, channel capacities, and backfill parameters.
 */
interface ServerConfig {
    val port: Int
    val host: String

    val maxBackfillThreshold: Int
    val maxBackfillChunkSize: Int
    val maxBroadcastingBatchSize: Int

    val logPruneChunkSize: Int
    val logRetentionDuration: Duration
    val logPruneInterval: Duration

    val writeChannelCapacity: Int
    val outboundChannelCapacity: Int
    val outboundStagingCapacity: Int

    companion object Default : ServerConfig {
        override val port: Int = 8080
        override val host: String = "0.0.0.0"
        override val maxBackfillThreshold: Int = 1500
        override val maxBackfillChunkSize: Int = 500
        override val maxBroadcastingBatchSize: Int = 50
        override val logPruneChunkSize: Int = 500
        override val logRetentionDuration: Duration = 45.days
        override val logPruneInterval: Duration = 24.hours
        override val writeChannelCapacity: Int = 1000
        override val outboundChannelCapacity: Int = 256
        override val outboundStagingCapacity: Int = 512

        /**
         * Factory operator allowing tests to override specific fields
         * while inheriting defaults for the rest.
         */
        operator fun invoke(
            port: Int = Default.port,
            host: String = Default.host,
            maxBackfillThreshold: Int = Default.maxBackfillThreshold,
            maxBackfillChunkSize: Int = Default.maxBackfillChunkSize,
            maxBroadcastingBatchSize: Int = Default.maxBroadcastingBatchSize,
            logPruneChunkSize: Int = Default.logPruneChunkSize,
            logRetentionDuration: Duration = Default.logRetentionDuration,
            logPruneInterval: Duration = Default.logPruneInterval,
            writeChannelCapacity: Int = Default.writeChannelCapacity,
            outboundChannelCapacity: Int = Default.outboundChannelCapacity,
            outboundStagingCapacity: Int = Default.outboundStagingCapacity,
        ): ServerConfig = ConfigImpl(
            port = port,
            host = host,
            maxBackfillThreshold = maxBackfillThreshold,
            maxBackfillChunkSize = maxBackfillChunkSize,
            maxBroadcastingBatchSize = maxBroadcastingBatchSize,
            logPruneChunkSize = logPruneChunkSize,
            logRetentionDuration = logRetentionDuration,
            logPruneInterval = logPruneInterval,
            writeChannelCapacity = writeChannelCapacity,
            outboundChannelCapacity = outboundChannelCapacity,
            outboundStagingCapacity = outboundStagingCapacity
        )
    }

    private data class ConfigImpl(
        override val port: Int,
        override val host: String,
        override val maxBackfillThreshold: Int,
        override val maxBackfillChunkSize: Int,
        override val maxBroadcastingBatchSize: Int,
        override val logPruneChunkSize: Int,
        override val logRetentionDuration: Duration,
        override val logPruneInterval: Duration,
        override val writeChannelCapacity: Int,
        override val outboundChannelCapacity: Int,
        override val outboundStagingCapacity: Int,
    ) : ServerConfig
}