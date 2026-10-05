package com.mochame.sync.internal.fixtures.serialization

import com.mochame.sync.api.models.HLC
import com.mochame.sync.api.metadata.MutationOp
import com.mochame.sync.api.models.LocalFirstEntity
import com.mochame.sync.spi.models.DecodeContext
import com.mochame.utils.fixtures.TestHlcFactory
import kotlin.test.assertEquals
import kotlin.time.Instant

data class FeatureEntity(
    override val id: Long = 1000L,
    override val hlc: HLC = TestHlcFactory.create(),
    override val lastModified: Long = TestHlcFactory.create().ts,
    override val createdAt: Instant = Instant.fromEpochMilliseconds(TestHlcFactory.create().ts),
    override val isDeleted: Boolean = false,
    override val fieldHlcs: ByteArray = ByteArray(0),
    val textValue: String? = "default-text",
    val countValue: Int? = 1,
) : LocalFirstEntity<FeatureEntity> {

    override fun withHlcMetadata(hlc: HLC, fieldBlob: ByteArray): FeatureEntity = copy(
        hlc = hlc,
        lastModified = hlc.ts,
        fieldHlcs = fieldBlob
    )

    override fun withDeleteState(isDeleted: Boolean) = if (isDeleted) {
        copy(
            isDeleted = true,
            textValue = null,
            countValue = null
        )
    } else {
        copy(isDeleted = false)
    }

    override fun withSyncHeader(
        hlc: HLC,
        lastModified: Long,
        createdAt: Instant,
        isDeleted: Boolean,
        fieldHlcs: ByteArray
    ): FeatureEntity = copy(
        hlc = hlc,
        lastModified = lastModified,
        createdAt = createdAt,
        isDeleted = isDeleted,
        fieldHlcs = fieldHlcs
    )
}

fun FeatureEntity.deriveContext(
    schemaVersion: Int = 1,
    op: MutationOp = MutationOp.UPSERT,
    overflowBlobId: String? = null,
    changedMask: Long? = null
) = DecodeContext(
    featureSchemaVersion = schemaVersion,
    candidateKey = id,
    hlc = hlc,
    op = op,
    overflowBlobId = overflowBlobId,
    changedMask = changedMask ?: 0L
)

fun FeatureEntity.assertDecodeParity(original: FeatureEntity, upsertHlc: HLC? = null) {
    assertEquals(original.id, this.id)
    assertEquals(original.isDeleted, this.isDeleted)
    assertEquals(original.textValue, this.textValue)
    assertEquals(original.countValue, this.countValue)
    assertEquals(original.createdAt, this.createdAt)

    upsertHlc?.let {
        assertEquals(it, this.hlc)
        assertEquals(it.ts, this.lastModified)
    } ?: {
        assertEquals(original.hlc, this.hlc)
    }
}
