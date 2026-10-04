package com.mochame.sync.infrastructure.serialization

import co.touchlab.kermit.Logger
import com.mochame.logger.LogTags
import com.mochame.logger.withTags
import com.mochame.sync.domain.serialization.IntentCodec
import com.mochame.sync.domain.serialization.IntentCodecRouter
import com.mochame.sync.spi.infrastructure.serialization.getCodec
import com.mochame.sync.spi.infrastructure.serialization.latestCodec
import com.mochame.sync.domain.model.SyncIntent
import org.koin.core.annotation.Single

@Single(binds = [IntentCodecRouter::class])
internal class DefaultIntentCodecRouter(
    v1: IntentCodecV1,
    logger: Logger,
    override val versionRegistry: Array<IntentCodec?> = arrayOf(null, v1),
    override val latestVersion: Int = 1,
) : IntentCodecRouter {

    private val logger =
        logger.withTags(LogTags.Layer.SERI, LogTags.Domain.SYNC, "InCRtr")


    override fun routedEncode(intent: SyncIntent): ByteArray = latestCodec.encode(intent)

    override fun routedDecode(bytes: ByteArray, version: Int): SyncIntent =
        getCodec(version, logger).decode(bytes)

}