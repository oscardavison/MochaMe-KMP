package com.mochame.sync.common

import co.touchlab.kermit.Logger
import com.mochame.sync.api.exceptions.MochaException
import com.mochame.sync.spi.infrastructure.serialization.VersionRouter
import com.mochame.sync.spi.infrastructure.serialization.getCodec

// -------------------------------------------------------------------
// CURRENTLY NOT IN USE - SWITCHED TO INT FOR VERSIONING
// -------------------------------------------------------------------

/**
 * Establishes a new ByteArray, appending the version before utilizing [ByteArray.copyInto] on the
 * result from [block]. Note that any lambda parameter (like [block]) is implicitly
 * inline as well.
 *
 * @return ByteArray the result of the execution performed, appended with the version
 * at index 0.
 * @throws MochaException.Transient.StateIssue if [block] failed to compute a
 * returning ByteArray
 */
inline fun prependVersionTo(
    version: Byte,
    logger: Logger,
    block: () -> ByteArray?
): ByteArray {
    val payload = block() ?: run {
        logger.e { "Cannot prepend version. Provided execution block returned a null ByteArray." }
        throw MochaException.Transient.StateIssue("Cannot prepend version. Execution returned null ByteArray.")
    }

    logger.d { "Encoding processed | Size: ${payload.size} bytes | Version Header: $version" }

    val envelope = ByteArray(1 + payload.size)
    envelope[0] = version
    payload.copyInto(envelope, destinationOffset = 1)
    return envelope
}

/**
 * Utilizes [versionRegistry] to provide [block]
 * as an anonymous lambda expression passing through [T] alongside the stripped [bytes].
 *
 * In the intended use case, all version routing methods call this method, and
 * therefore check bytes exist, strip the version, map the codec, and proceed
 * with their own codec usage via [block]. Block is implicitely inline.
 *
 * Future me start here for time and space complexity.
 *
 * @param T The type of versioned component to be passed back to the callers [block].
 * @param R The return result of [block], allowing the caller to capture this defined
 * return result after utilizing the provided [T] and stripped [bytes].
 * @param bytes the payload to be processed - must hold its version at index 0.
 */
inline fun <T : Any, R> VersionRouter<T>.stripAndVersion(
    bytes: ByteArray,
    version: Byte,
    logger: Logger,
    block: (T, ByteArray) -> R
): R {
    if (bytes.isEmpty()) {
        logger.e { "Attempt to strip a version made against a null ByteArray." }
        throw MochaException.Transient.StateIssue("Empty payload received.")
    }

    val codec = getCodec(version, logger)
    val strippedPayload = bytes.copyOfRange(1, bytes.size)

    return block(codec, strippedPayload)
}

/**
 * Version stripping and codec versioning through [Source.readByte].
 */
//inline fun <T, R> stripAndVersion(
//    source: Source,
//    versionMap: Map<Byte, T>,
//    logger: Logger,
//    block: (T, Source) -> R
//): R {
//    if (source.exhausted()) {
//        throw MochaException.Persistent.CorruptionDetected("Empty payload envelope received.")
//    }
//
//    val version = source.readByte()
//    val codec = versionMap[version] ?: run {
//        logger.e { "Unable to fetchAny codec. Unknown protocol version byte: $version" }
//        throw MochaException.Persistent.UnknownProtocolVersion(version)
//    }
//
//    return block(codec, source)
//}

