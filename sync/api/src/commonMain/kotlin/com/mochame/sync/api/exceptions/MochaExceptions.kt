package com.mochame.sync.api.exceptions

import kotlin.time.Duration

/**
 * This was meant to make UI error processing clearer to me, but I think I regret this.
 */
sealed class MochaException(
    override val message: String,
    override val cause: Throwable? = null
) : Exception(message, cause) {

    sealed class Transient(message: String, cause: Throwable? = null) :
        MochaException(message, cause) {
        class DatabaseBusy(message: String? = null, cause: Throwable? = null) :
            Transient(message ?: "The database is locked.", cause)

        class NetworkDisconnect(message: String? = null, cause: Throwable? = null) :
            Transient(message ?: "The sync server took too long to respond.", cause)

        class Contention(message: String? = null, cause: Throwable? = null) :
            Transient(message ?: "Operation timed out.", cause)

        class BootTimeout(message: String? = null, cause: Throwable? = null) :
            Transient(message ?: "Boot system timeout.", cause)

        class BlobResolutionPending(val blobId: String) :
            Transient("Payload overflow pending blob resolution: $blobId")

        class FileNotFound(blobId: String) :
            Transient("File not found: $blobId")

        class LocalPersistenceFailure(message: String? = null, cause: Throwable? = null) :
            Transient(message ?: "Local persistence failed.", cause)

        class StateIssue(message: String? = null, cause: Throwable? = null) :
            Transient(message ?: "Data corruption detected.", cause)

    }

    sealed class Persistent(message: String, cause: Throwable? = null) :
        MochaException(message, cause) {

        class DiskFull(message: String? = null, cause: Throwable? = null) :
            Persistent(message ?: "Cannot write to disk; storage is full.", cause)

        class BootLockout(message: String? = null, cause: Throwable? = null) :
            Persistent(message ?: "Boot lockout.", cause)

        class ClockSkew(val drift: Duration, cause: Throwable? = null) :
            Persistent("Clock Skew: $drift.", cause)

        class BootInitializationError(message: String?, cause: Throwable? = null) :
            Persistent(message ?: "Failed to initialize system.", cause)

        class HlcParseException(rawString: String) :
            Persistent("Failed to parse HLC string: '$rawString'. Data integrity at risk.")

        class Uncategorized(message: String? = null, cause: Throwable? = null) :
            Persistent(message ?: "Unexpected failure.", cause)

        class UnknownProtocolVersion(val version: Int) :
            Persistent("Unknown protocol version: $version")

        class Internal(message: String?, cause: Throwable? = null) :
            Persistent(message ?: "Internal failure. Dependency issue? ${cause?.message}", cause)

        class DirectoryInitializationFailure(message: String?, cause: Throwable? = null) :
            Persistent(message ?: "Failed to initialize directory.", cause)

        class IOFailure(message: String?, cause: Throwable? = null) :
            Persistent(message ?: "Unexpected IO Error", cause)
    }

    sealed class Policy(message: String, cause: Throwable? = null) :
        MochaException(message, cause) {
        class CausalityViolation(message: String) : Policy(message)
        class IdentityConflict(message: String) : Policy(message)
    }

    sealed class SemanticException(message: String, cause: Throwable? = null) :
        MochaException(message, cause) {

        // SPACES
        sealed class Space(message: String) : SemanticException(message) {
            data class NotFound(val id: String) : Space("Space $id was not found.")
            data class AlreadyExists(val name: String) :
                Space("Space '$name' already existsInCommitted.")

            data class InUse(val id: String, val count: Int) : Space(
                "Cannot deleteBlobByHash space $id: $count moments are still anchored here."
            )
        }


        // ===================== RESONANCE ========================

        // AUTHORS
        sealed class Author(message: String) : SemanticException(message) {
            data class NotFound(val id: String) : Author("Author $id not found.")
            data class InUse(val id: String, val bookCount: Int) : Author(
                "Cannot deleteBlobByHash author $id: It still contains $bookCount books."
            )
        }

        // BOOKS
        sealed class Book(message: String) : SemanticException(message) {
            data class NotFound(val id: String) : Book("Book $id not found.")

            data class InUse(val id: String, val quoteCount: Int) : Book(
                "Cannot deleteBlobByHash book $id: It still has $quoteCount associated quotes."
            )
        }

        // ===================== TELEMETRY ========================

        // DOMAINS
        sealed class Domain(message: String) : SemanticException(message) {
            data class InUse(val id: String, val momentCount: Int) : Domain(
                "Cannot deleteBlobByHash Domain $id: It still has $momentCount associated quotes."
            )

            data class AlreadyExists(val name: String, val domainId: String) :
                Domain("Domain '$name' already existsInCommitted in domain $domainId.")


            data class NotFound(val id: String) : Domain("Domain $id not found.")
        }

        // TOPICS & CATEGORIES
        sealed class Topic(message: String) : SemanticException(message) {
            data class AlreadyExists(val name: String, val topicId: String) :
                Topic("Topic '$name' already existsInCommitted in domain $topicId.")

            data class NotFound(val id: String) : Topic("Topic $id not found.")

            data class InUse(val id: String, val momentCount: Int) : Topic(
                "Cannot deleteBlobByHash Topic $id: It still has $momentCount associated quotes."
            )
        }
    }
}