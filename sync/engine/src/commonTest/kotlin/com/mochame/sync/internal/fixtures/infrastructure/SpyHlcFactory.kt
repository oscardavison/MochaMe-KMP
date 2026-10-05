package com.mochame.sync.internal.fixtures.infrastructure

import co.touchlab.kermit.Logger
import com.mochame.sync.api.models.HLC
import com.mochame.sync.domain.hlc.HlcFactory
import com.mochame.sync.domain.hlc.EngineHlcFactory
import com.mochame.sync.spi.node.NodeId
import com.mochame.utils.fixtures.FakeTimeUtils
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock

class SpyHlcFactory(
    clock: FakeTimeUtils,
    private val logger: Logger
) : HlcFactory {

    private val lock = reentrantLock()

    private val realFactory = EngineHlcFactory(
        timeUtils = clock,
        logger = logger
    )

    // --- Backing Fields ---
    private val _generatedHlcs = mutableListOf<HLC>()
    private val _witnessedHlcs = mutableListOf<HLC>()
    private var _getNextHlcCallCount = 0

    // Hydration tracking
    private var _hydrateCallCount = 0
    private var _lastHydratedHlc: HLC? = null
    private var _lastHydratedNodeId: NodeId? = null

    // --- Read-Only Properties ---
    val generatedHlcs: List<HLC>
        get() = lock.withLock { _generatedHlcs.toList() }

    val witnessedHlcs: List<HLC>
        get() = lock.withLock { _witnessedHlcs.toList() }

    val getNextHlcCallCount: Int
        get() = lock.withLock { _getNextHlcCallCount }

    val hydrateCallCount: Int
        get() = lock.withLock { _hydrateCallCount }

    val lastHydratedHlc: HLC?
        get() = lock.withLock { _lastHydratedHlc }

    val lastHydratedNodeId: NodeId?
        get() = lock.withLock { _lastHydratedNodeId }

    fun reset() = lock.withLock {
        _generatedHlcs.clear()
        _witnessedHlcs.clear()
        _getNextHlcCallCount = 0
        _hydrateCallCount = 0
        _lastHydratedHlc = null
        _lastHydratedNodeId = null
    }

    override suspend fun hydrate(lastKnownHlc: HLC?, currentNodeId: NodeId): HLC {
        lock.withLock {
            _hydrateCallCount++
            _lastHydratedHlc = lastKnownHlc
            _lastHydratedNodeId = currentNodeId
        }
        return realFactory.hydrate(lastKnownHlc, currentNodeId)
    }

    override suspend fun getNextHlc(): HLC {
        val nextHlc = realFactory.getNextHlc()

        lock.withLock {
            _generatedHlcs.add(nextHlc)
            _getNextHlcCallCount++
        }
        return nextHlc
    }

    override suspend fun witness(remoteHlc: HLC) {
        lock.withLock {
            _witnessedHlcs.add(remoteHlc)
        }

        return realFactory.witness(remoteHlc)
    }

    override suspend fun getCurrentHlc(): HLC? = lock.withLock {
        realFactory.getCurrentHlc()
    }

    override fun assertValid(hlc: HLC, contextKey: Long?) =
        realFactory.assertValid(hlc, contextKey)
}