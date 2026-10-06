package com.mochame.platform.providers

import co.touchlab.kermit.Logger
import com.mochame.logger.LogTags
import com.mochame.logger.withTags
import com.mochame.sync.spi.BufferProvider
import kotlinx.io.Buffer
import org.koin.core.annotation.Single
import platform.posix.pthread_self
import kotlin.native.concurrent.ThreadLocal

@Single(binds = [BufferProvider::class])
class LinuxBufferProvider(logger: Logger) : BufferProvider {
    private val logger =
        logger.withTags(LogTags.Layer.INFRA, LogTags.Domain.PLATFORM, "Buffer")

    @ThreadLocal
    companion object {
        private val threadInstance = Buffer()
    }

    override fun get(): Buffer {
        threadInstance.clear()
        logger.v { "BUFFER | REUSE | Thread: ${pthread_self()}" }
        return threadInstance
    }

}