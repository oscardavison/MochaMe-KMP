package com.mochame.sync.internal.fixtures.serialization

import co.touchlab.kermit.Logger
import com.mochame.logger.LogTags
import com.mochame.logger.withTags
import com.mochame.sync.api.codec.BaseCodecResolver
import org.koin.core.annotation.Single

@Single
class CodecResolverFixture(
    val v1: FeatureCodecV1,
    val v2: FakeFeatureCodec,
    val testLogger: Logger
) : BaseCodecResolver<FeatureEntity>(
    versionRegistry = arrayOf(null, v1, v2),
    latestVersion = 2,
    logger = testLogger.withTags(LogTags.Layer.SERI, LogTags.Domain.SYNC, "TeCRtr")
)

@Single
class IntegratedResolver(
    val v1: FeatureCodecV1,
    logger: Logger
) : BaseCodecResolver<FeatureEntity>(
    versionRegistry = arrayOf(null, v1),
    latestVersion = 1,
    logger = logger.withTags(LogTags.Layer.SERI, LogTags.Domain.SYNC, "TeCRtr")
)
