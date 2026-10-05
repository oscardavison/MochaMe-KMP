package com.mochame.sync.domain.model

import com.mochame.sync.api.metadata.FeatureContext

/**
 * The key for a lock in the keyed locker.
 */
data class LockKey(
    val context: FeatureContext,
    val candidateKey: Long
)