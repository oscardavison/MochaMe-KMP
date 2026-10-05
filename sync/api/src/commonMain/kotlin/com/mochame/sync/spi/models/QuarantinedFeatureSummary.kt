package com.mochame.sync.spi.models

import com.mochame.sync.api.metadata.FeatureContext

/**
 * Container designed to hold a featureContext and its count of quarantined records.
 */
data class QuarantinedFeatureSummary(
    val featureContext: FeatureContext,
    val count: Int
)
