package com.mochame.sync.infrastructure.stores

import co.touchlab.kermit.Logger
import com.mochame.annotations.BlobMutex
import com.mochame.annotations.CommittedDir
import com.mochame.annotations.IoContext
import com.mochame.annotations.PendingDir
import com.mochame.logger.LogTags
import com.mochame.logger.withTags
import com.mochame.logger.withTimer
import com.mochame.sync.api.exceptions.MochaException
import com.mochame.sync.api.exceptions.toMochaException
import com.mochame.sync.domain.stores.BlobStore
import com.mochame.sync.spi.infrastructure.DigestFactory
import com.mochame.sync.spi.infrastructure.digestHex
import com.mochame.utils.interfaces.TimeUtils
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import org.koin.core.annotation.Single
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.TimeSource


@Single(binds = [BlobStore::class])
internal class DefaultBlobStore(
    private val timeUtils: TimeUtils,
    private val digestFactory: DigestFactory,
    private val fileSystem: FileSystem,
    private val maxStagingAge: Duration = DEFAULT_STALE_AGE,
    @IoContext private val ioContext: CoroutineContext,
    @PendingDir private val pendingDir: Path,
    @CommittedDir private val committedDir: Path,
    @BlobMutex private val blobMutex: Mutex,
    logger: Logger
) : BlobStore {

    private val logger =
        logger.withTags(LogTags.Layer.INFRA, LogTags.Domain.SYNC, "BlobSt")

    companion object {
        val DEFAULT_STALE_AGE = 1.hours
        private val BLOB_HASH_REGEX = Regex("^[a-fA-F0-9]{64}$")
    }

    private val directoriesVerified = atomic(false)
    private val stagingCounter = atomic(0L)

    // --- STAGING ---

    /**
     * Acting upon abstraction of the platform file system, a unique path is established
     * as a temporary directory. A buffered write process begins which reads the source,
     * generates a fingerprint of its contents at the same time as writing the source to that
     * path. The fingerprint is used to establish a final path for staged sources, before
     * atomically moving the contents to that finalized path.
     *
     * Chunk size on my laptop:
     *
     * ```kotlin
     * sudo nvme id-ns -H /dev/nvme0n1 | grep -E "Data Size|Relative Performance"
        LBA Format  0 : Metadata Size: 0   bytes - Data Size: 512 bytes - Relative Performance: 0x2 Good (in use)
        LBA Format  1 : Metadata Size: 0   bytes - Data Size: 4096 bytes - Relative Performance: 0x1 Better
     ```
     * To check: kernel read-ahead behavior, memory pages, and L1 caching.
     *
     * @return blobId representing the fingerprint of the source (its bytes).
     */
    override suspend fun stage(source: Source): String = withContext(ioContext) {
        val mark = TimeSource.Monotonic.markNow()
        val uniqueSequence = stagingCounter.incrementAndGet()
        val now = timeUtils.now().toEpochMilliseconds()
        val tempPath =
            Path(pendingDir, "staging_${now}_${uniqueSequence}_${Random.nextLong().toString(16)}")
        val digest = digestFactory()
        var totalBytes = 0L

        ensureDirectoriesExist()

        try { // Thread local buffer provider?
            // Read the source, write to sink, through a buffer
            fileSystem.sink(tempPath).buffered().use { sink ->
                val buffer = Buffer()
                while (source.readAtMostTo(buffer, 8 * 1024) != -1L) {
                    val byteCount = buffer.size
                    digest.update(buffer.peek())
                    sink.write(buffer, byteCount)
                    totalBytes += byteCount
                }
            }

            // Create unique identity for this staging
            val blobId = digest.digestHex()
            val finalPendingPath = Path(pendingDir, blobId)

            // Deduplication Check
            if (fileSystem.exists(finalPendingPath)) {
                logger.v { "Deduplication: Blob $blobId already staged. Deleting temp." }
                fileSystem.delete(tempPath)
            } else {
                fileSystem.atomicMove(tempPath, finalPendingPath)
                logger.d { "Blob Staged | ID: $blobId | Size: ${totalBytes / 1024}KB".withTimer(mark) }
            }

            blobId
        } catch (e: Exception) {
            if (fileSystem.exists(tempPath)) fileSystem.delete(tempPath)
            throw e.toMochaException("Blob Staging: ${e.message}")
        }
    }

    override suspend fun commit(blobId: String) = withContext(ioContext) {
        ensureDirectoriesExist()
        val pendingPath = Path(pendingDir, blobId)
        val committedPath = Path(committedDir, blobId)

        try {
            if (fileSystem.exists(pendingPath)) {
                fileSystem.atomicMove(pendingPath, committedPath)
                logger.v { "Blob Committed | ID: $blobId" }
            } else if (!fileSystem.exists(committedPath)) {
                logger.w { "Commit Failed: Blob $blobId not found in pending chamber." }
            }
        } catch (e: Exception) {
            if (!fileSystem.exists(committedPath)) throw e.toMochaException("Blob Commit")
                .also {
                    logger.w(e) { "Possible race condition encountered on a commit? ${e.message}" }
                }
        }
    }

    override suspend fun abort(blobId: String) = withContext(ioContext) {
        ensureDirectoriesExist()
        val abortPath = Path(pendingDir, blobId)

        if (fileSystem.exists(abortPath)) {
            fileSystem.delete(abortPath)
            logger.d { "Blob Aborted | ID: $blobId" }
        }
    }

    // --- ADMIN ---

    /**
     * Returns a list necessary for reconciliation.
     * These are success stages that failed to atomically transition from
     * pending to committed directories, but are not orphaned. Therefore,
     * a retry attempt is possible.
     */
    override suspend fun listPendingHashes(): List<String> = withContext(ioContext) {
        ensureDirectoriesExist()

        try {
            fileSystem.list(pendingDir)
                .asSequence()
                .map { it.name }
                .filter { name ->
                    !name.startsWith("staging_") && BLOB_HASH_REGEX.matches(name)
                }
                .toList()
        } catch (e: Exception) {
            logger.e(e) { "Failed to scan pending chamber for hashes." }
            emptyList()
        }
    }

    /**
     * Implementation for clearing aborted/crashed write attempts.
     * Enforces a 1-hour restraint to prevent race conditions with active staging.
     */
    override suspend fun clearIncompleteStaging(): Int = withContext(ioContext) {
        ensureDirectoriesExist()
        var deletedCount = 0
        val now = timeUtils.now().toEpochMilliseconds()

        val pendingFiles = try {
            fileSystem.list(pendingDir)
        } catch (e: Exception) {
            logger.e(e) { "Failed to list pending directory for staging purge." }
            return@withContext 0
        }

        pendingFiles.forEach { path ->
            val name = path.name
            if (!name.startsWith("staging_")) return@forEach

            val parts = name.split("_")
            val fileTimestamp = parts.getOrNull(1)?.toLongOrNull() ?: 0L
            val age = now - fileTimestamp

            if (age > maxStagingAge.inWholeMilliseconds) {
                try {
                    fileSystem.delete(path)
                    deletedCount++
                    logger.v { "Purged stale staging file [${name}]" }
                } catch (e: Exception) {
                    logger.w(e) { "Failed to purge individual stale file [${name}]: ${e.message}" }
                }
            }
        }

        if (deletedCount > 0) {
            logger.i { "Maintenance Complete: Purged $deletedCount stale staging files." }
        }
        deletedCount
    }

    // --- READ ACCESS ---

    override suspend fun existsInCommitted(blobId: String): Boolean =
        withContext(ioContext) {
            ensureDirectoriesExist()
            val path = Path(committedDir, blobId)
            fileSystem.exists(path)
        }

    override suspend fun existsInPending(blobId: String): Boolean =
        withContext(ioContext) {
            ensureDirectoriesExist()
            val path = Path(pendingDir, blobId)
            fileSystem.exists(path)
        }

    override suspend fun open(blobId: String): Source = withContext(ioContext) {
        ensureDirectoriesExist()

        val path = Path(committedDir, blobId)

        if (!fileSystem.exists(path)) {
            throw MochaException.Transient.FileNotFound(blobId)
        }

        // Returns a RawSource wrapped in a buffered Source.
        fileSystem.source(path).buffered()
    }

    // --- Helpers ---
    private suspend fun ensureDirectoriesExist() {
        if (directoriesVerified.value) return

        withContext(ioContext) {
            blobMutex.withLock {
                if (directoriesVerified.value) return@withLock

                try {
                    if (!fileSystem.exists(pendingDir)) {
                        fileSystem.createDirectories(pendingDir)
                        check(fileSystem.exists(pendingDir)) {
                            "Failed to create pending directory at $pendingDir"
                        }
                        logger.i { "Init Pending Dir at $pendingDir" }
                    }
                    if (!fileSystem.exists(committedDir)) {
                        fileSystem.createDirectories(committedDir)
                        check(fileSystem.exists(committedDir)) {
                            "Failed to create committed directory at $committedDir"
                        }
                        logger.i { "Init Committed Dir at $committedDir" }
                    }
                    directoriesVerified.value = true
                } catch (e: Exception) {
                    throw e.toMochaException("Directory Initialization")
                }
            }
        }
    }
}