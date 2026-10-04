package com.mochame.sync.domain.serialization

import com.mochame.sync.spi.infrastructure.serialization.VersionRouter
import com.mochame.sync.domain.model.SyncIntent

interface IntentCodecRouter: VersionRouter<IntentCodec> {
    fun routedEncode(intent: SyncIntent): ByteArray
    fun routedDecode(bytes: ByteArray, version: Int): SyncIntent
}
