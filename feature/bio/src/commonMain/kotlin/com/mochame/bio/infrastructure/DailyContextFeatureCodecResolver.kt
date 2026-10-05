package com.mochame.bio.infrastructure

import co.touchlab.kermit.Logger
import com.mochame.bio.domain.DailyContext
import com.mochame.logger.LogTags
import com.mochame.logger.withTags
import com.mochame.sync.spi.infrastructure.serialization.BaseFeatureCodecResolver
import org.koin.core.annotation.Single

@Single
class DailyContextFeatureCodecResolver(
    v1: DailyContextCodecV1,
    logger: Logger
) : BaseFeatureCodecResolver<DailyContext>(
    versionRegistry = arrayOf(null, v1),
    latestVersion = 1,
    logger = logger.withTags(LogTags.Layer.SERI, LogTags.Domain.BIO, "DyCRtr")
)