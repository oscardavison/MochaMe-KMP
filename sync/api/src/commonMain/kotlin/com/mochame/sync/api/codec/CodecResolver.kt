package com.mochame.sync.api.codec

import co.touchlab.kermit.Logger
import com.mochame.sync.api.utils.VersionResolver
import com.mochame.sync.api.models.LocalFirstDelta
import com.mochame.sync.api.models.LocalFirstEntity

interface CodecResolver<T : LocalFirstEntity<T>, TCodec : Any> : VersionResolver<TCodec> {
    fun versionEncode(new: T, old: T?): ByteArray
    fun versionDecode(
        version: Int,
        data: ByteArray,
        logger: Logger,
        primaryKey: Long
    ): LocalFirstDelta

    fun versionComputeChangedTags(new: T, old: T?): List<Int>
    fun versionReconstructSummary(data: ByteArray, featureSchemaVersion: Int): String
}
