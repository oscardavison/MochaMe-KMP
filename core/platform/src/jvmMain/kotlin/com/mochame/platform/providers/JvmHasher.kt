package com.mochame.platform.providers

import co.touchlab.kermit.Logger
import com.mochame.logger.LogTags
import com.mochame.logger.withTags
import com.mochame.sync.spi.DigestState
import kotlinx.io.Source
import kotlinx.io.readByteArray
import org.koin.core.annotation.Single
import java.security.MessageDigest


@Single(binds = [DigestState::class])
actual fun createPlatformDigest(algorithm: String, logger: Logger): DigestState =
    JvmPlatformDigest(algorithm, logger)

class JvmPlatformDigest(algorithm: String, logger: Logger) : DigestState {

    private val delegate = MessageDigest.getInstance(algorithm)
    private val log = logger
        .withTags(LogTags.Layer.INFRA, LogTags.Domain.PLATFORM, algorithm)

    override fun update(source: Source) {
        val bytes = source.readByteArray()
        delegate.update(bytes)

        log.v { "Updated digest with ${bytes.size} bytes" }
    }

    override fun digest(): ByteArray {
        val result = delegate.digest()
        log.d { "Digest finalized | Hash Size: ${result.size} bytes" }
        return result
    }
}