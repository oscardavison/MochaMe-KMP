package com.mochame.sync.domain.serialization

import com.mochame.sync.spi.infrastructure.serialization.VersionRouter
import com.mochame.sync.domain.model.SyncIntent


interface BatchCodecRouter: VersionRouter<BatchCodec> {
    fun routedEncode(intents: List<SyncIntent>): ByteArray
    fun routedDecode(bytes: ByteArray, version: Int): List<SyncIntent>
}
