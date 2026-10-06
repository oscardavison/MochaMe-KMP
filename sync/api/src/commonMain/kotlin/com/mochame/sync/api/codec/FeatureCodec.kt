package com.mochame.sync.api.codec

import com.mochame.sync.api.FieldResolver
import com.mochame.sync.api.models.LocalFirstDelta
import com.mochame.sync.api.models.LocalFirstEntity
import com.mochame.sync.spi.BufferProvider

interface FeatureCodec<T : LocalFirstEntity<T>> {
    val bufferProvider: BufferProvider
    fun encode(new: T, old: T?): ByteArray
    fun deserializeDelta(bytes: ByteArray): LocalFirstDelta
    fun computeChangedTags(new: T, old: T?): List<Int>
    fun mergeDomain(resolver: FieldResolver, delta: LocalFirstDelta, candidateKey: Long, existing: T?): T
    fun reconstructSummary(bytes: ByteArray): String
}
