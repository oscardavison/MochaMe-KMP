package com.mochame.server

import com.mochame.server.utils.ServerConfig
import com.mochame.server.utils.ServerLogger
import com.mochame.server.database.ServerDatabase
import com.mochame.server.database.dbPath
import com.mochame.server.relay.RelayManager
import com.mochame.server.relay.RelayServer
import com.mochame.utils.implementations.DefaultTimeUtils


fun main() {
    val logger = ServerLogger.base.withTag("RelaySvr")
    val clock = DefaultTimeUtils()

    val resolvedDbPath = System.getenv("MOCHAME_DB_PATH") ?: dbPath
    val database = ServerDatabase(resolvedDbPath, clock)
    val relayManager = RelayManager(logger)

    val server = RelayServer(
        database = database,
        relayManager = relayManager,
        logger = logger,
        clock = clock
    )

    Runtime.getRuntime().addShutdownHook(Thread {
        server.close()
        logger.i { "Shutdown signal received. Closed Sync Relay Server." }
    })

    logger.i { "Starting Relay Server on ${ServerConfig.host}:${ServerConfig.port} (DB: $resolvedDbPath)" }
    server.start(wait = true)
}