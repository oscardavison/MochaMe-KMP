package com.mochame.platform.fixtures

import com.mochame.sync.spi.TransactionProvider
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock


/**
 * No database link, just runs the block.
 */
class FakeTransactionProvider : TransactionProvider {

    private val lock = reentrantLock()

    private var _executionCount = 0
    val executionCount
        get() = lock.withLock { _executionCount }

    /**
     * One time throw. Resets to null after use.
     */
    private var _shouldThrow: Exception? = null
    var shouldThrow: Exception?
        get() = lock.withLock { _shouldThrow }
        set(value) = lock.withLock { _shouldThrow = value }

    override suspend fun <R> runImmediateTransaction(block: suspend () -> R): R {
        lock.withLock {
            _executionCount++
            shouldThrow?.let {
                _shouldThrow = null
                throw it
            }
        }

        return block()
    }

    fun reset() {
        lock.withLock {
            _executionCount = 0
            _shouldThrow = null
        }
    }
}