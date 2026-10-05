package com.mochame.sync.domain.serialization

import com.mochame.sync.spi.infrastructure.serialization.VersionResolver
import com.mochame.sync.domain.model.SyncIntent

interface IntentCodecResolver: VersionResolver<IntentCodec> {
    fun versionEncode(intent: SyncIntent): ByteArray
    fun versionDecode(bytes: ByteArray, version: Int): SyncIntent
}
