package com.mochame.platform.providers

import co.touchlab.kermit.Logger
import com.mochame.logger.LogTags
import com.mochame.logger.withTags
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.coroutines.CoroutineContext

class AppBackgroundScopeOwner(
    context: CoroutineContext,
    logger: Logger
) : AutoCloseable, CoroutineScope by CoroutineScope(context + SupervisorJob()) {

    private val logger = logger.withTags(LogTags.Layer.INFRA, LogTags.Domain.PLATFORM, "BgScop")

    init {
        logger.d { "Created background scope: $this" }
    }

    override fun close() {
        cancel()
        logger.d { "Closed background scope: $this" }
    }

    override fun toString(): String = "AppBackgroundScopeOwner($coroutineContext)"
}
