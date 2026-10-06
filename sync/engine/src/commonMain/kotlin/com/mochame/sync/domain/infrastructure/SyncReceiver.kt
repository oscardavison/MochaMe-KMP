package com.mochame.sync.domain.infrastructure

import com.mochame.sync.api.metadata.FeatureContext
import com.mochame.sync.domain.model.DecodeContext

interface SyncReceiver {
    val featureContext: FeatureContext

    /**
     * Accepts a non-null ByteArray payload and decode context.
     * Transport optimizations (null checking, overflow blob resolution) are handled at coordinator level.
     */
    suspend fun processRemoteIntent(context: DecodeContext, payload: ByteArray?)
}