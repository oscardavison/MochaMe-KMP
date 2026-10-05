package com.mochame.sync.spi.infrastructure

import kotlinx.io.Source

/**
 * Defines the contract for how the platform actuals should
 * manage the digestion of an incoming source, for hashing.
 * This component is stateful.
 */
interface DigestState {
    fun update(source: Source)
    fun digest(): ByteArray
}

/**
 * SAM - call this interface and invoke an anonymous Digest object
 * per call. Thread safe and handles digest state carefully.
 * This component is a Factory.
 */
fun interface DigestFactory {
    operator fun invoke(): DigestState
}

/**
 * Chain the final digestion of the hashed byte array to a hex string.
 */
fun DigestState.digestHex(): String = digest().toHexString()