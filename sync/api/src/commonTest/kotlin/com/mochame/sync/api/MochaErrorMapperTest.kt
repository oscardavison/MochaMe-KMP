package com.mochame.sync.api

import com.mochame.support.MochaPlatformTest
import com.mochame.sync.api.exceptions.MochaException
import com.mochame.sync.api.exceptions.toMochaException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.milliseconds


class MochaErrorMapperTest : MochaPlatformTest() {
    // -----------------------------------------------------------
    // Standard Cancellation Transparency
    // -----------------------------------------------------------

    @Test
    fun should_rethrowDirectly_when_rootCancellationExceptionThrown() {
        val original = CancellationException("Standard job cancellation")

        val thrown = assertFailsWith<CancellationException> {
            original.toMochaException("Context message")
        }

        assertSame(original, thrown)
    }

    @Test
    fun should_rethrowDirectly_when_customChildCancellationExceptionThrown() {
        class CustomCancellation(msg: String) : CancellationException(msg)

        val original = CustomCancellation("Child scope cancelled")

        val thrown = assertFailsWith<CustomCancellation> {
            original.toMochaException("Context message")
        }

        assertSame(original, thrown)
    }

    // -----------------------------------------------------------
    // Timeout Disambiguation
    // -----------------------------------------------------------

    @Test
    fun should_mapToTransientContention_when_timeoutCancellationOccurs() = runTest {
        val timeoutException = runCatching {
            withTimeout(1.milliseconds) {
                delay(100.milliseconds)
            }
        }.exceptionOrNull()

        requireNotNull(timeoutException)

        val contextMessage = "Sync operation timed out"
        val result = timeoutException.toMochaException(contextMessage)

        assertIs<MochaException.Transient.Contention>(result)
        assertEquals(contextMessage, result.message)
        assertSame(timeoutException, result.cause)
    }

    @Test
    fun should_mapToTransientContentionWithDefaultMessage_when_noContextProvided() = runTest {
        val timeoutException = runCatching {
            withTimeout(1.milliseconds) {
                delay(100.milliseconds)
            }
        }.exceptionOrNull()

        requireNotNull(timeoutException)

        val result = timeoutException.toMochaException()

        assertIs<MochaException.Transient.Contention>(result)
        assertEquals("Operation timed out.", result.message)
        assertSame(timeoutException, result.cause)
    }

    // -----------------------------------------------------------
    // Errors
    // -----------------------------------------------------------

    @Test
    fun should_rethrowDirectly_when_fatalErrorThrown() {
        val original = Error("Fatal system failure")

        val thrown = assertFailsWith<Error> {
            original.toMochaException("Context message")
        }

        assertSame(original, thrown)
    }

    @Test
    fun should_rethrowImmediately_when_customFatalErrorContainsSqliteToken() {
        class PosixMemoryFaultError(msg: String) : Error(msg)

        val original =
            PosixMemoryFaultError("Fatal memory fault: SQLITE_BUSY lock acquisition faulted")

        val thrown = assertFailsWith<PosixMemoryFaultError> {
            original.toMochaException("Context message")
        }

        assertSame(original, thrown)
    }

    @Test
    fun should_rethrowImmediately_when_errorContainsIoToken() {
        class DiskDriveHardwareFailureError(msg: String) : Error(msg)

        val original = DiskDriveHardwareFailureError("Hardware fail ENOSPC No space left on device")

        val thrown = assertFailsWith<DiskDriveHardwareFailureError> {
            original.toMochaException("Context message")
        }

        assertSame(original, thrown)
    }

    // -----------------------------------------------------------
    // Locking & Contention (Transient: SQLITE_BUSY, SQLITE_LOCKED)
    // -----------------------------------------------------------

    @Test
    fun should_mapToDatabaseBusy_when_directMessageContainsSqliteBusy() {
        val original =
            RuntimeException("sqlite.SQLiteDatabaseLockedException: SQLITE_BUSY: database is locked")
        val result = original.toMochaException("Write transaction stalled")

        assertIs<MochaException.Transient.DatabaseBusy>(result)
        assertEquals("Write transaction stalled", result.message)
        assertSame(original, result.cause)
    }

