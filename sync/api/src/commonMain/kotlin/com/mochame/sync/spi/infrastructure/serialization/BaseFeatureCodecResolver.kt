package com.mochame.sync.spi.infrastructure.serialization

import co.touchlab.kermit.Logger
import com.mochame.sync.api.models.LocalFirstEntity
import com.mochame.sync.spi.models.DecodeContext

abstract class BaseFeatureCodecResolver<T : LocalFirstEntity<T>>(
    override val latestVersion: Int,
    override val versionRegistry: Array<FeatureCodec<T>?>,
    protected val logger: Logger
) : FeatureCodecResolver<T, FeatureCodec<T>> {

    override fun versionEncode(new: T, old: T?): ByteArray = latestCodec.encode(new, old)

    override fun versionDecode(data: ByteArray, context: DecodeContext, existing: T?): T =
        getCodec(context.featureSchemaVersion, logger).decode(data, context, existing)

    override fun versionReconstructSummary(
        data: ByteArray,
        context: DecodeContext
    ): String = getCodec(context.featureSchemaVersion, logger).reconstructSummary(data)

    override fun routedComputeChangedTags(new: T, old: T?): List<Int> =
        latestCodec.computeChangedTags(new, old)
}
