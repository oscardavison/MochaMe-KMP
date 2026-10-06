package com.mochame.sync.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.mochame.sync.api.metadata.FeatureContext
import com.mochame.sync.api.metadata.MutationOp
import com.mochame.sync.domain.model.SyncStatus
import kotlin.time.Clock

/**
 * Sync metadata wrapping each local intent.
 * Idea is for this to act as a persistence record of a mutation's lifecycle.
 */
@Entity(
    tableName = "sync_intent",
    indices = [
        Index(value = ["syncStatus", "batchId", "hlc"]),
        Index(value = ["batchId", "syncStatus"]),
        Index(value = ["candidateKey", "syncStatus"]),
        Index(value = ["featureContext", "syncStatus"]),
        Index(value = ["syncStatus", "leasedAt"]),
        Index(value = ["overflowBlobId"])
    ]
)
data class SyncIntentEntity(
    @PrimaryKey val hlc: String,
    val featureSchemaVersion: Int,
    val candidateKey: Long,
    val featureContext: FeatureContext,
    val operation: MutationOp,
    val payload: ByteArray?,
    val overflowBlobId: String?,
    val syncStatus: SyncStatus,
    val batchId: Long? = null,          // lease identity, diagnostic traceability
    val leasedAt: Long? = null,          // enables safe Janitor cutoff queries
    val diagnosticSummary: String?,
    val retryCount: Int = 0,              // Janitor implements threshold logic
    val lastErrorMessage: String? = null,
    val createdAt: Long = Clock.System.now().toEpochMilliseconds(),
    val changedMask: Long
)