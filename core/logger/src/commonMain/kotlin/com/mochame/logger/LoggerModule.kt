package com.mochame.logger

import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import co.touchlab.kermit.StaticConfig
import com.mochame.annotations.PlatformTag
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single

@Module
expect class PlatformTagModule

@Module(includes = [PlatformTagModule::class])
class LoggerModule {

    @Single
    fun getLogger(@PlatformTag platformTag: String, minSeverity: Severity? = null): Logger {
        return Logger(
            config = StaticConfig(
                minSeverity = minSeverity ?: Severity.Verbose,
                logWriterList = listOf(MochaLogWriter(minSeverity = Severity.Verbose))
            ),
            tag = platformTag
        )
    }
}