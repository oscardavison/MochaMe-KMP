package com.mochame.sync.internal.fixtures.api

import com.mochame.sync.api.metadata.FeatureContext
import com.mochame.sync.spi.infrastructure.SyncReceiver
import com.mochame.sync.spi.models.DecodeContext
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock

data class ReceivedIntent(
    val context: DecodeContext,
    val payload: ByteArray?
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ReceivedIntent) return false
        if (context != other.context) return false
        return payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = context.hashCode()
        result = 31 * result + (payload?.contentHashCode() ?: 0)
        return result
    }
}

class FakeSyncReceiver(
    override val featureContext: FeatureContext
) : SyncReceiver {

    private val lock = reentrantLock()

    // --- Backing Fields ---
    private var _failure: Throwable? = null
    private val _invocations = mutableListOf<ReceivedIntent>()
    private var _onProcessHook: (suspend (DecodeContext, ByteArray?) -> Unit)? = null


    // --- Inspection ---
    val invocations: List<ReceivedIntent>
        get() = lock.withLock { _invocations.toList() }

    val lastInvocation: ReceivedIntent?
        get() = lock.withLock { _invocations.lastOrNull() }

    val invocationCount: Int
        get() = lock.withLock { _invocations.size }

    // --- Controls ---
    var shouldFail: Throwable?
        get() = lock.withLock { _failure }
        set(value) = lock.withLock { _failure = value }

    var onProcessHook: (suspend (DecodeContext, ByteArray?) -> Unit)?
        get() = lock.withLock { _onProcessHook }
        set(value) = lock.withLock { _onProcessHook = value }

    fun reset() = lock.withLock {
        _failure = null
        _onProcessHook = null
        _invocations.clear()
    }

    override suspend fun processRemoteIntent(context: DecodeContext, payload: ByteArray?) {
        val (error, hook) = lock.withLock {
            _invocations.add(ReceivedIntent(context, payload))
            val error = _failure
            _failure = null
            Pair(error, _onProcessHook)
        }

        error?.let { throw it }
        hook?.invoke(context, payload)
    }
}