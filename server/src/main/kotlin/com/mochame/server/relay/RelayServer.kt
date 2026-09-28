package com.mochame.server.relay

import co.touchlab.kermit.Logger
import com.mochame.server.utils.ServerConfig
import com.mochame.server.database.ServerDatabase
import com.mochame.server.database.runtimeLogPruning
import com.mochame.utils.interfaces.TimeUtils
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Lifecycle orchestrator for the sync relay server.
 *
 * Coordinates the startup and orderly shutdown of the embedded Ktor CIO web server,
 * the single-writer [DatabaseActor], session broadcasting via [RelayManager],
 * and recurring SQLite WAL pruning jobs.
 *
 * Implements [AutoCloseable] to provide deterministic teardown across background
 * scopes, network channels, and underlying database connections.
 *
 * @param config Centralized server configuration settings.
 * @param database SQLite database facade managing HikariCP pools and schema operations.
 * @param relayManager Registry tracking active peer sessions and routing broadcast deltas.
 * @param serverScope Root supervisor scope governing background tasks such as the log pruner and database actor.
 * @param logger Structured logger tagged for relay lifecycle logging.
 */
class RelayServer(
    private val config: ServerConfig = ServerConfig.Default,
    private val database: ServerDatabase,
    private val relayManager: RelayManager,
    private val serverScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val clock: TimeUtils,
    private val logger: Logger
) : AutoCloseable {

    val databaseActor = DatabaseActor(
        database = database,
        relayManager = relayManager,
        logger = logger,
        scope = serverScope
    )

    private var pruningJob: Job? = null
    private var engine: EmbeddedServer<*, *>? = null

    /**
     * Boots the background maintenance jobs and starts the embedded HTTP/WebSocket server.
     *
     * Binds the Ktor CIO engine to the configured host and port.
     *
     * @param wait If `true`, blocks the calling thread until the engine shuts down;
     * if `false`, returns immediately after initiating server startup.
     */
    fun start(wait: Boolean = true) {
        pruningJob = serverScope.runtimeLogPruning(
            database = database,
            logger = logger,
            retention = config.logRetentionDuration,
            interval = config.logPruneInterval,
            clock = clock
        )

        engine = embeddedServer(CIO, config.port, config.host) {
            configureSyncRelay(
                database = database,
                relayManager = relayManager,
                databaseActor = databaseActor,
                logger = logger
            )
        }.start(wait = wait)
    }

    /**
     * Executes teardown of all relay subsystems.
     *
     * 1. Cancels the periodic SQLite log compaction job.
     * 2. Cancels [serverScope], terminating the [databaseActor] and active child coroutines.
     * 3. Stops the Ktor CIO server engine with a 1-second grace period and 3-second abort timeout, cancelling websocket instances.
     * 4. Closes the underlying [database] connection pool.
     */
    override fun close() {
        try {
            serverScope.cancel()
            engine?.stop(gracePeriodMillis = 1000, timeoutMillis = 3000)
            database.close()
        } catch (e: Exception) {
            logger.e { "Error during shutdown: ${e.message}" }
            throw e
        }
    }
}