package com.mochame.sync.di.infrastructure

import com.mochame.sync.data.QuarantinedPayloadDao
import com.mochame.sync.di.SyncInfraModule
import com.mochame.sync.di.data.SyncPersistenceTestModule
import com.mochame.sync.infrastructure.stores.DefaultQuarantinedPayloadStore
import com.mochame.utils.fixtures.FakeTimeUtils
import com.mochame.utils.fixtures.di.FakeTimeProviderModule
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Module

@Module(
    includes = [
        SyncInfraModule::class,
        SyncPersistenceTestModule::class,
        FakeTimeProviderModule::class
    ]
)
@ComponentScan("com.mochame.sync.di.infrastructure")
internal class QuarantinedPayloadStoreTestModule

@Factory
internal data class QuarantinedPayloadTestEnv(
    val quarantinedPayloadStore: DefaultQuarantinedPayloadStore,
    val quarantinedPayloadDao: QuarantinedPayloadDao,
    val fakeTimeUtils: FakeTimeUtils,
)
