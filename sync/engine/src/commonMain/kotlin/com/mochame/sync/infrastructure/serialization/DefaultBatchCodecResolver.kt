package com.mochame.sync.infrastructure.serialization

import co.touchlab.kermit.Logger
import com.mochame.logger.LogTags
import com.mochame.logger.withTags
import com.mochame.sync.domain.serialization.BatchCodec
import com.mochame.sync.domain.serialization.BatchCodecResolver
import com.mochame.sync.api.utils.getCodec
import com.mochame.sync.api.utils.latestCodec
import com.mochame.sync.domain.model.SyncIntent
import org.koin.core.annotation.Single

@Single(binds = [BatchCodecResolver::class])
internal class DefaultBatchCodecResolver(
    v1: BatchCodecV1,
    logger: Logger,
    override val versionRegistry: Array<BatchCodec?> = arrayOf(null, v1),
    override val latestVersion: Int = 1,
) : BatchCodecResolver {
    private val logger =
        logger.withTags(LogTags.Layer.SERI, LogTags.Domain.SYNC, "BaCRtr")


    override fun versionEncode(intents: List<SyncIntent>): ByteArray =
        latestCodec.encode(intents)

    override fun versionDecode(bytes: ByteArray, version: Int): List<SyncIntent> =
        getCodec(version, logger).decode(bytes)

}

/*
 question: The Java Virtual Machine maintains a permanent, pre-allocated internal array of java.lang.Byte objects on the heap for every single value from -128 to 127.
 */
/*
 * Byte remains a primitive sitting inside this object's memory block on the heap as raw bytes, not a boxed object
 * pointer as was previously the case when using a map of <Byte, Codec>.
 * This means the CPU copies the 8 bits straight into their registers to perform operations.
 * Using a Byte means a max value of 255 (due to bit masking on the versionRegistry lookup), an Int would
 * mean a 4-byte version system on each payload component, which is not necessary.
 */
