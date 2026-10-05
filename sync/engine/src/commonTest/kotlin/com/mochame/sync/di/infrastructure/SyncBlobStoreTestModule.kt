package com.mochame.sync.di.infrastructure

import co.touchlab.kermit.ExperimentalKermitApi
import co.touchlab.kermit.Logger
import co.touchlab.kermit.TestLogWriter
import com.mochame.annotations.BlobMutex
import com.mochame.annotations.CommittedDir
import com.mochame.annotations.PendingDir
import com.mochame.platform.fixtures.FakeDigestFactory
import com.mochame.platform.fixtures.di.FixturesPlatformModule
import com.mochame.support.TestSupportModule
import com.mochame.sync.di.SyncConcurrencyModule
import com.mochame.sync.di.SyncInfraModule
import com.mochame.sync.infrastructure.stores.DefaultBlobStore
import com.mochame.utils.fixtures.FakeTimeUtils
import com.mochame.utils.fixtures.di.FakeTimeProviderModule
import kotlinx.coroutines.sync.Mutex
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Module

@Module(
    includes = [
        SyncInfraModule::class,
        FakeTimeProviderModule::class,
        TestSupportModule::class,
        FixturesPlatformModule::class,
        SyncConcurrencyModule::class,
    ]
)
@ComponentScan("com.mochame.sync.di.infrastructure")
internal class SyncBlobStoreTestModule

@ExperimentalKermitApi
@Factory
internal class BlobStoreTestEnv(
    val store: DefaultBlobStore,
    val digestFactory: FakeDigestFactory,
    val fileSystem: FileSystem,
    val clock: FakeTimeUtils,
    @PendingDir val pendingDir: Path,
    @CommittedDir val committedDir: Path,
    @BlobMutex val mutex: Mutex,
    val logger: Logger,
    val writer: TestLogWriter,
)


