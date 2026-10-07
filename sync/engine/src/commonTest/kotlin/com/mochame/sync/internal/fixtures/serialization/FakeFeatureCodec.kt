@file:OptIn(ExperimentalSerializationApi::class)

package com.mochame.sync.internal.fixtures.serialization

import com.mochame.sync.api.FieldResolver
import com.mochame.sync.api.codec.FeatureCodec
import com.mochame.sync.api.models.LocalFirstDelta
import com.mochame.sync.spi.BufferProvider
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import org.koin.core.annotation.Single

@Single
class FakeFeatureCodec(
    override val bufferProvider: BufferProvider
) : FeatureCodec<FeatureEntity> {
    companion object {
        val BYTES_PRESET = byteArrayOf(0x02, 0x02)
        val MODEL_PRESET = FeatureEntity(
            id = 5L,
            textValue = "DECODED_VIA_V2_FAKE"
        )
        val DELTA_PRESET = FeatureEntityDeltaV1(
            id = 5L,
            textValue = "DECODED_VIA_V2_FAKE"
        )

        const val SUMMARIZE_PRESET = "OP:V2_SUMMARY"
        const val RECONSTRUCT_PRESET = "OP:V2_RECONSTRUCTED"
    }

    override fun encode(new: FeatureEntity, old: FeatureEntity?): ByteArray = BYTES_PRESET

    override fun deserializeDelta(bytes: ByteArray): LocalFirstDelta {
        if (!bytes.contentEquals(BYTES_PRESET)) {
            throw SerializationException(
                "FakeFeatureCodec deserializeDelta received unexpected bytes: ${bytes.joinToString()}"
            )
        }
        return DELTA_PRESET
    }

    override fun mergeDomain(
        resolver: FieldResolver,
        delta: LocalFirstDelta,
        candidateKey: Long,
        existing: FeatureEntity?
    ): FeatureEntity {
        return MODEL_PRESET.copy(id = candidateKey)
    }

    override fun reconstructSummary(bytes: ByteArray): String = RECONSTRUCT_PRESET

    override fun computeChangedTags(new: FeatureEntity, old: FeatureEntity?): List<Int> = buildList {
        val deleteStateChange = new.isDeleted != (old?.isDeleted ?: false)
        if (deleteStateChange) add(2)

        if (old == null || new.textValue != old.textValue) add(4)
        if (old == null || new.countValue != old.countValue) add(5)
    }
}