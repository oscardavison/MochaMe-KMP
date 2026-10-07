package com.mochame.sync.internal.fixtures.node

import com.mochame.sync.api.exceptions.toMochaException
import com.mochame.sync.domain.policy.ExecutionPolicy
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock

class FakeExecutionPolicy : ExecutionPolicy {

    private val lock = reentrantLock()

    private val _executionHistory = mutableListOf<String>()
    val executionHistory: List<String>
        get() = lock.withLock { _executionHistory.toList() }

    private var _executionCount = 0
    val executionCount: Int
        get() = lock.withLock { _executionCount }

    private var simulatedFailuresLeft = 0
    private var simulatedException: Throwable? = null

    /**
     * Set up a rule where the next [count] executions will throw [exception].
     */
    fun failConsecutively(count: Int, exception: Exception) = lock.withLock {
        simulatedFailuresLeft = count
        simulatedException = exception
    }

    override suspend fun <R> execute(operationTag: String, block: suspend () -> R): R {
        val exceptionToThrow = lock.withLock {
            _executionHistory.add(operationTag)
            _executionCount++

            if (simulatedFailuresLeft > 0) {
                simulatedFailuresLeft--
                simulatedException
            } else {
                null
            }
        }

        if (exceptionToThrow != null) {
            throw exceptionToThrow
        }

        return try {
            block()
        } catch (e: Exception) {
            throw e.toMochaException(e.message)
        }
    }

    fun reset() = lock.withLock {
        _executionHistory.clear()
        _executionCount = 0
        simulatedFailuresLeft = 0
        simulatedException = null
    }
}