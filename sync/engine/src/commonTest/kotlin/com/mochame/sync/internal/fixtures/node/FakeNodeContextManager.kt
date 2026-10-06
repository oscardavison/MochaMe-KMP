package com.mochame.sync.internal.fixtures.node

import com.mochame.sync.domain.infrastructure.NodeContextManager
import com.mochame.sync.api.models.NodeContext
import com.mochame.sync.api.models.HLC
import com.mochame.sync.api.models.NodeId
import com.mochame.utils.fixtures.TestNodeId
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Instant

class FakeNodeContextManager(
    private val defaultNodeId: NodeId = TestNodeId.A
) : NodeContextManager {

    private val lock = reentrantLock()

    private var _seededContext: NodeContext? = null
    private var _forcedNextNodeId: NodeId? = null

    private val _updatedHlcFloors = mutableListOf<HLC>()
    private val _recognizedServerResponses = mutableListOf<Pair<Long, Instant>>()
    private var _getOrEstablishCallCount = 0
    private var _setAppVersionCallCount = 0
    private var _simulatedDelay: Duration? = null

    val updatedHlcFloors: List<HLC>
        get() = lock.withLock { _updatedHlcFloors.toList() }

    val recognizedServerResponses: List<Pair<Long, Instant>>
        get() = lock.withLock { _recognizedServerResponses.toList() }

    val getOrEstablishCallCount: Int
        get() = lock.withLock { _getOrEstablishCallCount }

    val setAppVersionCallCount: Int
        get() = lock.withLock { _setAppVersionCallCount }

    var seededContext: NodeContext?
        get() = lock.withLock { _seededContext }
        set(value) = lock.withLock { _seededContext = value }

    var simulatedDelay: Duration?
        get() = lock.withLock { _simulatedDelay }
        set(value) = lock.withLock { _simulatedDelay = value }

    var forcedNextNodeId: NodeId?
        get() = lock.withLock { _forcedNextNodeId }
        set(value) = lock.withLock { _forcedNextNodeId = value }

    private fun getOrInitializeLocked(baseVersion: Int = 0): NodeContext {
        val current = _seededContext ?: NodeContext(
            nodeId = _forcedNextNodeId ?: defaultNodeId,
            appVersion = baseVersion,
            lastServerResponseTime = null,
            maxHlc = null,
            lastInboundWatermark = null,
        )
        _seededContext = current
        return current
    }

    fun reset() = lock.withLock {
        _seededContext = null
        _forcedNextNodeId = null
        _updatedHlcFloors.clear()
        _recognizedServerResponses.clear()
        _getOrEstablishCallCount = 0
        _setAppVersionCallCount = 0
        _simulatedDelay = null
    }

    override suspend fun getOrEstablishContext(baseVersion: Int): NodeContext {
        val delayDuration = lock.withLock {
            _getOrEstablishCallCount++
            _simulatedDelay
        }

        if (delayDuration != null) {
            delay(delayDuration)
        }

        return lock.withLock {
            getOrInitializeLocked(baseVersion)
        }
    }

    override suspend fun setAppVersion(targetVersion: Int) = lock.withLock {
        _setAppVersionCallCount++
        _seededContext =
            getOrInitializeLocked(targetVersion).copy(appVersion = targetVersion)
    }

    override suspend fun getLastBootedAppVersion(): Int? = lock.withLock {
        _seededContext?.appVersion
    }

    override suspend fun getLastServerResponseTime(): Instant? = lock.withLock {
        _seededContext?.lastServerResponseTime
    }

    override suspend fun getLastInboundWatermark(): Long? = lock.withLock {
        _seededContext?.lastInboundWatermark
    }

    override suspend fun getNodeId(): NodeId? = lock.withLock {
        _seededContext?.nodeId ?: _forcedNextNodeId
    }

    override suspend fun getMaxHlc(): HLC? = lock.withLock {
        _seededContext?.maxHlc
    }

    override suspend fun updateHlcFloor(hlc: HLC) = lock.withLock {
        _updatedHlcFloors.add(hlc)
        _seededContext = getOrInitializeLocked().copy(maxHlc = hlc)
    }

    override suspend fun overwriteNodeContext(nodeContext: NodeContext) = lock.withLock {
        _seededContext = nodeContext
    }

    override suspend fun commitInboundWatermark(watermark: Long, timestamp: Instant) =
        lock.withLock {
            _recognizedServerResponses.add(watermark to timestamp)
            _seededContext = getOrInitializeLocked().copy(
                lastInboundWatermark = watermark,
                lastServerResponseTime = timestamp
            )
        }

    override suspend fun commitOutboundWatermark(
        watermark: Long,
        timestamp: Instant
    ) = lock.withLock {
        _recognizedServerResponses.add(watermark to timestamp)
        _seededContext = getOrInitializeLocked().copy(
            lastOutboundWatermark = watermark,
            lastServerResponseTime = timestamp
        )
    }

    fun getLastOutboundWatermark() = lock.withLock { _seededContext?.lastOutboundWatermark ?: 0L }
}
