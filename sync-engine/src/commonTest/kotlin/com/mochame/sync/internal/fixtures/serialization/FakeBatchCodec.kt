package com.mochame.sync.internal.fixtures.serialization

import com.mochame.sync.internal.fixtures.createTestSyncIntent
import com.mochame.sync.domain.serialization.BatchCodec
import com.mochame.sync.domain.model.SyncIntent
import org.koin.core.annotation.Single


@Single
class FakeBatchCodec : BatchCodec {

    companion object {
        val BYTES_PRESET = byteArrayOf(0x04, 0x04)

        // Minimal multi-item preset using existing test model factory
        val MODEL_LIST_PRESET = listOf(
            createTestSyncIntent(candidateKey = 0L),
            createTestSyncIntent(candidateKey = 1L)
        )
    }

    override fun encode(intents: List<SyncIntent>): ByteArray = BYTES_PRESET

    override fun decode(bytes: ByteArray): List<SyncIntent> {
        require(bytes.contentEquals(BYTES_PRESET)) {
            "FakeBatchCodec received unexpected bytes: ${bytes.toHexString()}"
        }

        return MODEL_LIST_PRESET
    }
}