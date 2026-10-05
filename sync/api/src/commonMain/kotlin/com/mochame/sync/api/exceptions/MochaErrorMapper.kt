package com.mochame.sync.api.exceptions

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Handle standard, IO, and Coroutine timeouts.
 */
fun Throwable.toMochaException(context: String? = null): MochaException {
    if (this is Error) throw this
    if (this is MochaException) return this
    if (this is TimeoutCancellationException)
        return MochaException.Transient.Contention(context, this)
    if (this is CancellationException) throw this

    mapToSqliteFailure(context)?.let { return it }

    return when (this) {
        is IllegalArgumentException, is IllegalStateException, is NullPointerException ->
            MochaException.Transient.StateIssue(context, this)

        is IOException -> this.mapToIoFailure(context)
        else -> MochaException.Persistent.Uncategorized(context, this)
    }
}

private fun Throwable.mapToIoFailure(context: String?): MochaException {
    val chainMessages = errorChainMessages().lowercase()

    return when {
        context?.contains("directory initialization") == true  ->
            MochaException.Persistent.DirectoryInitializationFailure(context, this)

        "enospc" in chainMessages || "no space" in chainMessages ->
            MochaException.Persistent.DiskFull(context, this)

        "permission" in chainMessages || "access denied" in chainMessages ->
            MochaException.Persistent.IOFailure(context, this)

        else -> MochaException.Persistent.IOFailure(context, this)
    }
}

// -----------------------------------------------------------
// SQLITE
// -----------------------------------------------------------

private fun Throwable.mapToSqliteFailure(context: String?): MochaException? {
    val chainMessages = errorChainMessages()
    if (chainMessages.isBlank()) return null

    return when {
        "SQLITE_BUSY" in chainMessages || "SQLITE_LOCKED" in chainMessages ->
            MochaException.Transient.DatabaseBusy(context, this)

        "SQLITE_FULL" in chainMessages ->
            MochaException.Persistent.DiskFull(context, this)

        "SQLITE_CORRUPT" in chainMessages ->
            MochaException.Transient.StateIssue(context)

        "UNIQUE constraint" in chainMessages || "FOREIGN KEY constraint" in chainMessages ->
            MochaException.Transient.StateIssue(context, this)

        else -> null
    }
}

private fun Throwable.errorChainMessages(): String = buildString {
    var current: Throwable? = this@errorChainMessages
    while (current != null) {
        current.message?.let {
            if (isNotEmpty()) append(" ")
            append(it)
        }
        current = current.cause
    }
}