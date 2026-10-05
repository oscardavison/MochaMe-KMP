package com.mochame.sync.internal.fixtures.infrastructure

import com.mochame.sync.api.metadata.FeatureContext
import com.mochame.sync.domain.infrastructure.KeyedLocker
import com.mochame.sync.domain.model.LockKey
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock

class FakeKeyedLocker : KeyedLocker {

    private val lock = reentrantLock()

    private var _totalLockInvocations = 0
    private val _invocationsByKey = mutableMapOf<LockKey, Int>()
    private var pendingError: Throwable? = null

    // --- Telemetry ---

    val totalLockInvocations: Int
        get() = lock.withLock { _totalLockInvocations }

    fun invocationsFor(context: FeatureContext, candidateKey: Long): Int {
        val key = LockKey(context, candidateKey)
        return lock.withLock { _invocationsByKey[key] ?: 0 }
    }

    val lockedKeys: Set<LockKey>
        get() = lock.withLock { _invocationsByKey.keys.toSet() }

    // --- KeyedLocker Implementation ---

    override suspend fun <R> withLock(
        context: FeatureContext,
        candidateKey: Long,
        action: suspend () -> R
    ): R {
        val key = LockKey(context, candidateKey)

        lock.withLock {
            val error = pendingError
            pendingError = null
            error?.let { throw it }

            _totalLockInvocations++
            _invocationsByKey[key] = (_invocationsByKey[key] ?: 0) + 1
        }

        return action()
    }

    // --- Test Helpers ---

    fun throwOnNextLock(throwable: Throwable) = lock.withLock {
        pendingError = throwable
    }

    fun reset() = lock.withLock {
        _totalLockInvocations = 0
        _invocationsByKey.clear()
        pendingError = null
    }
}