    @Test
    fun should_mapToDatabaseBusy_when_directMessageContainsSqliteLocked() {
        val original = RuntimeException("SQLiteException: SQLITE_LOCKED (261)")
        val result = original.toMochaException("Table locked")

        assertIs<MochaException.Transient.DatabaseBusy>(result)
        assertEquals("Table locked", result.message)
        assertSame(original, result.cause)
    }

    @Test
    fun should_mapToDatabaseBusy_when_causeContainsSqliteBusy() {
        val cause = RuntimeException("Error code 5: SQLITE_BUSY")
        val wrapper = RuntimeException("Room transaction execution failed", cause)
        val result = wrapper.toMochaException("Pipeline step failed")

        assertIs<MochaException.Transient.DatabaseBusy>(result)
        assertEquals("Pipeline step failed", result.message)
        assertSame(wrapper, result.cause)
    }

    @Test
    fun should_mapToDatabaseBusy_when_causeContainsSqliteLocked() {
        val cause = RuntimeException("SQLITE_LOCKED: database table is locked")
        val wrapper = RuntimeException("Framework query dispatch failed", cause)
        val result = wrapper.toMochaException("Pipeline step failed")

        assertIs<MochaException.Transient.DatabaseBusy>(result)
        assertEquals("Pipeline step failed", result.message)
        assertSame(wrapper, result.cause)
    }

    // -----------------------------------------------------------
    // Disk Space (Persistent: SQLITE_FULL)
    // -----------------------------------------------------------

    @Test
    fun should_mapToDiskFull_when_directMessageContainsSqliteFull() {
        val original = RuntimeException("SQLiteException: SQLITE_FULL: database or disk is full")
        val result = original.toMochaException("WAL write failed")

        assertIs<MochaException.Persistent.DiskFull>(result)
        assertEquals("WAL write failed", result.message)
        assertSame(original, result.cause)
    }

    @Test
    fun should_mapToDiskFull_when_causeContainsSqliteFull() {
        val cause = RuntimeException("SQLITE_FULL (13)")
        val wrapper = RuntimeException("Database commit failed", cause)
        val result = wrapper.toMochaException("Sync flush failure")

        assertIs<MochaException.Persistent.DiskFull>(result)
        assertEquals("Sync flush failure", result.message)
        assertSame(wrapper, result.cause)
    }

    // -----------------------------------------------------------
    // Storage Integrity (Persistent: SQLITE_CORRUPT)
    // -----------------------------------------------------------

    @Test
    fun should_mapToStateIssue_when_directMessageContainsSqliteCorrupt() {
        val original =
            RuntimeException("SQLiteDatabaseCorruptException: SQLITE_CORRUPT: database disk image is malformed")
        val result = original.toMochaException("DB verification failed")

        assertIs<MochaException.Transient.StateIssue>(result)
        assertEquals("DB verification failed", result.message)
    }

    @Test
    fun should_mapToStateIssue_when_causeContainsSqliteCorrupt() {
        val cause = RuntimeException("SQLITE_CORRUPT (11)")
        val wrapper = RuntimeException("Page read failure", cause)
        val result = wrapper.toMochaException("Schema verification failed")

        assertIs<MochaException.Transient.StateIssue>(result)
        assertEquals("Schema verification failed", result.message)
    }

    // -----------------------------------------------------------
    // Data Schema (UNIQUE, FOREIGN KEY)
    // -----------------------------------------------------------

    @Test
    fun should_mapToStateIssue_when_directMessageContainsUniqueConstraint() {
        val original =
            RuntimeException("SQLiteConstraintException: UNIQUE constraint failed: user_entity.hlc")
        val result = original.toMochaException("Insertion rejected")

        assertIs<MochaException.Transient.StateIssue>(result)
        assertEquals("Insertion rejected", result.message)
        assertSame(original, result.cause)
    }

    @Test
    fun should_mapToStateIssue_when_causeContainsUniqueConstraint() {
        val cause = RuntimeException("UNIQUE constraint failed: bio_record.id")
        val wrapper = RuntimeException("Room DAO operation failed", cause)
        val result = wrapper.toMochaException("Conflict detected")

        assertIs<MochaException.Transient.StateIssue>(result)
        assertEquals("Conflict detected", result.message)
        assertSame(wrapper, result.cause)
    }

