package com.mochame.sync.spi.infrastructure.serialization

import co.touchlab.kermit.Logger
import com.mochame.sync.api.exceptions.MochaException


/**
 * Basic contract for all version control functionality.
 *
 * @param T Type of object assigned to a version on the [versionRegistry]
 * @property latestVersion assigned to a [latestCodec] as its key.
 * @property versionRegistry maps a byte value representing a version to whatever Type is provided.
 */
interface VersionRouter<T : Any> {
    /**
     * The array index of the latest item. Assumes that there may need to be the flexibility of
     * adding a version that is not the latest version.
     */
    val latestVersion: Int

    /**
     * Nullable in case version 1 is the starting point, in which case index 0 is to be null.
     */
    val versionRegistry: Array<T?>
}

/**
 * Property initializers in interfaces are prohibited. Interfaces are just contracts.
 * Therefore, Classes that want basic version routing mapping dont need to extend a
 * base class that implements an interface. They simply implement the interface directly, become
 * the type, and as this extension property has a receiver type of that type
 * , the class gets implicit uniform access to the latest codec without having
 * to repeat logic, or depend on a tool such as VersionRoutingUtils.getLatestCodec().
 *
 * @param T The Type for the object that is being version controlled and fetched.
 * @throws MochaException.Transient.StateIssue If the version is not an index
 */
val <T : Any> VersionRouter<T>.latestCodec: T
    get() = versionRegistry.getOrNull(latestVersion)
        ?: throw MochaException.Persistent.UnknownProtocolVersion(latestVersion)

fun <T : Any> VersionRouter<T>.getCodec(
    version: Int,
    logger: Logger,
    contextKey: String? = null
): T {
    val codec = versionRegistry.getOrNull(version)
    if (codec == null) {
        logger.e {
            "Protocol mismatch | requested=v$version latest=v$latestVersion" +
                    (contextKey?.let { " key=$it" } ?: "")
        }
        throw MochaException.Persistent.UnknownProtocolVersion(version)
    }
    if (version < latestVersion) {
        logger.w {
            "Schema divergence | decoding legacy v$version payload" +
                    (contextKey?.let { " key=$it" } ?: "")
        }
    }
    return codec
}

// Not tested or used.
fun <T : Any> VersionRouter<T>.getCodec(version: Byte, logger: Logger): T {
    val index = (version.toInt() and 0xFF)
    return versionRegistry.getOrNull(index)
        ?: throw MochaException.Persistent.UnknownProtocolVersion(latestVersion)
}