package com.mochame.sync.spi.infrastructure.serialization

import co.touchlab.kermit.Logger
import com.mochame.sync.api.hlc.HLC
import com.mochame.sync.api.models.LocalFirstEntity
import com.mochame.sync.spi.models.DecodeContext

abstract class BaseFeatureCodecRouter<T : LocalFirstEntity<T>>(
    override val latestVersion: Int,
    override val versionRegistry: Array<FeatureCodec<T>?>,
    protected val logger: Logger
) : FeatureCodecRouter<T, FeatureCodec<T>> {

    override fun routedEncode(new: T, old: T?): ByteArray = latestCodec.encode(new, old)

    override fun routedDecode(data: ByteArray, context: DecodeContext, existing: T?): T =
        getCodec(context.featureSchemaVersion, logger).decode(data, context, existing)

    override fun routedReconstructSummary(
        data: ByteArray,
        context: DecodeContext
    ): String = getCodec(context.featureSchemaVersion, logger).reconstructSummary(data)

    override fun routedComputeChangedTags(new: T, old: T?): List<Int> =
        latestCodec.computeChangedTags(new, old)

    override fun stampHlcMetadata(
        candidateState: T,
        existingState: T?,
        changedTags: List<Int>,
        hlc: HLC
    ): T {
        var fieldHlcMap = FieldHlcMap(existingState?.fieldHlcs ?: ByteArray(0))
        changedTags.forEach { tag -> fieldHlcMap = fieldHlcMap.updateTag(tag, hlc) }
        return candidateState.withHlcMetadata(hlc, fieldHlcMap.bytes)
    }
}
