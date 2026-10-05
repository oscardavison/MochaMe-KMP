package com.mochame.sync.spi.infrastructure.serialization

import com.mochame.sync.api.models.LocalFirstEntity
import com.mochame.sync.spi.models.DecodeContext

interface FeatureCodecResolver<T : LocalFirstEntity<T>, TCodec : Any> : VersionResolver<TCodec> {
    fun versionEncode(new: T, old: T?): ByteArray
    fun versionDecode(data: ByteArray, context: DecodeContext, existing: T?): T
    fun routedComputeChangedTags(new: T, old: T?): List<Int>
    fun versionReconstructSummary(data: ByteArray, context: DecodeContext): String
}
