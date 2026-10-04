package com.mochame.sync.internal.fixtures.infrastructure

import com.mochame.sync.infrastructure.DefaultSyncWorkerHook
import com.mochame.sync.domain.infrastructure.SyncWorkerHook
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SpySyncWorkerHook(
    private val delegate: SyncWorkerHook = DefaultSyncWorkerHook()
) : SyncWorkerHook {

    private val lock = reentrantLock()
    private var _invalidationCount = 0
    private var _totalCollects = 0
    private var pendingSignalError: Throwable? = null

    val invalidationCount: Int
        get() = lock.withLock { _invalidationCount }

    val totalCollects: Int
        get() = lock.withLock { _totalCollects }

    override val signals: Flow<Unit> = delegate.signals.map { signal ->
        val errorToThrow = lock.withLock {
            _totalCollects++
            val error = pendingSignalError
            pendingSignalError = null
            error
        }
        errorToThrow?.let { throw it }
        signal
    }

    fun throwOnNextSignal(throwable: Throwable) = lock.withLock {
        pendingSignalError = throwable
    }

    fun reset() = lock.withLock {
        _invalidationCount = 0
        _totalCollects = 0
        pendingSignalError = null
    }

    override fun invalidate() {
        lock.withLock { _invalidationCount++ }
        delegate.invalidate()
    }
}