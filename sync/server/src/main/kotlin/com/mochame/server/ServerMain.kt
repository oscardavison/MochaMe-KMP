package com.mochame.server

import com.mochame.server.utils.ServerConfig
import com.mochame.server.utils.ServerLogger
import com.mochame.server.database.ServerDatabase
import com.mochame.server.relay.RelayManager
import com.mochame.server.relay.RelayServer
import com.mochame.server.utils.resolveBackupDirectory
import com.mochame.utils.implementations.DefaultTimeUtils
import java.io.File
import java.nio.file.Path


fun main() {
    val logger = ServerLogger.base.withTag("RelaySvr")
    val clock = DefaultTimeUtils()

    val dbPath: String = System.getenv("SQLITE_DB_PATH")
        ?: "${System.getProperty("user.home")}/.mochame/sync_server.db"
    val backupDir: Path = resolveBackupDirectory()

    File(dbPath).parentFile?.mkdirs()
    val host: String = System.getenv("SERVER_HOST") ?: "0.0.0.0"
    val port: Int = System.getenv("SERVER_PORT")?.toIntOrNull() ?: 8080

    val database = ServerDatabase(dbPath, clock)
    val relayManager = RelayManager(logger)

    val server = RelayServer(
        database = database,
        relayManager = relayManager,
        logger = logger,
        clock = clock,
        backupDir = backupDir,
        config = ServerConfig.Default(port = port, host = host)
    )

    Runtime.getRuntime().addShutdownHook(Thread {
        server.close()
        logger.i { "Shutdown signal received. Closed Relay Server." }
    })

    logger.i { "Starting Relay Server on ${ServerConfig.host}:${ServerConfig.port} (DB: $dbPath)" }
    server.start(wait = true)
}