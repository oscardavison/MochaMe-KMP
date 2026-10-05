package com.mochame.sync.domain.serialization

import com.mochame.sync.spi.infrastructure.serialization.VersionResolver
import com.mochame.sync.domain.model.SyncIntent


interface BatchCodecResolver: VersionResolver<BatchCodec> {
    fun versionEncode(intents: List<SyncIntent>): ByteArray
    fun versionDecode(bytes: ByteArray, version: Int): List<SyncIntent>
}
