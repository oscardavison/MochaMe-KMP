package com.mochame.logger

import co.touchlab.kermit.Severity
import com.mochame.annotations.PlatformTag
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single
import kotlin.experimental.ExperimentalNativeApi

@Module
actual class PlatformTagModule {
    @Single
    @PlatformTag
    fun providePlatformTag(): String = "Linux"

    @OptIn(ExperimentalNativeApi::class)
    @Single
    fun provideMinSeverity(): Severity =
        if (Platform.isDebugBinary) Severity.Verbose else Severity.Warn
}