    @Test
    fun should_mapToStateIssue_when_directMessageContainsForeignKeyConstraint() {
        val original = RuntimeException("SQLiteConstraintException: FOREIGN KEY constraint failed")
        val result = original.toMochaException("Foreign key mismatch")

        assertIs<MochaException.Transient.StateIssue>(result)
        assertEquals("Foreign key mismatch", result.message)
        assertSame(original, result.cause)
    }

    @Test
    fun should_mapToStateIssue_when_causeContainsForeignKeyConstraint() {
        val cause = RuntimeException("FOREIGN KEY constraint failed")
        val wrapper = RuntimeException("Integrity check failure", cause)
        val result = wrapper.toMochaException("Parent record missing")

        assertIs<MochaException.Transient.StateIssue>(result)
        assertEquals("Parent record missing", result.message)
        assertSame(wrapper, result.cause)
    }

    // -----------------------------------------------------------
    // Cause Unwrapping Depth & Fallthrough
    // -----------------------------------------------------------

    @Test
    fun should_fallthrough_when_sqliteTokensAreAbsentInMessageAndCause() {
        val cause = RuntimeException("Unrelated inner error")
        val wrapper = RuntimeException("General database execution error", cause)
        val result = wrapper.toMochaException("DB generic failure")

        assertIs<MochaException.Persistent.Uncategorized>(result)
        assertEquals("DB generic failure", result.message)
        assertSame(wrapper, result.cause)
    }

    @Test
    fun should_fallthrough_when_exceptionMessageAndCauseAreBlank() {
        val original = RuntimeException("")
        val result = original.toMochaException("Empty error")

        assertIs<MochaException.Persistent.Uncategorized>(result)
        assertEquals("Empty error", result.message)
        assertSame(original, result.cause)
    }

    // -----------------------------------------------------------
    // Disk Exhaustion Tokens (ENOSPC, No space)
    // -----------------------------------------------------------

    @Test
    fun should_mapToDiskFull_when_ioExceptionContainsEnospc() {
        val original = IOException("write failed: ENOSPC (No space left on device)")
        val result = original.toMochaException("Context message")

        assertIs<MochaException.Persistent.DiskFull>(result)
        assertEquals("Context message", result.message)
        assertSame(original, result.cause)
    }

    @Test
    fun should_mapToDiskFull_when_ioExceptionContainsCaseInsensitiveNoSpace() {
        val original = IOException("File flush error: no space available on partition")
        val result = original.toMochaException("Context message")

        assertIs<MochaException.Persistent.DiskFull>(result)
        assertEquals("Context message", result.message)
        assertSame(original, result.cause)
    }

    // -----------------------------------------------------------
    // Access Control Issues (Permission, Access denied)
    // -----------------------------------------------------------

    @Test
    fun should_mapToAccessIssue_when_ioExceptionContainsPermission() {
        val original = IOException("open failed: EACCES (Permission denied)")
        val result = original.toMochaException("Context message")

        assertIs<MochaException.Persistent.IOFailure>(result)
        assertEquals("Context message", result.message)
        assertSame(original, result.cause)
    }

    @Test
    fun should_mapToAccessIssue_when_ioExceptionContainsAccessDenied() {
        val original = IOException("Access denied: cannot write to lockfile")
        val result = original.toMochaException("Context message")

        assertIs<MochaException.Persistent.IOFailure>(result)
        assertEquals("Context message", result.message)
        assertSame(original, result.cause)
    }

    // -----------------------------------------------------------
    // I/O Fallback
    // -----------------------------------------------------------

    @Test
    fun should_fallbackToAccessIssue_when_ioExceptionContainsGenericMessage() {
        val original = IOException("Connection reset by peer during channel transfer")
        val result = original.toMochaException("Context message")

        assertIs<MochaException.Persistent.IOFailure>(result)
        assertEquals("Context message", result.message)
        assertSame(original, result.cause)
    }

    @Test
    fun should_fallbackToDefaultMessage_when_ioExceptionMessageIsNull() {
        val original = IOException(null, null)
        val result = original.toMochaException()

        assertIs<MochaException.Persistent.IOFailure>(result)
        assertEquals("Unexpected IO Error", result.message)
        assertSame(original, result.cause)
    }

