package com.mochame.sync.domain.serialization

import com.mochame.sync.domain.model.SyncIntent

interface BatchCodec {
    fun encode(intents: List<SyncIntent>): ByteArray
    fun decode(bytes: ByteArray): List<SyncIntent>
}
