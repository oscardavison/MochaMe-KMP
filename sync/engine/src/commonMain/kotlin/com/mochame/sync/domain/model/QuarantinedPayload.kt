package com.mochame.sync.domain.model

data class QuarantinedPayload (
    val watermark: Long,
    val rawPayload: ByteArray,
    val failureReason: String,
    val receivedAt: Long
)