package com.mochame.bio.di

import com.mochame.bio.data.DefaultDailyContextRepository
import com.mochame.sync.spi.infrastructure.SyncReceiver
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single

@Module
@ComponentScan("com.mochame.bio")
class BioProductionModule {

    @Single(binds = [SyncReceiver::class])
    fun provideDailyContextSyncReceiver(repo: DefaultDailyContextRepository): SyncReceiver =
        repo.asSyncReceiver()
}