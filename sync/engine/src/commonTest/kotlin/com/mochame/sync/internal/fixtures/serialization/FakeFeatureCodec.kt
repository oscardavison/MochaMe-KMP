package com.mochame.sync.internal.fixtures.serialization

import com.mochame.sync.spi.BufferProvider
import com.mochame.sync.api.codec.FeatureCodec
import com.mochame.sync.domain.model.DecodeContext
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

        const val SUMMARIZE_PRESET = "OP:V2_SUMMARY"
        const val RECONSTRUCT_PRESET = "OP:V2_RECONSTRUCTED"

    }

    override fun encode(new: FeatureEntity, old: FeatureEntity?): ByteArray = BYTES_PRESET

    override fun decode(
        bytes: ByteArray,
        context: DecodeContext,
        existing: FeatureEntity?
    ): FeatureEntity {
        require(bytes.contentEquals(BYTES_PRESET)) {
            "FakeFeatureCodec decode received unexpected bytes: ${bytes.toHexString()}"
        }

        return MODEL_PRESET.copy(
            id = context.primaryKey,
            hlc = context.hlc,
            lastModified = context.hlc.ts
        )
    }

    override fun reconstructSummary(bytes: ByteArray): String = RECONSTRUCT_PRESET
    override fun computeChangedTags(new: FeatureEntity, old: FeatureEntity?) = buildList {
        val deleteStateChange = new.isDeleted != (old?.isDeleted ?: false)
        if (deleteStateChange) add(2)

        if (old == null || new.textValue != old.textValue) add(4)
        if (old == null || new.countValue != old.countValue) add(5)
    }
}