package com.mochame.sync.internal.fixtures.serialization

import com.mochame.sync.domain.serialization.PayloadCodec
import com.mochame.sync.domain.model.SyncIntent
import kotlinx.atomicfu.locks.reentrantLock
import kotlinx.atomicfu.locks.withLock

class FakePayloadCodec : PayloadCodec {
    private val lock = reentrantLock()

    private var _encodeError: Exception? = null
    private var _remainingEncodeFailures: Int = 0

    private var _decodeError: Exception? = null
    private var _remainingDecodeFailures: Int = 0

    var encodeError: Exception?
        get() = lock.withLock { _encodeError }
        set(value) = lock.withLock {
            _encodeError = value
            _remainingEncodeFailures = if (value != null) 1 else 0
        }

    var decodeError: Exception?
        get() = lock.withLock { _decodeError }
        set(value) = lock.withLock {
            _decodeError = value
            _remainingDecodeFailures = if (value != null) 1 else 0
        }

    val remainingEncodeFailures: Int get() = lock.withLock { _remainingEncodeFailures }
    val remainingDecodeFailures: Int get() = lock.withLock { _remainingDecodeFailures }

    var nextDecodeResult: List<SyncIntent> = emptyList()

    private val _encodedInvocations = mutableListOf<List<SyncIntent>>()
    private val _decodedInvocations = mutableListOf<ByteArray>()

    val encodedInvocations: List<List<SyncIntent>>
        get() = lock.withLock { _encodedInvocations.toList() }

    val decodedInvocations: List<ByteArray>
        get() = lock.withLock { _decodedInvocations.toList() }

    val encodeCallCount: Int get() = lock.withLock { _encodedInvocations.size }
    val decodeCallCount: Int get() = lock.withLock { _decodedInvocations.size }

    /**
     * Configures the codec to throw on the next [times] invocations of [encode].
     */
    fun failNextEncodes(
        times: Int = 1,
        error: Exception = IllegalStateException("Simulated encode serialization failure")
    ): FakePayloadCodec = apply {
        require(times >= 0) { "Failure count cannot be negative: $times" }
        lock.withLock {
            _remainingEncodeFailures = times
            _encodeError = if (times > 0) error else null
        }
    }

    /**
     * Configures the codec to throw on the next [times] invocations of [decode].
     */
    fun failNextDecodes(
        times: Int = 1,
        error: Exception = IllegalStateException("Simulated decode deserialization failure")
    ): FakePayloadCodec = apply {
        require(times >= 0) { "Failure count cannot be negative: $times" }
        lock.withLock {
            _remainingDecodeFailures = times
            _decodeError = if (times > 0) error else null
        }
    }

    override fun encode(payload: List<SyncIntent>): ByteArray = lock.withLock {
        _encodedInvocations.add(payload)

        if (_remainingEncodeFailures > 0) {
            _remainingEncodeFailures--
            val error = _encodeError ?: IllegalStateException("Simulated encode failure")
            if (_remainingEncodeFailures == 0) {
                _encodeError = null
            }
            throw error
        }

        byteArrayOf(0x01)
    }

    override fun decode(bytes: ByteArray): List<SyncIntent> = lock.withLock {
        _decodedInvocations.add(bytes)

        if (_remainingDecodeFailures > 0) {
            _remainingDecodeFailures--
            val error = _decodeError ?: IllegalStateException("Simulated decode failure")
            if (_remainingDecodeFailures == 0) {
                _decodeError = null
            }
            throw error
        }

        nextDecodeResult
    }

    fun reset() = lock.withLock {
        _encodeError = null
        _remainingEncodeFailures = 0
        _decodeError = null
        _remainingDecodeFailures = 0
        nextDecodeResult = emptyList()
        _encodedInvocations.clear()
        _decodedInvocations.clear()
    }
}