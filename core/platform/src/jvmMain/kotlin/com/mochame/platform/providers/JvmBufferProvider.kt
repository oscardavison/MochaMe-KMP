package com.mochame.platform.providers

import co.touchlab.kermit.Logger
import com.mochame.logger.LogTags
import com.mochame.logger.withTags
import com.mochame.sync.spi.BufferProvider
import kotlinx.io.Buffer
import org.koin.core.annotation.Single

@Single(binds = [BufferProvider::class])
class JvmBufferProvider(logger: Logger) : BufferProvider {
    private val logger =
        logger.withTags(LogTags.Layer.INFRA, LogTags.Domain.PLATFORM, "Buffer")
    private val threadLocal = ThreadLocal.withInitial { Buffer() }

    override fun get(): Buffer {
        val buffer = threadLocal.get()

        val threadName = Thread.currentThread().name
        val threadId = Thread.currentThread().threadId()

        logger.v { "BUFFER | REUSE | Thread: $threadName (ID: $threadId)" }

        buffer.clear()
        return buffer
    }
}

