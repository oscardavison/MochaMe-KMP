package com.mochame.sync.internal.fixtures.serialization

import co.touchlab.kermit.Logger
import com.mochame.sync.infrastructure.serialization.BatchCodecV1
import com.mochame.sync.infrastructure.serialization.DefaultBatchCodecResolver
import com.mochame.sync.infrastructure.serialization.DefaultIntentCodecResolver
import com.mochame.sync.infrastructure.serialization.IntentCodecV1
import com.mochame.sync.domain.serialization.BatchCodec
import com.mochame.sync.domain.serialization.BatchCodecResolver
import com.mochame.sync.domain.serialization.IntentCodecResolver

/**
 * Always of format -  registry = arrayOf(null, this, v2), with the latest version being the last index.
 */
internal fun IntentCodecV1.toRouterWithVersion(
    v2: FakeIntentCodec,
    logger: Logger
): IntentCodecResolver {
    val registry = arrayOf(null, this, v2)
    val latestVersion = registry.lastIndex

    return DefaultIntentCodecResolver(
        v1 = this,
        logger = logger,
        versionRegistry = registry,
        latestVersion = latestVersion
    )
}

/**
 * Always of format -  registry = arrayOf(null, this, v2), with the latest version being the last index.
 */
internal fun BatchCodecV1.toRouterWithVersion(
    v2: FakeBatchCodec,
    logger: Logger
): BatchCodecResolver {
    val registry: Array<BatchCodec?> = arrayOf(null, this, v2)
    val latestVersion = registry.lastIndex

    return DefaultBatchCodecResolver(
        v1 = this,
        logger = logger,
        versionRegistry = registry,
        latestVersion = latestVersion
    )
}