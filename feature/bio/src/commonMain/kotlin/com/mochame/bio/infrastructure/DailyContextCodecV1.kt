package com.mochame.bio.infrastructure

import co.touchlab.kermit.Logger
import com.mochame.bio.domain.DailyContext
import com.mochame.logger.LogTags
import com.mochame.logger.withTags
import com.mochame.sync.api.models.LocalFirstDelta
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

@ExperimentalSerializationApi
@Serializable
data class DailyContextDeltaV1(
    @ProtoNumber(TAG_PRIMARY_KEY) override val id: Long,
    @ProtoNumber(TAG_IS_DELETED) override val isDeleted: Boolean? = null,
    @ProtoNumber(TAG_CREATED_AT) override val createdAt: Long? = null,
    @ProtoNumber(DailyContextCodecV1.TAG_SLEEP_HOURS) val sleepHours: Double? = null,
    @ProtoNumber(DailyContextCodecV1.TAG_READINESS_SCORE) val readinessScore: Int? = null,
    @ProtoNumber(DailyContextCodecV1.TAG_IS_NAPPED) val isNapped: Boolean? = null,
    @ProtoNumber(DailyContextCodecV1.TAG_NOTES) val notes: String? = null
) : LocalFirstDelta


@OptIn(ExperimentalSerializationApi::class)
@Single
class DailyContextCodecV1(
    bufferProvider: BufferProvider,
    logger: Logger
) : BaseFeatureCodec<DailyContext, DailyContextDeltaV1>(
    bufferProvider = bufferProvider,
    deltaSerializer = DailyContextDeltaV1.serializer(),
    logger = logger.withTags(LogTags.Layer.SERI, LogTags.Domain.BIO, "DyCdc1")
) {

    companion object {
        const val TAG_SLEEP_HOURS = 4
        const val TAG_READINESS_SCORE = 5
        const val TAG_IS_NAPPED = 6
        const val TAG_NOTES = 7
    }

    override fun buildDeleteDelta(entity: DailyContext) = DailyContextDeltaV1(
        id = entity.id,
        isDeleted = true,
        createdAt = entity.createdAt.toEpochMilliseconds()
    )

    override fun buildInsertDelta(entity: DailyContext) = DailyContextDeltaV1(
        id = entity.id,
        createdAt = entity.createdAt.toEpochMilliseconds(),
        sleepHours = entity.sleepHours,
        readinessScore = entity.readinessScore,
        isNapped = entity.isNapped,
        notes = entity.notes
    )

    override fun buildUpdateDelta(
        new: DailyContext,
        old: DailyContext,
        isRestored: Boolean
    ): DailyContextDeltaV1 = DailyContextDeltaV1(
        id = new.id,
        isDeleted = false.takeIf { isRestored },
        sleepHours = new.sleepHours diff old.sleepHours,
        readinessScore = new.readinessScore diff old.readinessScore,
        isNapped = new.isNapped diff old.isNapped,
        notes = new.notes diff old.notes
    )

    override fun FieldMergeScope.mergeDomainDelta(
        delta: DailyContextDeltaV1,
        context: DecodeContext,
        existing: DailyContext?
    ) = DailyContext(
        id = context.candidateKey,

        sleepHours = eval(TAG_SLEEP_HOURS, delta.sleepHours, existing?.sleepHours),
        readinessScore = eval(TAG_READINESS_SCORE, delta.readinessScore, existing?.readinessScore),
        isNapped = eval(TAG_IS_NAPPED, delta.isNapped, existing?.isNapped),
        notes = eval(TAG_NOTES, delta.notes, existing?.notes)
    )

    override fun computeDomainChangedTags(new: DailyContext, old: DailyContext?): List<Int> =
        buildList {
            if (new.sleepHours != old?.sleepHours) add(TAG_SLEEP_HOURS)
            if (new.readinessScore != old?.readinessScore) add(TAG_READINESS_SCORE)
            if (new.isNapped != old?.isNapped) add(TAG_IS_NAPPED)
            if (new.notes != old?.notes) add(TAG_NOTES)
        }

}