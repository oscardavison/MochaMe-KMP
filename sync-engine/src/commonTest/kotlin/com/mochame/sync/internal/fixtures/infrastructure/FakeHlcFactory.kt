package com.mochame.sync.internal.fixtures.infrastructure

import com.mochame.sync.api.hlc.HLC
import com.mochame.sync.api.hlc.HlcFactory
import com.mochame.sync.spi.node.NodeId
import com.mochame.utils.fixtures.FakeTimeUtils
import com.mochame.utils.fixtures.TestHlcFactory
import com.mochame.utils.fixtures.TestNodeId
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock

class FakeHlcFactory(
    private val timeUtils: FakeTimeUtils
) : HlcFactory {

    private val lock = reentrantLock()

    private var currentHlc: HLC = TestHlcFactory.create()
    private var currentNodeId: NodeId? = null
    private var pendingError: Throwable? = null

    // --- Telemetry ---
    private val _generatedHlcs = mutableListOf<HLC>()
    private val _witnessedHlcs = mutableListOf<HLC>()
    private var _getNextHlcCallCount = 0

    val generatedHlcs: List<HLC>
        get() = lock.withLock { _generatedHlcs.toList() }

    val witnessedHlcs: List<HLC>
        get() = lock.withLock { _witnessedHlcs.toList() }

    val getNextHlcCallCount: Int
        get() = lock.withLock { _getNextHlcCallCount }

    // --- HlcFactory Implementations ---

    override suspend fun hydrate(lastKnownHlc: HLC?, currentNodeId: NodeId): HLC = lock.withLock {
        checkAndThrowPendingError()

        this.currentNodeId = currentNodeId
        val hydrated = lastKnownHlc?.copy(nodeId = currentNodeId)
            ?: currentHlc.copy(nodeId = currentNodeId)
        currentHlc = hydrated
        hydrated
    }

    override suspend fun getNextHlc(): HLC = lock.withLock {
        checkAndThrowPendingError()

        val next = TestHlcFactory.create(
            ts = currentHlc.ts + 1L,
            count = 0,
            nodeId = currentNodeId ?: TestNodeId.A
        )
        currentHlc = next
        _generatedHlcs.add(next)
        _getNextHlcCallCount++
        next
    }

    override suspend fun witness(remoteHlc: HLC): Unit = lock.withLock {
        checkAndThrowPendingError()

        val last = currentHlc
        val nodeId = currentNodeId ?: return@withLock

        val deviceClock = timeUtils.now().toEpochMilliseconds()
        val newTs = maxOf(deviceClock, last.ts, remoteHlc.ts)

        val newCount = when {
            newTs == last.ts && newTs == remoteHlc.ts -> maxOf(last.count, remoteHlc.count)
            newTs == remoteHlc.ts -> remoteHlc.count
            newTs == last.ts -> last.count
            else -> 0
        }

        val witnessed = HLC(newTs, newCount, nodeId)
        currentHlc = witnessed
        _witnessedHlcs.add(remoteHlc)
    }

    override suspend fun getCurrentHlc(): HLC? = lock.withLock {
        currentHlc
    }

    override fun assertValid(hlc: HLC, contextKey: Long?) =
        lock.withLock { checkAndThrowPendingError() }

    // --- Test Helpers ---

    fun setExplicitCurrentHlc(hlc: HLC, nodeId: NodeId) = lock.withLock {
        currentHlc = hlc
        currentNodeId = nodeId
    }

    fun throwOnNextOperation(throwable: Throwable) = lock.withLock {
        pendingError = throwable
    }

    fun reset() = lock.withLock {
        currentHlc = TestHlcFactory.create()
        currentNodeId = null
        _generatedHlcs.clear()
        _witnessedHlcs.clear()
        _getNextHlcCallCount = 0
        pendingError = null
    }

    private fun checkAndThrowPendingError() {
        val error = pendingError
        pendingError = null
        error?.let { throw it }
    }
}
