@file:OptIn(ExperimentalKermitApi::class)

package com.mochame.sync.infrastructure.stores

import co.touchlab.kermit.ExperimentalKermitApi
import co.touchlab.kermit.Severity
import com.mochame.platform.fixtures.di.deleteRecursively
import com.mochame.support.MochaPlatformTest
import com.mochame.support.runUnitEnvironment
import com.mochame.sync.api.exceptions.MochaException
import com.mochame.sync.di.infrastructure.BlobStoreTestEnv
import com.mochame.sync.di.infrastructure.SyncBlobStoreTestModule
import com.mochame.utils.fixtures.TestPayloads
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.TestScope
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.readByteArray
import org.koin.plugin.module.dsl.modules
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

// -----------------------------------------------------------
// SUT ENVIRONMENT
// -----------------------------------------------------------

private inline fun runEnv(crossinline block: suspend BlobStoreTestEnv.(TestScope) -> Unit) =
    runUnitEnvironment<BlobStoreTestEnv>(
        koinSetup = { modules(SyncBlobStoreTestModule::class) },
        block = block
    )


internal class DefaultBlobStoreTest : MochaPlatformTest() {

    // -----------------------------------------------------------
    // STAGING - DEDUPLICATION / CONTENTION / CLEANUP
    // -----------------------------------------------------------
    @Test
    fun should_stagePayloadToPending_when_stagingNewSource() = runEnv {
        // Arrange
        val source = TestPayloads.defaultSource()

        // Act
        val blobId = store.stage(source)

        // Assert
        assertTrue(
            store.existsInPending(blobId),
            "Expected blob $blobId to exist in pending directory"
        )
        assertEquals(
            1,
            digestFactory.invokeCount,
            "Digest factory should be invoked exactly once per stage operation"
        )
        assertEquals(
            listOf(blobId),
            store.listPendingHashes(),
            "Pending directory should contain exactly the newly staged blob ID"
        )
    }

    @Test
    fun should_deduplicateAndRetainOriginal_when_stagingIdenticalPayload() = runEnv {
        // Arrange
        val firstSource = TestPayloads.defaultSource()
        val secondSource = TestPayloads.defaultSource()

        // Act
        val initialBlobId = store.stage(firstSource)
        val duplicateBlobId = store.stage(secondSource)

        // Assert
        assertEquals(
            initialBlobId,
            duplicateBlobId,
            "Staging identical payloads must produce matching blob IDs"
        )
        assertTrue(
            store.existsInPending(initialBlobId),
            "Original blob $initialBlobId must remain intact in pending directory"
        )
        assertEquals(
            2,
            digestFactory.invokeCount,
            "Digest factory should be invoked once per staging attempt"
        )
        assertEquals(
            listOf(initialBlobId),
            store.listPendingHashes(),
            "Deduplication must clean up temporary staging files without leaving orphaned entries"
        )
    }

    @Test
    fun should_cleanUpTempStagingFileAndRethrowMochaException_when_hasherFailsDuringStaging() =
        runEnv {
            // Arrange
            val source = TestPayloads.defaultSource()
            val expectedCause = IllegalStateException("Simulated native OpenSSL allocation failure")
            digestFactory.shouldThrow = expectedCause

            // Act & Assert Exception Wrapping
            val thrownException = assertFailsWith<MochaException> { store.stage(source) }

            // Assert
            assertEquals(
                thrownException.message.contains("Blob Staging"),
                true,
                "Exception must be converted to MochaException with staging context"
            )
            assertEquals(
                emptyList(),
                store.listPendingHashes(),
                "Pending directory must remain completely clean after a failed staging attempt"
            )
        }

    @Test
    fun should_safelyHandleConcurrentStaging_withoutDirectoryOrStateCollisions() =
        runEnv { scope ->
            // Arrange
            if (fileSystem.exists(pendingDir)) {
                fileSystem.deleteRecursively(pendingDir)
            }

            val multiThreadedStore = DefaultBlobStore(
                timeUtils = clock,
                digestFactory = digestFactory,
                fileSystem = fileSystem,
                pendingDir = pendingDir,
                committedDir = committedDir,
                blobMutex = mutex,
                logger = logger,
                ioContext = Dispatchers.Default
            )
            val threadCount = 8
            val readySignals = List(threadCount) { CompletableDeferred<Unit>() }
            val iterations = 5
            val gate = CompletableDeferred<Unit>()

            val workerDeferreds = List(threadCount) { workerId ->
                scope.async(Dispatchers.Default) {
                    readySignals[workerId].complete(Unit)
                    gate.await()

                    val localResults = mutableListOf<String>()
                    repeat(iterations) { iteration ->
                        // Generate unique payloads to stress-test concurrent creation of distinct paths
                        val uniquePayload =
                            TestPayloads.sourceFromString("payload-$workerId-$iteration")
                        val blobId = multiThreadedStore.stage(uniquePayload)
                        localResults.add(blobId)
                    }
                    localResults
                }
            }
            readySignals.awaitAll()

            // Act
            gate.complete(Unit)
            val allResults = workerDeferreds.awaitAll().flatten()

            // Assert
            assertEquals(
                threadCount * iterations,
                allResults.size,
                "All $threadCount threads across $iterations iterations must complete staging"
            )
            assertTrue(
                fileSystem.exists(pendingDir),
                "pendingDir must exist and remain valid after high-concurrency execution"
            )

            val pendingHashes = store.listPendingHashes().toSet()
            val expectedHashes = allResults.toSet()
            assertEquals(
                expectedHashes.size,
                pendingHashes.size,
                "No staged blobs should be lost or overwritten due to concurrent directory operations"
            )
            assertTrue(
                pendingHashes.containsAll(expectedHashes),
                "Every generated blob ID must be discoverable in pending hashes"
            )
        }

