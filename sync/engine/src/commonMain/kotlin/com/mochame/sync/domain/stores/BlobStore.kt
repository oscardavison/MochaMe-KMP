package com.mochame.sync.domain.stores

import kotlinx.io.Source

interface BlobStore {
    /**
     * Streams source to /pending while hashing.
     * Returns the contentBlobId.
     */
    suspend fun stage(source: Source): String

    /**
     * Atomic move from /pending to /committed.
     */
    suspend fun commit(blobId: String)

    /**
     * Cleanup for aborted transactions.
     */
    suspend fun abort(blobId: String)

    /**
     * Returns all finalized blobIds (hashes) currently sitting
     * in the pending chamber.
     */
    suspend fun listPendingHashes(): List<String>

    /**
     * Deletes all "staging_*" files.
     * Safe to call at boot because no active writes can exist yet.
     */
    suspend fun clearIncompleteStaging(): Int

    /** Only allowed to open files from the /committed chamber. */
    suspend fun open(blobId: String): Source

    suspend fun existsInCommitted(blobId: String): Boolean

    suspend fun existsInPending(blobId: String): Boolean
}