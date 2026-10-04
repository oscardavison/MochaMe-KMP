package com.mochame.sync.internal.fixtures.infrastructure

import com.mochame.sync.domain.infrastructure.SyncWorkerHook
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map

class FakeSyncWorkerHook : SyncWorkerHook {

    private val lock = reentrantLock()

    private val _signals = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    private var _invalidationCount = 0
    private var _totalCollects = 0
    private var pendingSignalError: Throwable? = null

    val invalidationCount: Int
        get() = lock.withLock { _invalidationCount }

    val totalCollects: Int
        get() = lock.withLock { _totalCollects }

    override val signals: Flow<Unit> = _signals.asSharedFlow().map { signal ->
        val errorToThrow = lock.withLock {
            _totalCollects++
            val error = pendingSignalError
            pendingSignalError = null
            error
        }
        errorToThrow?.let { throw it }
        signal
    }

    override fun invalidate() {
        lock.withLock {
            _invalidationCount++
        }
        _signals.tryEmit(Unit)
    }

    fun emitSignal() {
        _signals.tryEmit(Unit)
    }

    fun throwOnNextSignal(throwable: Throwable) = lock.withLock {
        pendingSignalError = throwable
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun reset() = lock.withLock {
        _invalidationCount = 0
        _totalCollects = 0
        pendingSignalError = null
        _signals.resetReplayCache()
    }
}
