package com.mochame.sync.domain.serialization

import com.mochame.sync.api.utils.VersionResolver
import com.mochame.sync.domain.model.SyncIntent

interface IntentCodecResolver: VersionResolver<IntentCodec> {
    fun versionEncode(intent: SyncIntent): ByteArray
    fun versionDecode(bytes: ByteArray, version: Int): SyncIntent
}
