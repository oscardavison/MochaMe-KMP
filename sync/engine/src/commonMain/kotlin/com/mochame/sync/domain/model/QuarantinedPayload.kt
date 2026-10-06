package com.mochame.sync.domain.model

import com.mochame.sync.api.metadata.FeatureContext

data class QuarantinedPayload (
    val watermark: Long,
    val rawPayload: ByteArray,
    val failureReason: String,
    val receivedAt: Long
)

/**
 * Container designed to hold a featureContext and its count of quarantined records.
 */
data class QuarantinedFeatureSummary(
    val featureContext: FeatureContext,
    val count: Int
)
