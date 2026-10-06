package com.mochame.sync.api.codec

import co.touchlab.kermit.Logger
import com.mochame.sync.api.utils.getCodec
import com.mochame.sync.api.utils.latestCodec
import com.mochame.sync.api.models.LocalFirstDelta
import com.mochame.sync.api.models.LocalFirstEntity

/**
 * Base resolver that routes serialization and delta decoding for [T] across schema versions.
 *
 * Feature modules extend this to supply their versioned [FeatureCodec] registry.
 *
 * @param T The local-first entity type handled by this resolver.
 * @property latestVersion The current active schema version index.
 * @property versionRegistry Codec implementations indexed by their schema version.
 */
abstract class BaseCodecResolver<T : LocalFirstEntity<T>>(
    override val latestVersion: Int,
    override val versionRegistry: Array<FeatureCodec<T>?>,
    protected val logger: Logger
) : CodecResolver<T, FeatureCodec<T>> {

    /** Serializes changes using the latest codec. */
    override fun versionEncode(new: T, old: T?): ByteArray = latestCodec.encode(new, old)

    /** Deserializes a raw payload into a [LocalFirstDelta] using the codec matching [version]. */
    override fun versionDecode(
        version: Int,
        data: ByteArray,
        logger: Logger,
        primaryKey: Long
    ): LocalFirstDelta = getCodec(version, logger, primaryKey).deserializeDelta(data)

    /**
     * Generates a human-readable summary of the payload using the version specified in [context].
     * Currently exists for debugging only.
     */
    override fun versionReconstructSummary(
        data: ByteArray,
        featureSchemaVersion: Int
    ): String = getCodec(featureSchemaVersion, logger).reconstructSummary(data)

    /** Computes field tags modified between [old] and [new] using the versioned codec. */
    override fun versionComputeChangedTags(new: T, old: T?): List<Int> =
        latestCodec.computeChangedTags(new, old)
}
