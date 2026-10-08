package com.mochame.sync.fixtures

import com.mochame.sync.api.SyncAdaptor
import com.mochame.sync.api.models.LocalFirstEntity
import kotlinx.atomicfu.atomic

/**
 * In-memory [SyncAdaptor] to test feature repositories in isolation.
 *
 * Adheres to the [LocalFirstEntity] contract:
 * - Local skip when upsert delta evaluates to a no-op (identical to existing)
 * - No-op on deleting non-existent records
 * - Deletion via [LocalFirstEntity.withDeleteState] when computeChange is omitted
 */
class FakeSyncAdaptor<T : LocalFirstEntity<T>>(
    private val fetchById: suspend (Long) -> T?,
    private val save: suspend (T) -> Long
) : SyncAdaptor<T> {

    private val _upsertCalls = atomic(0)
    private val _deleteCalls = atomic(0)
    private val _shouldThrow = atomic<Exception?>(null)

    var upsertCalls: Int
        get() = _upsertCalls.value
        set(value) { _upsertCalls.value = value }
    var deleteCalls: Int
        get() = _deleteCalls.value
        set(value) { _deleteCalls.value = value }
    var shouldThrow: Exception?
        get() = _shouldThrow.value
        set(value) { _shouldThrow.value = value }

    override suspend fun upsert(
        candidateKey: Long,
        computeChange: suspend (existing: T?) -> T
    ): Long {
        _shouldThrow.value?.let { throw it }
        upsertCalls++

        val existing = fetchById(candidateKey)
        val candidate = computeChange(existing)

        if (existing != null && candidate == existing) { return 0L }

        return save(candidate)
    }

    override suspend fun delete(
        candidateKey: Long,
        computeChange: (suspend (existing: T?) -> T)?
    ): Long {
        _shouldThrow.value?.let { throw it }
        deleteCalls++

        val existing = fetchById(candidateKey) ?: return 0L

        if (existing.isDeleted) { return 0L }

        val tombstone = computeChange?.invoke(existing) ?: existing.withDeleteState(true)
        return save(tombstone)
    }
}