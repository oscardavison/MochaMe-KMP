package com.mochame.sync.spi.infrastructure.serialization

import com.mochame.sync.api.models.LocalFirstEntity
import com.mochame.sync.spi.infrastructure.BufferProvider
import com.mochame.sync.spi.models.DecodeContext

interface FeatureCodec<T : LocalFirstEntity<T>> {
    val bufferProvider: BufferProvider
    fun encode(new: T, old: T?): ByteArray
    fun decode(bytes: ByteArray, context: DecodeContext, existing: T?): T
    fun reconstructSummary(bytes: ByteArray): String
    fun computeChangedTags(new: T, old: T?): List<Int>
}
