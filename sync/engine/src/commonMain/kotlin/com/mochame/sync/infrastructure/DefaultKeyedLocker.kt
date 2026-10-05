package com.mochame.sync.infrastructure

import com.mochame.sync.api.metadata.FeatureContext
import com.mochame.sync.domain.model.LockKey
import com.mochame.sync.domain.infrastructure.KeyedLocker
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single

/**
 * A reference-counted locker for keyed synchronization.
 */
@Single(binds = [KeyedLocker::class])
internal class DefaultKeyedLocker : KeyedLocker {
    private class LockEntry(val mutex: Mutex = Mutex(), var activeUsers: Int = 0)

    /**
     * Guards map lookups, mutations, and inspectability reads across threads.
     */
    private val registryLock = reentrantLock()
    private val keyLocks = mutableMapOf<LockKey, LockEntry>()

    /**
     * Returns the total count of active lock keys currently retained in the registry.
     */
    internal val activeKeysCount: Int
        get() = registryLock.withLock { keyLocks.size }

    /**
     * Returns the count of active users (holder + queued waiters) for a specific domain key,
     * or null if the key is not currently present in the registry.
     */
    internal fun activeUsersFor(context: FeatureContext, candidateKey: Long): Int? {
        val key = LockKey(context, candidateKey)
        return registryLock.withLock { keyLocks[key]?.activeUsers }
    }

    /**
     * Returns the count of active users directly via an existing [LockKey] instance,
     * avoiding redundant object allocations.
     */
    internal fun activeUsersFor(key: LockKey): Int? =
        registryLock.withLock { keyLocks[key]?.activeUsers }


    override suspend fun <R> withLock(
        context: FeatureContext,
        candidateKey: Long,
        action: suspend () -> R
    ): R {
        val key = LockKey(context, candidateKey)
        val entry = registryLock.withLock {
            keyLocks.getOrPut(key) { LockEntry() }.apply { activeUsers++ }
        }

        return try {
            entry.mutex.withLock { action() }
        } finally {
            withContext(NonCancellable) {
                registryLock.withLock {
                    entry.activeUsers--
                    if (entry.activeUsers == 0) {
                        keyLocks.remove(key)
                    }
                }
            }
        }
    }

}