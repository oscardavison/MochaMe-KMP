package com.mochame.platform.providers

import co.touchlab.kermit.Logger
import com.mochame.logger.LogTags
import com.mochame.logger.withTags
import com.mochame.sync.spi.BufferProvider
import kotlinx.io.Buffer
import org.koin.core.annotation.Single

@Single(binds = [BufferProvider::class])
class AndroidBufferProvider(logger: Logger): BufferProvider {
    private val logger =
        logger.withTags(LogTags.Layer.INFRA, LogTags.Domain.PLATFORM, "Buffer")

    private val threadLocal = ThreadLocal.withInitial { Buffer() }

    override fun get(): Buffer {
        val buffer = threadLocal.get() ?: Buffer().also { threadLocal.set(it) }

        val threadName = Thread.currentThread().name

        logger.v { "BUFFER | REUSE | Thread: $threadName" }

        buffer.clear()
        return buffer
    }
}