package com.mochame.sync.di.janitor

import co.touchlab.kermit.ExperimentalKermitApi
import co.touchlab.kermit.TestLogWriter
import com.mochame.annotations.JanitorMutex
import com.mochame.platform.fixtures.FakeTransactionProvider
import com.mochame.platform.fixtures.di.FixturesPlatformModule
import com.mochame.support.TestSupportModule
import com.mochame.sync.di.SyncConcurrencyModule
import com.mochame.sync.di.SyncDomainModule
import com.mochame.sync.di.SyncOrchestrationModule
import com.mochame.sync.di.SyncStoresModule
import com.mochame.sync.di.domain.SyncPruneIntentsTestModule
import com.mochame.sync.di.fixtures.SyncInternalFixturesModule
import com.mochame.sync.domain.config.JanitorMaintenanceConfig
import com.mochame.sync.infrastructure.stores.DefaultBlobStore
import com.mochame.sync.internal.fixtures.infrastructure.FakeSyncIntentStore
import com.mochame.sync.internal.fixtures.infrastructure.SpyHlcFactory
import com.mochame.sync.internal.fixtures.node.FakeExecutionPolicy
import com.mochame.sync.internal.fixtures.node.FakeNodeContextManager
import com.mochame.sync.internal.fixtures.node.SpyBootStatusManager
import com.mochame.sync.orchestration.DefaultSyncJanitor
import com.mochame.utils.fixtures.FakeTimeUtils
import com.mochame.utils.fixtures.di.FakeTimeProviderModule
import kotlinx.coroutines.sync.Mutex
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Module

@Module(
    includes = [
        TestSupportModule::class,
        FixturesPlatformModule::class,
        SyncStoresModule::class,
        SyncConcurrencyModule::class,
        SyncPruneIntentsTestModule::class,
        SyncDomainModule::class,
        SyncOrchestrationModule::class,
        SyncInternalFixturesModule::class,
        FakeTimeProviderModule::class
    ]
)
@ComponentScan("com.mochame.sync.di.janitor")
internal class SyncJanitorTestModule

@Factory
@ExperimentalKermitApi
internal data class JanitorTestEnv(
    val janitor: DefaultSyncJanitor,
    val config: JanitorMaintenanceConfig,
    val writer: TestLogWriter,
    val fakeClock: FakeTimeUtils,
    val bootUpdater: SpyBootStatusManager,
    val hlcFactory: SpyHlcFactory,
    val nodeManager: FakeNodeContextManager,
    val blobStore: DefaultBlobStore,
    val intentStore: FakeSyncIntentStore,
    val transactor: FakeTransactionProvider,
    val executor: FakeExecutionPolicy,
    @JanitorMutex val janitorMutex: Mutex,
) : AutoCloseable {

    override fun close() {
        janitor.close()
    }
}