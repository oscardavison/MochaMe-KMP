package com.mochame.sync.di.infrastructure

import com.mochame.sync.infrastructure.DefaultKeyedLocker
import com.mochame.sync.domain.infrastructure.KeyedLocker
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single

@Module
internal class DefaultKeyedLockerModule {
    @Single(binds = [KeyedLocker::class])
    fun provideRealLocker(): DefaultKeyedLocker = DefaultKeyedLocker()
}