    @Test
    fun should_prioritizeSqliteTokens_when_ioExceptionContainsSqlitePattern() {
        val original = IOException("Underlying driver error: SQLITE_BUSY")
        val result = original.toMochaException("Context message")

        assertIs<MochaException.Transient.DatabaseBusy>(result)
        assertEquals("Context message", result.message)
        assertSame(original, result.cause)
    }

    // -----------------------------------------------------------
    // State & Argument Errors
    // -----------------------------------------------------------

    @Test
    fun should_mapToStateIssueAndPreserveContext_when_illegalArgumentExceptionThrown() {
        val original = IllegalArgumentException("Invalid node ID format provided")
        val context = "Initialization failed"
        val result = original.toMochaException(context)

        assertIs<MochaException.Transient.StateIssue>(result)
        assertEquals(context, result.message)
        assertSame(original, result.cause)
    }

    @Test
    fun should_mapToStateIssue_when_illegalStateExceptionThrown() {
        val original = IllegalStateException("Orchestrator pipeline not initialized")
        val context = "Execution precondition violated"
        val result = original.toMochaException(context)

        assertIs<MochaException.Transient.StateIssue>(result)
        assertEquals(context, result.message)
        assertSame(original, result.cause)
    }

    // -----------------------------------------------------------
    // Idempotency
    // -----------------------------------------------------------

    @Test
    fun should_returnSameInstance_when_alreadyTransientMochaException() {
        val original = MochaException.Transient.DatabaseBusy(
            "Lock acquisition stalled",
            RuntimeException("inner")
        )
        val result = original.toMochaException("Overriding context")

        assertSame(original, result)
        assertEquals("Lock acquisition stalled", result.message)
    }

    @Test
    fun should_returnSameInstance_when_alreadyPersistentMochaException() {
        val original = MochaException.Persistent.DiskFull("Storage partition exhausted", null)
        val result = original.toMochaException("Overriding context")

        assertSame(original, result)
        assertEquals("Storage partition exhausted", result.message)
    }

    // -----------------------------------------------------------
    // Uncategorized Fallback
    // -----------------------------------------------------------

    @Test
    fun should_mapToUncategorized_when_arbitraryCustomExceptionThrown() {
        class CustomDomainBoundaryException(msg: String) : Exception(msg)

        val original = CustomDomainBoundaryException("Unrecognized business state failure")
        val context = "Fallback dispatch"
        val result = original.toMochaException(context)

        assertIs<MochaException.Persistent.Uncategorized>(result)
        assertEquals(context, result.message)
        assertSame(original, result.cause)
    }

    @Test
    fun should_mapToUncategorized_when_genericRuntimeExceptionThrownWithoutSqliteTokens() {
        val original = RuntimeException("Null pointer during parsing")
        val context = "Parser pipeline failed"
        val result = original.toMochaException(context)

        assertIs<MochaException.Persistent.Uncategorized>(result)
        assertEquals(context, result.message)
        assertSame(original, result.cause)
    }

    // -----------------------------------------------------------
    // Context Preservation Across Nullable / Non-Nullable Inputs
    // -----------------------------------------------------------

    @Test
    fun should_defaultThrownMessage_when_noContextSuppliedToUncategorized() {
        val thrownMessage = "Requested entity not found in memory buffer"
        val original = NoSuchElementException(thrownMessage)
        val result = original.toMochaException()

        assertIs<MochaException.Persistent.Uncategorized>(result)
        assertEquals("Unexpected failure.", result.message)
        assertSame(original, result.cause)
    }

    @Test
    fun should_retainOriginalUnderlyingCause_acrossAllFallbackChains() {
        val rootCause = RuntimeException("Root kernel level socket closure")
        val intermediate = IllegalStateException("Pipeline aborted", rootCause)
        val result = intermediate.toMochaException("Pipeline context")

        assertIs<MochaException.Transient.StateIssue>(result)
        assertEquals("Pipeline context", result.message)
        assertSame(intermediate, result.cause)
        assertSame(rootCause, result.cause?.cause)
    }
}