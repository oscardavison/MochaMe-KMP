package com.mochame.sync.di

import com.mochame.annotations.BlobMutex
import com.mochame.annotations.CoordinatorMutex
import com.mochame.annotations.JanitorMutex
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.cio.CIO
import kotlinx.coroutines.sync.Mutex
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single

import com.mochame.logger.LoggerModule
import com.mochame.sync.domain.policy.ExecutionPolicy
import com.mochame.sync.domain.policy.StaggeredDbRetryPolicy
import co.touchlab.kermit.Logger

@Module(
    includes = [
        SyncDataModule::class,
        SyncDomainModule::class,
        SyncInfraModule::class,
        SyncStoresModule::class,
        SyncOrchestrationModule::class,
        SyncConcurrencyModule::class,
        StaggeredDbRetryPolicyModule::class,
        NetworkModule::class
    ]
)
@ComponentScan("com.mochame.sync.api.network", "com.mochame.sync.api.repository", "com.mochame.sync.engine")
class SyncProductionModule

@Module(includes = [LoggerModule::class])
class StaggeredDbRetryPolicyModule {
    @Single(binds = [ExecutionPolicy::class])
    fun provideStaggeredDbPolicyModule(logger: Logger): ExecutionPolicy =
        StaggeredDbRetryPolicy(logger = logger)
}

@Module
class NetworkModule {

    @Single
    fun provideHttpClientEngine(): HttpClientEngine = CIO.create()
}

@Module
@ComponentScan("com.mochame.sync.data")
class SyncDataModule

@Module
@ComponentScan("com.mochame.sync.domain")
class SyncDomainModule

@Module
@ComponentScan("com.mochame.sync.infrastructure")
class SyncInfraModule

@Module
@ComponentScan("com.mochame.sync.infrastructure.stores")
class SyncStoresModule

@Module
@ComponentScan("com.mochame.sync.infrastructure.serialization")
class SyncSerializationModule

@Module
@ComponentScan("com.mochame.sync.orchestration")
class SyncOrchestrationModule

@Module
class SyncConcurrencyModule {
    @Single
    @JanitorMutex
    fun provideJanitorMutex(): Mutex = Mutex()

    @Single
    @BlobMutex
    fun provideBlobMutex(): Mutex = Mutex()

    @Single
    @CoordinatorMutex
    fun provideCoordinatorMutex(): Mutex = Mutex()
}
