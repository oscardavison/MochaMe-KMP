package com.mochame.sync.di.api

import co.touchlab.kermit.ExperimentalKermitApi
import co.touchlab.kermit.Logger
import co.touchlab.kermit.TestLogWriter
import com.mochame.logger.test.TestLoggerModule
import com.mochame.node.di.StaggeredDbRetryPolicyModule
import com.mochame.node.fixtures.FakeNodeContextManager
import com.mochame.node.fixtures.SpyBootStatusManager
import com.mochame.node.fixtures.di.FixturesNodeModule
import com.mochame.platform.fixtures.FakeTransactionProvider
import com.mochame.platform.fixtures.di.FixturesPlatformModule
import com.mochame.sync.api.boot.BootState
import com.mochame.sync.api.hlc.HLC
import com.mochame.sync.api.metadata.FeatureContext
import com.mochame.sync.api.metadata.MutationOp
import com.mochame.sync.api.models.LocalFirstEntity
import com.mochame.sync.api.repository.LocalFirstEngine
import com.mochame.sync.common.InternalTestApi
import com.mochame.sync.common.toBitmask
import com.mochame.sync.di.SyncInfraModule
import com.mochame.sync.di.codec.CodecTestModule
import com.mochame.sync.di.fixtures.SyncInternalFixturesModule
import com.mochame.sync.di.infrastructure.DefaultKeyedLockerModule
import com.mochame.sync.domain.infrastructure.KeyedLocker
import com.mochame.sync.infrastructure.DefaultKeyedLocker
import com.mochame.sync.infrastructure.DefaultLocalFirstEngine
import com.mochame.sync.internal.fixtures.di.FixturesSyncModule
import com.mochame.sync.internal.fixtures.infrastructure.FakeBlobStore
import com.mochame.sync.internal.fixtures.infrastructure.FakeSyncIntentStore
import com.mochame.sync.internal.fixtures.infrastructure.FeatureRepositoryFixture
import com.mochame.sync.internal.fixtures.infrastructure.SpyHlcFactory
import com.mochame.sync.internal.fixtures.infrastructure.SpySyncWorkerHook
import com.mochame.sync.internal.fixtures.serialization.FakeFeatureCodec
import com.mochame.sync.internal.fixtures.serialization.FeatureCodecRouter
import com.mochame.sync.internal.fixtures.serialization.FeatureCodecRouterFixture
import com.mochame.sync.internal.fixtures.serialization.FeatureCodecV1
import com.mochame.sync.internal.fixtures.serialization.FeatureEntity
import com.mochame.sync.spi.infrastructure.BufferProvider
import com.mochame.sync.spi.infrastructure.SyncReceiver
import com.mochame.sync.spi.models.DecodeContext
import com.mochame.sync.spi.policy.ExecutionPolicy
import com.mochame.utils.fixtures.FakeTimeUtils
import com.mochame.utils.fixtures.TestNodeId
import kotlinx.coroutines.Dispatchers
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single


/**
 * Test Fixture & Verification:
 *
 * Faked / Test-controlled dependencies:
 * - HlcFactory: Spy
 * - TransactionProvider: Fake
 * - SyncIntentStore: Fake
 * - SyncWorkerHook: Spy
 * - NodeContextManager & BootStatusProvider: Fakes
 * - FeatureCodecRouterFixture & FakeFeatureCodec: Routes as default to fake codec
 * - BlobStore: Fake
 *
 * Integrated Dependencies:
 * - ExecutionPolicy (StaggeredDbRetryPolicy)
 * - KeyedLocker (DefaultKeyedLocker)
 */


@Module(
    includes = [
        SyncInfraModule::class,
        FixturesSyncModule::class,
        SyncInternalFixturesModule::class,
        DefaultKeyedLockerModule::class,
        CodecTestModule::class,
        FixturesNodeModule::class,
        FixturesPlatformModule::class,
        StaggeredDbRetryPolicyModule::class,
        TestLoggerModule::class
    ]
)
@ComponentScan(
    "com.mochame.sync.di.api",
    "com.mochame.sync.internal.fixtures",
    "com.mochame.sync.engine"
)
internal class LocalFirstRepoTestModule {

