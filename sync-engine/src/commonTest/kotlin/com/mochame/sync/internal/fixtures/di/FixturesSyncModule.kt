package com.mochame.sync.internal.fixtures.di

import com.mochame.logger.test.TestLoggerModule
import com.mochame.node.fixtures.di.FixturesNodeModule
import com.mochame.platform.fixtures.FakeDigestFactory
import com.mochame.platform.fixtures.di.FixturesPlatformModule
import com.mochame.support.TestSupportModule
import com.mochame.sync.api.hlc.HlcFactory
import com.mochame.sync.internal.fixtures.infrastructure.FakeBlobStore
import com.mochame.sync.internal.fixtures.infrastructure.FakeHlcFactory
import com.mochame.sync.internal.fixtures.infrastructure.FakeKeyedLocker
import com.mochame.sync.internal.fixtures.infrastructure.FakeSyncIntentStore
import com.mochame.sync.internal.fixtures.infrastructure.FakeSyncWorkerHook
import com.mochame.sync.domain.stores.SyncIntentMaintenanceStore
import com.mochame.sync.domain.stores.BlobStore
import com.mochame.sync.domain.infrastructure.KeyedLocker
import com.mochame.sync.domain.stores.SyncIntentStore
import com.mochame.sync.domain.infrastructure.SyncWorkerHook
import com.mochame.utils.fixtures.FakeTimeUtils
import com.mochame.utils.fixtures.di.FakeTimeProviderModule
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single


@Module(
    includes = [
        FakeTimeProviderModule::class,
        FixturesPlatformModule::class,
        FixturesNodeModule::class,
        TestSupportModule::class,
        TestLoggerModule::class,
    ]
)
class FixturesSyncModule {

    @Single(binds = [BlobStore::class, FakeBlobStore::class])
    fun provideFakeBlobStore(digestFactory: FakeDigestFactory): FakeBlobStore =
        FakeBlobStore(digestFactory)

    @Single(binds = [SyncIntentStore::class, SyncIntentMaintenanceStore::class, FakeSyncIntentStore::class])
    fun provideFakeSyncIntentStore(fakeClock: FakeTimeUtils): FakeSyncIntentStore =
        FakeSyncIntentStore(fakeClock)

    @Single(binds = [SyncWorkerHook::class, FakeSyncWorkerHook::class])
    fun provideFakeWorkerHook(): FakeSyncWorkerHook =
        FakeSyncWorkerHook()

    @Single(binds = [HlcFactory::class, FakeHlcFactory::class])
    fun provideFakeHlcFactory(fakeClock: FakeTimeUtils): FakeHlcFactory =
        FakeHlcFactory(fakeClock)

    @Single(binds = [KeyedLocker::class, FakeKeyedLocker::class])
    fun provideFakeKeyedLocker(): FakeKeyedLocker =
        FakeKeyedLocker()
}
