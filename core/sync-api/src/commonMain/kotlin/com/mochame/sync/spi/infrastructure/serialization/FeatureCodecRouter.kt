package com.mochame.sync.spi.infrastructure.serialization

import com.mochame.sync.api.hlc.HLC
import com.mochame.sync.spi.models.DecodeContext
import com.mochame.sync.api.models.LocalFirstEntity

interface FeatureCodecRouter<T : LocalFirstEntity<T>, TCodec : Any> : VersionRouter<TCodec> {
    fun routedEncode(new: T, old: T?): ByteArray
    fun routedDecode(data: ByteArray, context: DecodeContext, existing: T?): T
    fun routedComputeChangedTags(new: T, old: T?): List<Int>
    fun routedReconstructSummary(data: ByteArray, context: DecodeContext): String
    fun stampHlcMetadata(
        candidateState: T,
        existingState: T?,
        changedTags: List<Int>,
        hlc: HLC
    ): T
}