    @Test
    fun should_preserveFinalizedPendingBlobs_when_runningMaintenancePurge() = runEnv {
        // Arrange
        val blobId = store.stage(TestPayloads.defaultSource())

        assertTrue(
            store.existsInPending(blobId),
            "Prerequisite: Blob must exist in pending after staging"
        )

        // Act
        val deletedCount = store.clearIncompleteStaging()

        // Assert
        assertEquals(
            0,
            deletedCount,
            "Maintenance purge must return 0 deleted files when no orphaned staging files exist"
        )
        assertTrue(
            store.existsInPending(blobId),
            "Finalized blob must remain intact in pending directory"
        )
    }

    @Test
    fun should_neverPurgeFinalizedBlobs_when_timeAdvancesPastOneHourThreshold() = runEnv {
        // Arrange
        val blobId = store.stage(TestPayloads.defaultSource())

        // Act
        clock.advanceTime(1.hours.plus(1.milliseconds))
        val deletedCount = store.clearIncompleteStaging()

        // Assert
        assertEquals(
            0,
            deletedCount,
            "Finalized 64-character hash blobs must be immune to clearIncompleteStaging regardless of age"
        )
        assertTrue(
            store.existsInPending(blobId),
            "Finalized blob must remain accessible in pending directory after timeline progression"
        )
    }

    @Test
    fun should_purgeOnlyStaleStagingFiles_and_preserveFreshStagingAndFinalizedBlobs() =
        runEnv {
            // Arrange - Seeding file states
            fileSystem.createDirectories(pendingDir)

            val staleTimestamp =
                DefaultBlobStore.DEFAULT_STALE_AGE.plus(1.milliseconds).inWholeMilliseconds
            val staleStagingPath = Path(pendingDir, "staging_${staleTimestamp}_9999")
            fileSystem.sink(staleStagingPath).buffered()
                .use { it.write(TestPayloads.SMALL_TEXT_BYTES) }

            val freshStagingPath =
                Path(pendingDir, "staging_${clock.now().toEpochMilliseconds()}_8888")
            fileSystem.sink(freshStagingPath).buffered()
                .use { it.write(TestPayloads.SMALL_TEXT_BYTES) }

            val finalizedBlobId = store.stage(TestPayloads.defaultSource())

            // Act
            val deletedCount = store.clearIncompleteStaging()

            // Assert
            assertEquals(
                1,
                deletedCount,
                "clearIncompleteStaging should return exactly 1 for the purged stale file"
            )
            assertFalse(
                fileSystem.exists(staleStagingPath),
                "Staging file older than 1 hour must be deleted"
            )
            assertTrue(
                fileSystem.exists(freshStagingPath),
                "Staging file under 1 hour old must be preserved"
            )
            assertTrue(
                store.existsInPending(finalizedBlobId),
                "Finalized blobs must never be purged during maintenance"
            )
        }

    // -----------------------------------------------------------
    // LIFECYCLE STATE TRANSITIONS (COMMIT & ABORT)
    // -----------------------------------------------------------
    @Test
    fun should_moveBlobToCommitted_when_committingPendingBlob() = runEnv {
        // Arrange
        val source = TestPayloads.defaultSource()
        val blobId = store.stage(source)

        assertTrue(
            store.existsInPending(blobId),
            "Prerequisite: Blob must exist in pending before commit"
        )
        assertFalse(
            store.existsInCommitted(blobId),
            "Prerequisite: Blob must not exist in committed before commit"
        )

        // Act
        store.commit(blobId)

        // Assert
        assertFalse(
            store.existsInPending(blobId),
            "Blob should no longer exist in pending after successful commit"
        )
        assertTrue(
            store.existsInCommitted(blobId),
            "Blob must exist in committed directory after atomic move"
        )
    }

