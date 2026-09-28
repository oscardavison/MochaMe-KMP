package com.mochame.logger

import co.touchlab.kermit.Severity
import com.mochame.annotations.PlatformTag
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single


@Module
actual class PlatformTagModule {
    @Single
    @PlatformTag
    fun providePlatformTag(): String = "Android"

    @Single
    fun provideMinSeverity(): Severity = Severity.Verbose
}