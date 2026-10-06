package com.mochame.platform.fixtures

import com.mochame.sync.spi.DigestFactory
import com.mochame.sync.spi.DigestState
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.io.Source
import kotlinx.io.readByteArray

class FakeDigestFactory(
    private val deterministicId: String? = null
) : DigestFactory {
    private val lock = reentrantLock()
    private var _invokeCount = 0
    private var _shouldThrow: Exception? = null

    val invokeCount: Int get() = lock.withLock { _invokeCount }

    var shouldThrow: Exception?
        get() = lock.withLock { _shouldThrow }
        set(value) = lock.withLock { _shouldThrow = value }

    override fun invoke(): DigestState {
        lock.withLock { _invokeCount++ }
        return FakeDigestState(deterministicId, _shouldThrow)
    }

    fun reset() = lock.withLock {
        _invokeCount = 0
        _shouldThrow = null
    }
}

class FakeDigestState(
    private val deterministicId: String? = null,
    initialError: Exception? = null
) : DigestState {

    private val lock = reentrantLock()
    private val buffer = mutableListOf<Byte>()
    private var _shouldThrow: Exception? = initialError

    override fun update(source: Source) {
        lock.withLock {
            _shouldThrow?.let { throw it }
            val snapshot = source.peek().readByteArray()
            buffer.addAll(snapshot.toList())
        }
    }

    override fun digest(): ByteArray {
        val result = ByteArray(32)
        buffer.forEachIndexed { index, byte ->
            result[index % 32] = (result[index % 32] + byte).toByte()
        }
        return result
    }
}