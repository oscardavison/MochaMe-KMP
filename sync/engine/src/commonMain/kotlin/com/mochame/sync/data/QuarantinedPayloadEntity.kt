package com.mochame.sync.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "quarantined_payload")
data class QuarantinedPayloadEntity(
    @PrimaryKey val watermark: Long,
    val rawPayload: ByteArray,
    val failureReason: String,
    val receivedAt: Long
)