    @Test
    fun should_logWarningAndNotThrow_when_committingNonExistentBlob() = runEnv {
        // Arrange
        val nonExistentBlobId =
            "0000000000000000000000000000000000000000000000000000000000000000"

        // Act & Assert - Should gracefully log warning without throwing an exception
        store.commit(nonExistentBlobId)
        assertFalse(
            store.existsInCommitted(nonExistentBlobId),
            "Non-existent blob should not magically appear in committed directory"
        )
        assertFalse(
            store.existsInPending(nonExistentBlobId),
            "Non-existent blob should not exist in pending directory"
        )
        with(writer.logs[2]) {
            assertEquals(Severity.Warn, severity)
        }
    }

    @Test
    fun should_removeBlobFromPending_when_abortingPendingBlob() = runEnv {
        // Arrange
        val source = TestPayloads.defaultSource()
        val blobId = store.stage(source)
        assertTrue(
            store.existsInPending(blobId),
            "Prerequisite: Blob must exist in pending prior to abort"
        )
        // Act
        store.abort(blobId)
        // Assert
        assertFalse(
            store.existsInPending(blobId),
            "Aborted blob must be completely deleted from pending directory"
        )
        assertFalse(
            store.existsInCommitted(blobId),
            "Aborted blob must never reach committed directory"
        )
    }

    // -----------------------------------------------------------
    // FILE CONVENTION VALIDITY
    // -----------------------------------------------------------
    @Test
    fun should_returnOnlyValid64CharHashes_and_filterOutStagingAndInvalidFiles() =
        runEnv {
            // Arrange
            fileSystem.createDirectories(pendingDir)

            // Stage valid 64-char hex entry in pendingDir
            val validBlobId = store.stage(TestPayloads.defaultSource())

            // Seed an orphaned temporary staging file directly into pendingDir
            val stagingPath =
                Path(pendingDir, "staging_${clock.now().toEpochMilliseconds()}_7777")
            fileSystem.sink(stagingPath).buffered()
                .use { it.write(TestPayloads.SMALL_TEXT_BYTES) }

            // Seed invalid non-hash files into pendingDir
            val textFile = Path(pendingDir, "random_notes.txt")
            fileSystem.sink(textFile).buffered()
                .use { it.write(TestPayloads.SMALL_TEXT_BYTES) }

            // Act
            val pendingHashes = store.listPendingHashes()

            // Assert
            assertEquals(
                listOf(validBlobId),
                pendingHashes,
                "listPendingHashes must only return valid 64-character hex strings, excluding staging_ prefix and arbitrary files"
            )
            assertFalse(
                pendingHashes.contains(
                    "staging_${
                        clock.now().toEpochMilliseconds()
                    }_7777"
                ),
                "Staging temporary files must be explicitly filtered out of pending hash lists"
            )
        }

    // -----------------------------------------------------------
    // READ ACCESS
    // -----------------------------------------------------------
    @Test
    fun should_returnSourceWithMatchingBytes_when_openingCommittedBlob() = runEnv {
        // Arrange
        val source = TestPayloads.defaultSource()
        val expectedBytes = source.peek().readByteArray()

        val blobId = store.stage(source)
        store.commit(blobId)

        // Act
        val readSource = store.open(blobId)

        // Assert
        readSource.use { input ->
            val actualBytes = input.readByteArray()
            assertContentEquals(
                expectedBytes,
                actualBytes,
                "Source returned by open() must yield identical bytes to the originally staged payload"
            )
        }
    }

    @Test
    fun should_throwFileNotFoundMochaException_when_openingNonExistentBlob() = runEnv {
        // Arrange
        val missingBlobId =
            "0000000000000000000000000000000000000000000000000000000000000000"

        // Act & Assert
        val exception = assertFailsWith<MochaException.Transient.FileNotFound> {
            store.open(missingBlobId)
        }

        assertTrue(
            exception.message.contains(missingBlobId) || exception.message.contains("Blob not found"),
            "Exception message should clearly indicate missing blob context"
        )
    }

    // -----------------------------------------------------------
    // DIRECTORY INIT
    // -----------------------------------------------------------
    @Test
    fun should_automaticallyCreatePendingAndCommittedDirectories_onFirstIoOperation() =
        runEnv {
            // Arrange - Ensure storage directories are completely removed prior to first I/O
            if (fileSystem.exists(pendingDir)) fileSystem.deleteRecursively(pendingDir)
            val committedDir = Path(pendingDir.parent!!, "committed")
            if (fileSystem.exists(committedDir)) fileSystem.deleteRecursively(committedDir)

            assertFalse(
                fileSystem.exists(pendingDir),
                "Prerequisite: pendingDir must not exist before I/O"
            )
            assertFalse(
                fileSystem.exists(committedDir),
                "Prerequisite: committedDir must not exist before I/O"
            )

            // Act - Trigger initial read/write operation
            store.listPendingHashes()

            // Assert
            assertTrue(
                fileSystem.exists(pendingDir),
                "DefaultBlobStore must lazily create pendingDir on first I/O operation"
            )
            assertTrue(
                fileSystem.exists(committedDir),
                "DefaultBlobStore must lazily create committedDir on first I/O operation"
            )
        }

}