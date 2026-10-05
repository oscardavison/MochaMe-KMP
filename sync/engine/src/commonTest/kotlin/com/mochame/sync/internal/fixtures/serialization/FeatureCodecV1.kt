package com.mochame.sync.internal.fixtures.serialization

import co.touchlab.kermit.Logger
import com.mochame.logger.LogTags
import com.mochame.logger.withTags
import com.mochame.sync.api.models.LocalFirstDelta
import com.mochame.sync.internal.fixtures.serialization.FeatureCodecV1.Companion.TAG_COUNT_VALUE
import com.mochame.sync.internal.fixtures.serialization.FeatureCodecV1.Companion.TAG_TEXT_VALUE
import com.mochame.sync.spi.infrastructure.BufferProvider
import com.mochame.sync.spi.infrastructure.serialization.BaseFeatureCodec
import com.mochame.sync.spi.infrastructure.serialization.BaseFeatureCodec.Companion.TAG_CREATED_AT
import com.mochame.sync.spi.infrastructure.serialization.BaseFeatureCodec.Companion.TAG_IS_DELETED
import com.mochame.sync.spi.infrastructure.serialization.BaseFeatureCodec.Companion.TAG_PRIMARY_KEY
import com.mochame.sync.spi.infrastructure.serialization.FieldMergeScope
import com.mochame.sync.spi.infrastructure.serialization.diff
import com.mochame.sync.spi.models.DecodeContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber
import org.koin.core.annotation.Single

@Serializable
@ExperimentalSerializationApi
data class FeatureEntityDeltaV1(
    @ProtoNumber(TAG_PRIMARY_KEY) override val id: Long,
    @ProtoNumber(TAG_IS_DELETED) override val isDeleted: Boolean? = null,
    @ProtoNumber(TAG_CREATED_AT) override val createdAt: Long? = null,
    @ProtoNumber(TAG_TEXT_VALUE) val textValue: String? = null,
    @ProtoNumber(TAG_COUNT_VALUE) val countValue: Int? = null
) : LocalFirstDelta

@Single
@OptIn(ExperimentalSerializationApi::class)
class FeatureCodecV1(
    bufferProvider: BufferProvider,
    logger: Logger
) : BaseFeatureCodec<FeatureEntity, FeatureEntityDeltaV1>(
    bufferProvider = bufferProvider,
    deltaSerializer = FeatureEntityDeltaV1.serializer(),
    logger = logger.withTags(LogTags.Layer.SERI, LogTags.Domain.SYNC, "TeCdc1")
) {

    companion object {
        const val TAG_TEXT_VALUE = 4
        const val TAG_COUNT_VALUE = 5
    }

    override fun buildDeleteDelta(entity: FeatureEntity) = FeatureEntityDeltaV1(
        id = entity.id,
        isDeleted = true
    )

    override fun buildInsertDelta(entity: FeatureEntity) = FeatureEntityDeltaV1(
        id = entity.id,
        createdAt = entity.createdAt.toEpochMilliseconds(),
        textValue = entity.textValue,
        countValue = entity.countValue,
    )

    override fun buildUpdateDelta(
        new: FeatureEntity,
        old: FeatureEntity,
        isRestored: Boolean
    ): FeatureEntityDeltaV1 = FeatureEntityDeltaV1(
        id = new.id,
        isDeleted = false.takeIf { isRestored },
        textValue = new.textValue diff old.textValue,
        countValue = new.countValue diff old.countValue
    )

    override fun FieldMergeScope.mergeDomainDelta(
        delta: FeatureEntityDeltaV1,
        context: DecodeContext,
        existing: FeatureEntity?
    ): FeatureEntity = FeatureEntity(
        id = context.candidateKey,

        textValue = eval(TAG_TEXT_VALUE, delta.textValue, existing?.textValue),
        countValue = eval(TAG_COUNT_VALUE, delta.countValue, existing?.countValue)
    )

    override fun computeDomainChangedTags(new: FeatureEntity, old: FeatureEntity?) = buildList {
        if (old == null || new.textValue != old.textValue) add(TAG_TEXT_VALUE)
        if (old == null || new.countValue != old.countValue) add(TAG_COUNT_VALUE)
    }

}