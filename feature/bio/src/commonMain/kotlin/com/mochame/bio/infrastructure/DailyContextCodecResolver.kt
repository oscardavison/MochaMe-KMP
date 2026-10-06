package com.mochame.bio.infrastructure

import co.touchlab.kermit.Logger
import com.mochame.bio.domain.DailyContext
import com.mochame.logger.LogTags
import com.mochame.logger.withTags
import com.mochame.sync.api.codec.BaseCodecResolver
import org.koin.core.annotation.Single

@Single
class DailyContextCodecResolver(
    v1: DailyContextCodecV1,
    logger: Logger
) : BaseCodecResolver<DailyContext>(
    versionRegistry = arrayOf(null, v1),
    latestVersion = 1,
    logger = logger.withTags(LogTags.Layer.SERI, LogTags.Domain.BIO, "DyCRtr")
)