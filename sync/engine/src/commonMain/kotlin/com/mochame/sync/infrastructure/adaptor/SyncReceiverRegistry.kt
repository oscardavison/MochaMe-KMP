package com.mochame.sync.infrastructure.adaptor

import com.mochame.sync.api.metadata.FeatureContext
import com.mochame.sync.spi.infrastructure.SyncReceiver
import org.koin.core.annotation.Single

@Single
internal class SyncReceiverRegistry {
    val receivers: Map<FeatureContext, SyncReceiver>
        field = mutableMapOf<FeatureContext, SyncReceiver>()

    fun register(receiver: SyncReceiver) {
        receivers[receiver.featureContext] = receiver
    }
}