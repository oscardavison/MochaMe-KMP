package com.mochame.sync.internal.fixtures

import com.mochame.sync.domain.stores.QuarantinedPayloadStore
import com.mochame.sync.domain.model.QuarantinedPayload
import com.mochame.utils.interfaces.TimeUtils
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock

class FakeQuarantinedPayloadStore(
    private val timeUtils: TimeUtils
) : QuarantinedPayloadStore {

    private val lock = reentrantLock()

    private val _payloads = linkedMapOf<Long, QuarantinedPayload>()

    private var _failWith: Exception? = null
    private var _recordCallCount: Int = 0

    var failWith: Exception?
        get() = lock.withLock { _failWith }
        set(value) = lock.withLock { _failWith = value }

    val recordCallCount: Int
        get() = lock.withLock { _recordCallCount }

    val payloads: List<QuarantinedPayload>
        get() = lock.withLock {
            _payloads.values.map { it.copy(rawPayload = it.rawPayload.copyOf()) }
        }

    fun seed(vararg items: QuarantinedPayload) {
        seed(items.asList())
    }

    fun seed(items: Collection<QuarantinedPayload>) = lock.withLock {
        items.forEach { _payloads[it.watermark] = it.copy(rawPayload = it.rawPayload.copyOf()) }
    }

    fun reset() = lock.withLock {
        _payloads.clear()
        _failWith = null
        _recordCallCount = 0
    }

    override suspend fun record(
        watermark: Long,
        rawPayload: ByteArray,
        failureReason: String
    ) = lock.withLock {
        _failWith?.let {
            _failWith = null
            throw it
        }

        _recordCallCount++
        _payloads[watermark] = QuarantinedPayload(
            watermark = watermark,
            rawPayload = rawPayload.copyOf(),
            failureReason = failureReason,
            receivedAt = timeUtils.now().toEpochMilliseconds()
        )
    }

    override suspend fun getAll(): List<QuarantinedPayload> = lock.withLock {
        _failWith?.let {
            _failWith = null
            throw it
        }

        _payloads.values
            .sortedBy { it.watermark }
            .map { it.copy(rawPayload = it.rawPayload.copyOf()) }
    }

    override suspend fun getByWatermark(watermark: Long): QuarantinedPayload? = lock.withLock {
        _failWith?.let {
            _failWith = null
            throw it
        }

        _payloads[watermark]?.let { it.copy(rawPayload = it.rawPayload.copyOf()) }
    }

    override suspend fun deleteByWatermark(watermark: Long) = lock.withLock {
        _failWith?.let {
            _failWith = null
            throw it
        }

        _payloads.remove(watermark)
        Unit
    }

    override suspend fun pruneOlderThan(timestamp: Long): Int = lock.withLock {
        _failWith?.let {
            _failWith = null
            throw it
        }

        var prunedCount = 0
        val iterator = _payloads.values.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            if (item.receivedAt < timestamp) {
                iterator.remove()
                prunedCount++
            }
        }
        prunedCount
    }
}