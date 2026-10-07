package com.mochame.sync.di.fixtures

import co.touchlab.kermit.Logger
import com.mochame.annotations.BlobMutex
import com.mochame.annotations.CommittedDir
import com.mochame.annotations.IoContext
import com.mochame.annotations.PendingDir
import com.mochame.logger.test.TestLoggerModule
import com.mochame.platform.fixtures.di.FixturesPlatformModule
import com.mochame.support.TestSupportModule
import com.mochame.sync.api.boot.BootStatusProvider
import com.mochame.sync.api.boot.BootStatusUpdater
import com.mochame.sync.di.SyncConcurrencyModule
import com.mochame.sync.di.SyncStoresModule
import com.mochame.sync.domain.hlc.HlcFactory
import com.mochame.sync.domain.infrastructure.NodeContextManager
import com.mochame.sync.domain.infrastructure.SyncWorkerHook
import com.mochame.sync.domain.policy.ExecutionPolicy
import com.mochame.sync.domain.stores.BlobStore
import com.mochame.sync.domain.stores.QuarantinedPayloadStore
import com.mochame.sync.domain.stores.SyncIntentMaintenanceStore
import com.mochame.sync.domain.stores.SyncIntentStore
import com.mochame.sync.infrastructure.stores.DefaultBlobStore.Companion.DEFAULT_STALE_AGE
import com.mochame.sync.internal.fixtures.FakeQuarantinedPayloadStore
import com.mochame.sync.internal.fixtures.infrastructure.FakeSyncIntentStore
import com.mochame.sync.internal.fixtures.infrastructure.SpyBlobStore
import com.mochame.sync.internal.fixtures.infrastructure.SpyHlcFactory
import com.mochame.sync.internal.fixtures.infrastructure.SpySyncWorkerHook
import com.mochame.sync.internal.fixtures.node.FakeExecutionPolicy
import com.mochame.sync.internal.fixtures.node.FakeNodeContextManager
import com.mochame.sync.internal.fixtures.node.SpyBootStatusManager
import com.mochame.sync.spi.DigestFactory
import com.mochame.utils.fixtures.FakeTimeUtils
import com.mochame.utils.fixtures.di.FakeTimeProviderModule
import com.mochame.utils.interfaces.TimeUtils
import kotlinx.coroutines.sync.Mutex
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@Module(
    includes = [
        TestSupportModule::class,
        SyncConcurrencyModule::class,
        FixturesPlatformModule::class,
        FixturesNodeModule::class,
        FakeTimeProviderModule::class,
        TestLoggerModule::class
    ]
)
internal class SyncInternalFixturesModule {

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

    @Single(binds = [BlobStore::class, SpyBlobStore::class])
    fun provideSpyBlobStore(
        timeUtils: TimeUtils,
        digestFactory: DigestFactory,
        fileSystem: FileSystem,
        maxStagingAge: Duration = DEFAULT_STALE_AGE,
        @IoContext ioContext: CoroutineContext,
        @PendingDir pendingDir: Path,
        @CommittedDir committedDir: Path,
        @BlobMutex blobMutex: Mutex,
        logger: Logger
    ): SpyBlobStore =
        SpyBlobStore(
            timeUtils = timeUtils,
            digestFactory = digestFactory,
            fileSystem = fileSystem,
            maxStagingAge = maxStagingAge,
            ioContext = ioContext,
            pendingDir = pendingDir,
            committedDir = committedDir,
            blobMutex,
            logger
        )

}


@Module
class FixturesNodeModule {
    @Single(binds = [NodeContextManager::class, FakeNodeContextManager::class])
    fun provideFakeNodeManager(): FakeNodeContextManager = FakeNodeContextManager()

    @Single(binds = [BootStatusProvider::class, BootStatusUpdater::class, SpyBootStatusManager::class])
    fun provideSpyBootStatusManager(): SpyBootStatusManager =
        SpyBootStatusManager(timeout = FixturesNodeConfig.BOOT_TIMEOUT)

    @Single(binds = [ExecutionPolicy::class, FakeExecutionPolicy::class])
    fun provideFakeExecutionPolicy(): FakeExecutionPolicy = FakeExecutionPolicy()
}

object FixturesNodeConfig {
    val BOOT_TIMEOUT = 5.seconds
}