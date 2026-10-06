package com.mochame.sync.internal.fixtures.node

import com.mochame.sync.api.boot.BootState
import com.mochame.sync.api.boot.BootStatusProvider
import com.mochame.sync.api.boot.BootStatusUpdater
import com.mochame.sync.infrastructure.boot.DefaultBootStatusManager
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class SpyBootStatusManager(
    private val initialState: BootState = BootState.Idle,
    timeout: Duration = 5.seconds,
    private val delegate: DefaultBootStatusManager = DefaultBootStatusManager(initialState, timeout)
) : BootStatusProvider by delegate, BootStatusUpdater {
    private val lock = reentrantLock()
    private val _history = mutableListOf(initialState)

    val history: List<BootState>
        get() = lock.withLock { _history.toList() }

    override fun updateState(newState: BootState) {
        lock.withLock {
            _history.add(newState)
        }
        delegate.updateState(newState)
    }

    fun reset(initialState: BootState = BootState.Idle) {
        lock.withLock {
            _history.clear()
            _history.add(initialState)
        }
        delegate.updateState(initialState)
    }
}
