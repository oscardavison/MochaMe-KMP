package com.mochame.logger

import co.touchlab.kermit.Severity
import com.mochame.annotations.PlatformTag
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single

@Module
actual class PlatformTagModule {
    @Single
    @PlatformTag
    fun providePlatformTag(): String = "JVM"

    @Single
    fun provideIsDebug(): Severity {
        val isDebug = System.getProperty("mochame.debug")?.toBoolean()
            ?: (System.getenv("DEBUG") == "true")

        return if (isDebug) Severity.Verbose else Severity.Warn
    }
}