package com.mochame.server.database

import co.touchlab.kermit.Logger
import com.mochame.server.relay.DeltaWriteIntent
import com.mochame.server.utils.ServerConfig
import com.mochame.server.utils.resolveBackupDirectory
import com.mochame.utils.interfaces.TimeUtils
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.io.files.SystemFileSystem
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.sql.Connection
import java.sql.PreparedStatement
import java.util.Locale
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.path.absolutePathString
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

data class StoredDelta(
    val watermark: Long,
    val payload: ByteArray
)

/**
 * Embedded SQLite storage layer for change-log delta persistence and catch-up queries.
 *
 * Access is partitioned into:
 * - **Writer Pool (`writeDataSource`):** Restricted to exactly 1 connection (`maximumPoolSize = 1`).
 * - **Reader Pool (`readDataSource`):** Scaled to `cores * 2` with `PRAGMA query_only = ON`.
 *
 * Cache sizes are explicitly negative to enforce KiB memory limits rather than page counts:
 * - Writer: -2000 (~2 MB off-heap native C cache)
 * - Readers: -4000 (~4 MB off-heap native C cache per connection)
 * Total off-heap native cache ceiling: `(1 * 2MB) + ((cores * 2) * 4MB)`.
 */
class ServerDatabase(
    path: String,
    private val clock: TimeUtils,
    private val config: ServerConfig = ServerConfig.Default,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO
) : AutoCloseable {

    private val writeDataSource: HikariDataSource
    private val readDataSource: HikariDataSource
    private val url = "jdbc:sqlite:$path"
    private val backupMutex = Mutex()

    init {
        File(path).parentFile?.mkdirs()

        val writeConfig = HikariConfig().apply {
            jdbcUrl = url
            poolName = "MochaMe-SQLite-Writer"
            maximumPoolSize = 1
            minimumIdle = 1
            connectionTimeout = 6000
            connectionInitSql = """
                PRAGMA busy_timeout = 5000;
                PRAGMA synchronous = NORMAL;
                PRAGMA cache_size = -2000;
            """.trimIndent()
        }
        writeDataSource = HikariDataSource(writeConfig)

        initializeSchema()

        val cores = Runtime.getRuntime().availableProcessors()
        val readConfig = HikariConfig().apply {
            jdbcUrl = url
            poolName = "MochaMe-SQLite-Reader"
            maximumPoolSize = (cores * 2).coerceAtLeast(2)
            minimumIdle = 2
            connectionTimeout = 6000
            connectionInitSql = """
                PRAGMA query_only = ON;
                PRAGMA busy_timeout = 5000;
                PRAGMA cache_size = -4000; -- The actual amount of 4B pages
             """.trimIndent()
        }
        readDataSource = HikariDataSource(readConfig)
    }

    internal fun <T> withWriteConnection(block: (Connection) -> T): T =
        writeDataSource.connection.use(block)

    internal fun <T> withReaderConnection(block: (Connection) -> T): T =
        readDataSource.connection.use(block)

    /**
     * Executes initial table migrations and establishes WAL persistence mode.
     * Must be invoked on startup, with PRAGMA usages for persisting flags on the
     * database header.
     */
    private fun initializeSchema() {
        writeDataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("PRAGMA journal_mode = WAL;")
                stmt.execute("PRAGMA wal_autocheckpoint = 1000;")
                stmt.execute("PRAGMA wal_checkpoint(PASSIVE);")
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS sync_change_log (
                        watermark INTEGER PRIMARY KEY AUTOINCREMENT,
                        group_id TEXT NOT NULL,
                        origin_node_id TEXT NOT NULL,
                        payload BLOB NOT NULL,
                        created_at INTEGER NOT NULL
                    );
                    """.trimIndent()
                )
                stmt.execute(
                    """
                    CREATE INDEX IF NOT EXISTS idx_group_watermark 
                    ON sync_change_log(group_id, watermark);
                    """.trimIndent()
                )
            }
        }
    }

    /**
     * Commits a coalesced client of delta intents within a single atomic SQLite transaction.
     *
     * Disables auto-commit to append all records in a single WAL operation,
     * capturing auto-incremented watermarks for each intent.
     *
     * @param intents Coalesced write intents to persist sequentially.
     * @return Monotonically ordered list of assigned watermarks matching intent positions.
     * @throws java.sql.SQLException If the write transaction fails, connection times out,
     *         or the disk is full. On failure, all writes in the client are rolled back.
     */
    suspend fun insertBatch(intents: List<DeltaWriteIntent>): List<Long> = withContext(dispatcher) {
        if (intents.isEmpty()) return@withContext emptyList()

        val sql = """
            INSERT INTO sync_change_log (group_id, origin_node_id, payload, created_at)
            VALUES (?, ?, ?, ?);
        """.trimIndent()

        writeDataSource.connection.use { conn ->
            val wasAutoCommit = conn.autoCommit
            conn.autoCommit = false

            try {
                val watermarks = ArrayList<Long>(intents.size)
                val now = clock.now().toEpochMilliseconds()

                conn.prepareStatement(sql, PreparedStatement.RETURN_GENERATED_KEYS).use { stmt ->
                    for (intent in intents) {
                        stmt.setString(1, intent.groupId)
                        stmt.setString(2, intent.originNodeId)
                        stmt.setBytes(3, intent.rawPayload)
                        stmt.setLong(4, now)
                        stmt.executeUpdate()

                        val rs = stmt.generatedKeys
                        if (rs.next()) {
                            watermarks.add(rs.getLong(1))
                        } else {
                            error("Failed to retrieve generated watermark for intent")
                        }
                    }
                }
                conn.commit()
                watermarks
            } catch (e: Throwable) {
                conn.rollback()
                throw e
            } finally {
                conn.autoCommit = wasAutoCommit
            }
        }
    }

    /**
     * Retrieves a bounded, chronological page of historical deltas for client catch-up on initial connection.
     *
     * Traverses the composite index (`idx_group_watermark`) to perform an O(log N) range seek
     * while filtering out deltas authored by [excludeNodeId] to prevent self-reflection. (Gemini claims, leaving for when returning to this)
     *
     * @param groupId Target sync group partition.
     * @param excludeNodeId Node identifier of the caller to filter out its own authored writes.
     * @param sinceWatermark Lower bound watermark (exclusive) to stream from.
     * @param limit Maximum deltas to return, defaulting to [ServerConfig.maxBackfillChunkSize].
     * @return Ascending list of deltas strictly ordered by watermark.
     * @throws java.sql.SQLException If borrowing a read connection times out or query execution fails.
     */
    suspend fun getDeltasSince(
        groupId: String,
        excludeNodeId: String,
        sinceWatermark: Long,
        limit: Int = config.maxBackfillChunkSize
    ): List<StoredDelta> = withContext(dispatcher) {
        val effectiveLimit = limit.coerceIn(1, config.maxBackfillChunkSize)
        val sql = """
            SELECT watermark, payload 
            FROM sync_change_log 
            WHERE group_id = ? AND watermark > ? AND origin_node_id != ?
            ORDER BY watermark ASC 
            LIMIT ?;
        """.trimIndent()

        readDataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, groupId)
                stmt.setLong(2, sinceWatermark)
                stmt.setString(3, excludeNodeId)
                stmt.setInt(4, effectiveLimit)

                val rs = stmt.executeQuery()
                val results = ArrayList<StoredDelta>(effectiveLimit)
                while (rs.next()) {
                    results.add(
                        StoredDelta(
                            watermark = rs.getLong("watermark"),
                            payload = rs.getBytes("payload")
                        )
                    )
                }
                results
            }
        }
    }

    /**
     * Counts the total number of unobserved historical deltas available for a connecting node.
     *
     * Evaluates backlog volume prior to streaming to determine if a client is too far behind for
     * granular delta sync and must instead be redirected to a state snapshot, protecting the
     * server broadcasting from an intensive backfill.
     *
     * Utilizes the composite index (`idx_group_watermark`) to execute an index-backed scan
     * while excluding self-authored writes via [excludeNodeId].
     *
     * @param groupId Target sync group partition.
     * @param excludeNodeId Node identifier of the caller to filter out its own authored writes.
     * @param sinceWatermark Lower bound watermark (exclusive) to evaluate from.
     * @return Total number of pending deltas awaiting synchronization.
     * @throws java.sql.SQLException If borrowing a read connection times out or query execution fails.
     */
    suspend fun countDeltasSince(
        groupId: String,
        excludeNodeId: String,
        sinceWatermark: Long
    ): Long = withContext(dispatcher) {
        val sql = """
            SELECT COUNT(*) FROM sync_change_log 
            WHERE group_id = ? AND watermark > ? AND origin_node_id != ?;
        """.trimIndent()

        readDataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, groupId)
                stmt.setLong(2, sinceWatermark)
                stmt.setString(3, excludeNodeId)
                val rs = stmt.executeQuery()
                if (rs.next()) rs.getLong(1) else 0L
            }
        }
    }

    /**
     * Retrieves the lowest surviving watermark currently retained for a sync group.
     *
     * Serves as a data integrity check against log compaction: if a connecting client's
     * `sinceWatermark` falls below this value, the server cannot guarantee an unbroken
     * causal history and must reject the client.
     *
     * Index seek against `idx_group_watermark`.
     *
     * @param groupId Target sync group partition.
     * @return Oldest retained watermark in the group, or `null` if the group log is empty.
     * @throws java.sql.SQLException If borrowing a read connection times out or query execution fails.
     */
    suspend fun getMinWatermark(groupId: String): Long? = withContext(dispatcher) {
        val sql = "SELECT MIN(watermark) FROM sync_change_log WHERE group_id = ?;"
        readDataSource.connection.use { conn ->
            conn.prepareStatement(sql).use { stmt ->
                stmt.setString(1, groupId)
                val rs = stmt.executeQuery()
                if (rs.next()) {
                    val minVal = rs.getLong(1)
                    if (rs.wasNull()) null else minVal
                } else null
            }
        }
    }

    /**
     * Prunes expired change-log deltas older than a specified timestamp cutoff in incremental chunks.
     *
     * Executes deletions in iterative sub-transactions to avoid prolonged exclusive write locks.
     * Checks out and releases the single [writeDataSource] connection per chunk and invokes [yield]
     * between iterations, preventing HikariCP pool contention and allowing the write pipeline
     * ([com.mochame.server.relay.DatabaseActor]) to interleave live commits without latency spikes or connection timeouts.
     *
     * @param olderThanEpochMs Cutoff epoch timestamp in milliseconds; records created prior to this are deleted.
     * @param chunkSize Maximum number of records deleted per database transaction, defaulting to [ServerConfig.LOG_PRUNE_CHUNK_SIZE].
     * @return Total number of records deleted across all chunks.
     * @throws java.sql.SQLException If acquiring the write connection or executing the deletion fails.
     */
    suspend fun pruneExpiredDeltas(
        olderThanEpochMs: Long,
        chunkSize: Int = config.logPruneChunkSize
    ): Int = withContext(dispatcher) {
        val sql = """
            DELETE FROM sync_change_log 
            WHERE rowid IN (
                SELECT rowid FROM sync_change_log 
                WHERE created_at < ? 
                LIMIT ?
            );
        """.trimIndent()

        var totalPruned = 0
        while (true) {
            val prunedThisChunk = writeDataSource.connection.use { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setLong(1, olderThanEpochMs)
                    stmt.setInt(2, chunkSize)
                    stmt.executeUpdate()
                }
            }
            totalPruned += prunedThisChunk
            if (prunedThisChunk < chunkSize) break

            yield()
        }
        totalPruned
    }

    /**
     * To execute at most one backup concurrently.
     */
    suspend fun createBackup(targetFile: Path) = backupMutex.withLock {
        targetFile.parent?.createDirectories()

        targetFile.deleteIfExists()

        withWriteConnection { conn ->
            conn.prepareStatement("VACUUM INTO ?").use { stmt ->
                stmt.setString(1, targetFile.absolutePathString())
                stmt.execute()
            }
        }
    }

    override fun close() {
        readDataSource.close()
        writeDataSource.close()
    }
}

fun CoroutineScope.runtimeLogPruning(
    database: ServerDatabase,
    logger: Logger,
    retention: Duration,
    interval: Duration,
    clock: TimeUtils
): Job = launch(CoroutineName("DatabasePruner")) {
    logger.i { "Runtime log pruning job starting..." }

    while (isActive) {
        try {
            val cutoff = clock.now() - retention
            val pruned = database.pruneExpiredDeltas(cutoff.toEpochMilliseconds())
            logger.i { "Pruned: $pruned" }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(e) { "Log compaction task failed. Will retry on interval..." }
        }
        delay(interval)
    }
}


fun CoroutineScope.launchBackupSchedule(
    database: ServerDatabase,
    backupDir: Path = resolveBackupDirectory(),
    interval: Duration = 1.days,
    logger: Logger
) = launch(CoroutineName("DatabaseBackupSchedule")) {
        while (isActive) {
            logger.i { "Automated database backup scheduled..." }

            delay(interval)

            val timestamp = Clock.System.now().toEpochMilliseconds()
            val tempBackup = backupDir.resolve("mochame_backup_$timestamp.tmp")
            val targetBackup = backupDir.resolve("mochame_backup_latest.db")

            try {
                database.createBackup(tempBackup)

                Files.move(
                    tempBackup,
                    targetBackup,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )

                logger.i { "Successfully backup to: $targetBackup" }
            } catch (ce: CancellationException) {
                Files.deleteIfExists(tempBackup)
                throw ce
            } catch (e: Exception) {
                logger.e(e) { "Automated database backup failed" }
                Files.deleteIfExists(tempBackup)
            }
        }
    }
