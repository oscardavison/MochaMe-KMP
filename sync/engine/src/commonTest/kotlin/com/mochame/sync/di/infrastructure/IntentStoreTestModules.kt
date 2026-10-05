package com.mochame.sync.di.infrastructure

import com.mochame.sync.data.SyncIntentDao
import com.mochame.sync.di.SyncInfraModule
import com.mochame.sync.di.data.SyncPersistenceTestModule
import com.mochame.sync.infrastructure.stores.DefaultSyncIntentStore
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Module

@Module(includes = [SyncInfraModule::class, SyncPersistenceTestModule::class])
@ComponentScan("com.mochame.sync.di.infrastructure")
internal class SyncIntentStoreTestModule

@Factory
internal data class SyncIntentTestEnv(
    val intentStore: DefaultSyncIntentStore,
    val intentDao: SyncIntentDao,
)
