package com.mochame.sync.internal.fixtures.infrastructure

import co.touchlab.kermit.Logger
import com.mochame.annotations.BlobMutex
import com.mochame.annotations.CommittedDir
import com.mochame.annotations.IoContext
import com.mochame.annotations.PendingDir
import com.mochame.sync.domain.stores.BlobStore
import com.mochame.sync.infrastructure.stores.DefaultBlobStore
import com.mochame.sync.infrastructure.stores.DefaultBlobStore.Companion.DEFAULT_STALE_AGE
import com.mochame.sync.spi.DigestFactory
import com.mochame.utils.interfaces.TimeUtils
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.sync.Mutex
import kotlinx.io.Source
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration

/**
 * Tracks operations and allows error injection
 * while delegating calls to an underlying [BlobStore] implementation.
 */
internal class SpyBlobStore(
    timeUtils: TimeUtils,
    digestFactory: DigestFactory,
    fileSystem: FileSystem,
    maxStagingAge: Duration = DEFAULT_STALE_AGE,
    @IoContext ioContext: CoroutineContext,
    @PendingDir pendingDir: Path,
    @CommittedDir committedDir: Path,
    @BlobMutex blobMutex: Mutex,
    logger: Logger
) : BlobStore {

    private val delegate = DefaultBlobStore(
        timeUtils = timeUtils,
        digestFactory = digestFactory,
        fileSystem = fileSystem,
        maxStagingAge = maxStagingAge,
        ioContext = ioContext,
        pendingDir = pendingDir,
        committedDir = committedDir,
        blobMutex = blobMutex,
        logger = logger
    )
    private val _stageCallCount = atomic(0)
    private val _commitCallCount = atomic(0)
    private val _abortCallCount = atomic(0)
    private val _openCallCount = atomic(0)

    private val _stageError = atomic<Exception?>(null)
    private val _commitError = atomic<Exception?>(null)
    private val _generalError = atomic<Exception?>(null)

    // --- Telemetry Properties ---

    val stageCallCount: Int get() = _stageCallCount.value
    val commitCallCount: Int get() = _commitCallCount.value
    val abortCallCount: Int get() = _abortCallCount.value
    val openCallCount: Int get() = _openCallCount.value

    var stageError: Exception?
        get() = _stageError.value
        set(value) { _stageError.value = value }

    var commitError: Exception?
        get() = _commitError.value
        set(value) { _commitError.value = value }

    var generalError: Exception?
        get() = _generalError.value
        set(value) { _generalError.value = value }

    // --- BlobStore Operations ---

    override suspend fun stage(source: Source): String {
        _stageError.value?.let { throw it }
        _stageCallCount.incrementAndGet()
        return delegate.stage(source)
    }

    override suspend fun commit(blobId: String) {
        _commitError.value?.let { throw it }
        _commitCallCount.incrementAndGet()
        delegate.commit(blobId)
    }

    override suspend fun abort(blobId: String) {
        _generalError.value?.let { throw it }
        _abortCallCount.incrementAndGet()
        delegate.abort(blobId)
    }

    override suspend fun listPendingHashes(): List<String> {
        _generalError.getAndSet(null)?.let { throw it }
        return delegate.listPendingHashes()
    }

    override suspend fun clearIncompleteStaging(): Int {
        _generalError.getAndSet(null)?.let { throw it }
        return delegate.clearIncompleteStaging()
    }

    override suspend fun existsInCommitted(blobId: String): Boolean {
        return delegate.existsInCommitted(blobId)
    }

    override suspend fun existsInPending(blobId: String): Boolean {
        return delegate.existsInPending(blobId)
    }

    override suspend fun open(blobId: String): Source {
        _generalError.value?.let { throw it }
        _openCallCount.incrementAndGet()
        return delegate.open(blobId)
    }

    // --- Test Helpers ---

    /**
     * Resets invocation counters and injected failure triggers.
     */
    fun reset() {
        _stageCallCount.value = 0
        _commitCallCount.value = 0
        _abortCallCount.value = 0
        _openCallCount.value = 0
        _stageError.value = null
        _commitError.value = null
        _generalError.value = null
    }
}