package com.mochame.sync.internal.fixtures.infrastructure

import com.mochame.sync.api.network.NetworkConfig
import com.mochame.sync.api.network.SendResult
import com.mochame.sync.api.network.SyncTransport
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock

class FakeSyncTransport(
    initialConnected: Boolean = true,
    initialSendResult: Boolean = true
) : SyncTransport {

    private val lock = reentrantLock()

    private var _isConnected: Boolean = initialConnected
    private var _failWith: Exception? = null
    private var _sendResult: Boolean = initialSendResult
    private var _nextSendResult: SendResult? = null
    private var _onSendHook: (suspend (batchId: Long, payload: ByteArray) -> Unit)? = null
    private var _autoAck: Boolean = false
    private var _nextAckWatermark: Long = 1L

    private val _sentBatches = mutableListOf<SentBatch>()
    private val _connectCalls = mutableListOf<ConnectCall>()
    private var _pauseCallCount = 0
    private var _resumeCallCount = 0

    private var _inboundDeltaHandler: (suspend (watermark: Long, payload: ByteArray) -> Unit)? = null
    private var _inboundAckHandler: (suspend (batchId: Long, watermark: Long) -> Unit)? = null
    private var _onConnectedListener: (suspend () -> Unit)? = null
    private var _onDisconnectedListener: (suspend () -> Unit)? = null

    data class ConnectCall(val config: NetworkConfig) {
        val host: String = config.host
        val port: Int = config.port
        val groupId: String = config.groupId
    }
    data class SentBatch(val batchId: Long, val payload: ByteArray)

    override var isConnected: Boolean
        get() = lock.withLock { _isConnected }
        set(value) = lock.withLock { _isConnected = value }

    var failWith: Exception?
        get() = lock.withLock { _failWith }
        set(value) = lock.withLock { _failWith = value }

    /**
     * Backwards-compatible to original.
     */
    var sendResult: Boolean
        get() = lock.withLock { _sendResult }
        set(value) = lock.withLock { _sendResult = value }

    /**
     * One-shot explicit result override to simulate intermediate network states
     * (e.g., SendResult.NoConnection or specific SendResult.Failure causes)
     * without decoupling the ambient [isConnected] status.
     */
    var nextSendResult: SendResult?
        get() = lock.withLock { _nextSendResult }
        set(value) = lock.withLock { _nextSendResult = value }

    /**
     * Suspending hook invoked when a batch reaches transmission.
     */
    var onSendHook: (suspend (batchId: Long, payload: ByteArray) -> Unit)?
        get() = lock.withLock { _onSendHook }
        set(value) = lock.withLock { _onSendHook = value }

    var autoAck: Boolean
        get() = lock.withLock { _autoAck }
        set(value) = lock.withLock { _autoAck = value }

    var nextAckWatermark: Long
        get() = lock.withLock { _nextAckWatermark }
        set(value) = lock.withLock { _nextAckWatermark = value }

    val sentBatches: List<SentBatch>
        get() = lock.withLock { _sentBatches.map { it.copy(payload = it.payload.copyOf()) } }

    val sentPayloads: List<ByteArray>
        get() = lock.withLock { _sentBatches.map { it.payload.copyOf() } }

    val connectCalls: List<ConnectCall>
        get() = lock.withLock { _connectCalls.toList() }

    val pauseCallCount: Int
        get() = lock.withLock { _pauseCallCount }

    val resumeCallCount: Int
        get() = lock.withLock { _resumeCallCount }

    override suspend fun connect(config: NetworkConfig) {
        val listener = lock.withLock {
            _connectCalls.add(ConnectCall(config))
            _isConnected = true
            _onConnectedListener
        }
        listener?.invoke()
    }

    override suspend fun send(batchId: Long, payload: ByteArray): SendResult {
        var hookToRun: (suspend () -> Unit)? = null
        var ackToRun: (suspend () -> Unit)? = null

        val result: SendResult = lock.withLock {
            _onSendHook?.let { hook ->
                hookToRun = { hook(batchId, payload) }
            }

            _failWith?.let {
                _failWith = null
                return@withLock SendResult.Failure(it)
            }

            val override = _nextSendResult
            if (override != null) {
                _nextSendResult = null
                if (override is SendResult.Success) {
                    _sentBatches.add(SentBatch(batchId, payload.copyOf()))
                    if (_autoAck) {
                        val watermark = _nextAckWatermark++
                        _inboundAckHandler?.let { handler ->
                            ackToRun = { handler(batchId, watermark) }
                        }
                    }
                }
                return@withLock override
            }

            if (!_isConnected) {
                return@withLock SendResult.NoConnection
            }

            _sentBatches.add(SentBatch(batchId, payload.copyOf()))

            if (_autoAck) {
                val watermark = _nextAckWatermark++
                _inboundAckHandler?.let { handler ->
                    ackToRun = { handler(batchId, watermark) }
                }
            }

            SendResult.Success
        }

        // Suspending invocations executed outside thread lock
        hookToRun?.invoke()
        ackToRun?.invoke()

        return result
    }

    override suspend fun pause() {
        lock.withLock {
            _pauseCallCount++
            _isConnected = false
        }
    }

    override suspend fun resume() {
        lock.withLock {
            _resumeCallCount++
            _isConnected = true
        }
    }

    override fun registerInboundDeltaHandler(onReceived: suspend (Long, ByteArray) -> Unit) {
        lock.withLock {
            _inboundDeltaHandler = onReceived
        }
    }

    override fun registerInboundAckHandler(onAck: suspend (batchId: Long, watermark: Long) -> Unit) {
        lock.withLock {
            _inboundAckHandler = onAck
        }
    }

    override fun setOnConnectedListener(onConnected: suspend () -> Unit) {
        lock.withLock {
            _onConnectedListener = onConnected
        }
    }

    override fun setOnDisconnectedListener(onDisconnected: suspend () -> Unit) {
        lock.withLock {
            _onDisconnectedListener = onDisconnected
        }
    }

    // --- Test Helpers ---

    suspend fun emitInboundDelta(watermark: Long, payload: ByteArray) {
        val handler = lock.withLock { _inboundDeltaHandler }
        handler?.invoke(watermark, payload)
    }

    suspend fun emitInbound(watermark: Long, payload: ByteArray) {
        emitInboundDelta(watermark, payload)
    }

    suspend fun emitInboundAck(batchId: Long, watermark: Long) {
        val handler = lock.withLock { _inboundAckHandler }
        handler?.invoke(batchId, watermark)
    }

    suspend fun triggerConnected() {
        val listener = lock.withLock { _onConnectedListener }
        listener?.invoke()
    }

    suspend fun triggerDisconnected() {
        val listener = lock.withLock {
            _isConnected = false
            _onDisconnectedListener
        }
        listener?.invoke()
    }

    fun reset() = lock.withLock {
        _sentBatches.clear()
        _connectCalls.clear()
        _pauseCallCount = 0
        _resumeCallCount = 0
        _isConnected = true
        _sendResult = true
        _nextSendResult = null
        _onSendHook = null
        _autoAck = false
        _nextAckWatermark = 1L
        _failWith = null
        _inboundDeltaHandler = null
        _inboundAckHandler = null
        _onConnectedListener = null
        _onDisconnectedListener = null
    }
}