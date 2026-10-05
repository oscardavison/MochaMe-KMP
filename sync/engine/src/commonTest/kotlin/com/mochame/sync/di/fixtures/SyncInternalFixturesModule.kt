package com.mochame.sync.di.fixtures

import co.touchlab.kermit.Logger
import com.mochame.logger.test.TestLoggerModule
import com.mochame.platform.fixtures.di.FixturesPlatformModule
import com.mochame.sync.domain.hlc.HlcFactory
import com.mochame.sync.domain.infrastructure.SyncWorkerHook
import com.mochame.sync.domain.stores.QuarantinedPayloadStore
import com.mochame.sync.domain.stores.SyncIntentMaintenanceStore
import com.mochame.sync.domain.stores.SyncIntentStore
import com.mochame.sync.internal.fixtures.FakeQuarantinedPayloadStore
import com.mochame.sync.internal.fixtures.infrastructure.FakeSyncIntentStore
import com.mochame.sync.internal.fixtures.infrastructure.SpyHlcFactory
import com.mochame.sync.internal.fixtures.infrastructure.SpySyncWorkerHook
import com.mochame.utils.fixtures.FakeTimeUtils
import com.mochame.utils.fixtures.di.FakeTimeProviderModule
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single

@Module(
    includes = [
        FixturesPlatformModule::class,
        FakeTimeProviderModule::class,
        TestLoggerModule::class
    ]
)
class SyncInternalFixturesModule {

    @Single(binds = [HlcFactory::class, SpyHlcFactory::class])
    internal fun provideSpyHlcFactory(
        clock: FakeTimeUtils,
        logger: Logger
    ): SpyHlcFactory = SpyHlcFactory(clock, logger)

    @Single(binds = [SyncIntentMaintenanceStore::class, SyncIntentStore::class])
    fun provideFakeSyncIntentStore(fakeClock: FakeTimeUtils): FakeSyncIntentStore =
        FakeSyncIntentStore(fakeClock)

    @Single(binds = [QuarantinedPayloadStore::class, FakeQuarantinedPayloadStore::class])
    fun provideFakeMalformedPayloadStore(fakeClock: FakeTimeUtils): FakeQuarantinedPayloadStore =
        FakeQuarantinedPayloadStore(fakeClock)


    @Single(binds = [SyncWorkerHook::class])
    fun provideSpySyncWorkerHook(): SpySyncWorkerHook = SpySyncWorkerHook()

}