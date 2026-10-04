package com.mochame.sync.domain.infrastructure

import com.mochame.sync.api.metadata.FeatureContext

/**
 * A reference-counted locker for keyed synchronization.
 */
interface KeyedLocker {
    suspend fun <R> withLock(
        context: FeatureContext,
        candidateKey: Long,
        action: suspend () -> R
    ): R
}