    @Single(binds = [KeyedLocker::class])
    fun provideRealLocker(): DefaultKeyedLocker = DefaultKeyedLocker()

    @OptIn(InternalTestApi::class)
    @Single
    fun <T : LocalFirstEntity<T>> provideFeatureRepository(
        featureContext: FeatureContext = FeatureContext.TEST_STUB_A,
        engine: LocalFirstEngine,
        codecRouter: FeatureCodecRouterFixture,
        logger: Logger,
    ): FeatureRepositoryFixture = FeatureRepositoryFixture(
        featureContext = featureContext,
        engine = engine,
        codecRouter = codecRouter,
        logger = logger
    )

    @Single(binds = [SyncReceiver::class])
    fun provideFixtureRepoReceiver(repo: FeatureRepositoryFixture): SyncReceiver =
        repo.asSyncReceiver()
}

@Factory
@ExperimentalKermitApi
internal class LocalFirstRepoTestEnv(
    val repo: FeatureRepositoryFixture,
    val engine: DefaultLocalFirstEngine,
    val hlcFactory: SpyHlcFactory,
    val intentStore: FakeSyncIntentStore,
    val workerHook: SpySyncWorkerHook,
    val nodeManager: FakeNodeContextManager,
    val bootProvider: SpyBootStatusManager,
    val integratedCodec: FeatureCodecV1,
    val blobStore: FakeBlobStore,
    val transactor: FakeTransactionProvider,
    val fakeClock: FakeTimeUtils,
    val locker: DefaultKeyedLocker,
    val fakeBufferProvider: BufferProvider,
    val logger: Logger,
    val writer: TestLogWriter,
    val executor: ExecutionPolicy,
) {
    @OptIn(InternalTestApi::class)
    fun createCodecIntegratedRepo(
        featureContext: FeatureContext = FeatureContext.TEST_STUB_A
    ): FeatureRepositoryFixture = FeatureRepositoryFixture(
        featureContext = featureContext,
        engine = engine,
        codecRouter = FeatureCodecRouter(integratedCodec, logger),
        logger = logger
    )

    @OptIn(InternalTestApi::class)
    fun createIntegratedMultiThreadedRepo(
        logger: Logger,
        fakeBufferProvider: BufferProvider,
        featureContext: FeatureContext = FeatureContext.TEST_STUB_A
    ): FeatureRepositoryFixture = FeatureRepositoryFixture(
        featureContext = featureContext,
        engine = DefaultLocalFirstEngine(
            hlcFactory = hlcFactory,
            transactor = transactor,
            blobStore = blobStore,
            intentStore = intentStore,
            workerHook = workerHook,
            executor = executor,
            locker = locker,
            logger = logger,
            nodeManager = nodeManager,
            bootProvider = bootProvider,
            ioContext = Dispatchers.Default
        ),
        codecRouter = FeatureCodecRouterFixture(
            integratedCodec,
            FakeFeatureCodec(fakeBufferProvider),
            logger
        ),
        logger = logger
    )

    suspend fun setupValidContext(hlc: HLC? = null) {
        hlcFactory.hydrate(hlc, TestNodeId.A)
        bootProvider.updateState(BootState.Ready)
    }

    fun makeRemoteIntent(
        new: FeatureEntity,
        old: FeatureEntity?,
        hlc: HLC,
        op: MutationOp
    ): Pair<DecodeContext, ByteArray> {
        val payload = integratedCodec.encode(new, old)

        val changedTags = integratedCodec.computeChangedTags(new, old)

        val context = DecodeContext(
            candidateKey = new.id,
            hlc = hlc,
            op = op,
            featureSchemaVersion = 1,
            changedMask = changedTags.toBitmask()
        )

        return context to payload
    }
}