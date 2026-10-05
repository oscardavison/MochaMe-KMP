package com.mochame.sync.infrastructure.stores

import com.mochame.sync.data.QuarantinedPayloadDao
import com.mochame.sync.data.QuarantinedPayloadEntity
import com.mochame.sync.domain.stores.QuarantinedPayloadStore
import com.mochame.sync.domain.model.QuarantinedPayload
import com.mochame.utils.interfaces.TimeUtils
import org.koin.core.annotation.Single


@Single(binds = [QuarantinedPayloadStore::class])
class DefaultQuarantinedPayloadStore(
    private val dao: QuarantinedPayloadDao,
    private val timeUtils: TimeUtils
) : QuarantinedPayloadStore {

    override suspend fun record(watermark: Long, rawPayload: ByteArray, failureReason: String) {
        dao.insert(
            QuarantinedPayloadEntity(
                watermark = watermark,
                rawPayload = rawPayload,
                failureReason = failureReason,
                receivedAt = timeUtils.now().toEpochMilliseconds()
            )
        )
    }

    override suspend fun getAll(): List<QuarantinedPayload> =
        dao.getAll().map { it.toDomain() }

    override suspend fun getByWatermark(watermark: Long): QuarantinedPayload? =
        dao.getByWatermark(watermark)?.toDomain()

    override suspend fun deleteByWatermark(watermark: Long) {
        dao.deleteByWatermark(watermark)
    }

    override suspend fun pruneOlderThan(timestamp: Long): Int =
        dao.pruneOlderThan(timestamp)

    private fun QuarantinedPayloadEntity.toDomain() = QuarantinedPayload(
        watermark = watermark,
        rawPayload = rawPayload,
        failureReason = failureReason,
        receivedAt = receivedAt
    )
}