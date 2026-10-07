package com.mochame.sync.di.infrastructure

import co.touchlab.kermit.ExperimentalKermitApi
import co.touchlab.kermit.Logger
import co.touchlab.kermit.TestLogWriter
import com.mochame.annotations.InternalTestApi
import com.mochame.logger.test.TestLoggerModule
import com.mochame.platform.fixtures.FakeTransactionProvider
import com.mochame.platform.fixtures.di.FixturesPlatformModule
import com.mochame.sync.api.SyncAdaptor
import com.mochame.sync.api.SyncAdaptorFactory
import com.mochame.sync.api.boot.BootState
import com.mochame.sync.api.metadata.FeatureContext
import com.mochame.sync.api.metadata.MutationOp
import com.mochame.sync.api.models.HLC
import com.mochame.sync.di.StaggeredDbRetryPolicyModule
import com.mochame.sync.di.SyncInfraModule
import com.mochame.sync.di.codec.CodecTestModule
import com.mochame.sync.di.fixtures.SyncInternalFixturesModule
import com.mochame.sync.domain.crdt.CrdtReconciler
import com.mochame.sync.domain.crdt.DefaultCrdtReconciler
import com.mochame.sync.domain.infrastructure.LocalFirstEngine
import com.mochame.sync.domain.model.DecodeContext
import com.mochame.sync.domain.policy.ExecutionPolicy
import com.mochame.sync.domain.policy.StaggeredDbRetryPolicy
import com.mochame.sync.infrastructure.DefaultKeyedLocker
import com.mochame.sync.infrastructure.DefaultLocalFirstEngine
import com.mochame.sync.infrastructure.adaptor.DefaultSyncAdaptorFactory
import com.mochame.sync.infrastructure.adaptor.SyncReceiverRegistry
import com.mochame.sync.internal.fixtures.infrastructure.FakeSyncIntentStore
import com.mochame.sync.internal.fixtures.infrastructure.FeatureRepositoryFixture
import com.mochame.sync.internal.fixtures.infrastructure.SpyBlobStore
import com.mochame.sync.internal.fixtures.infrastructure.SpyHlcFactory
import com.mochame.sync.internal.fixtures.infrastructure.SpySyncWorkerHook
import com.mochame.sync.internal.fixtures.node.FakeNodeContextManager
import com.mochame.sync.internal.fixtures.node.SpyBootStatusManager
import com.mochame.sync.internal.fixtures.serialization.IntegratedResolver
import com.mochame.sync.internal.fixtures.serialization.CodecResolverFixture
import com.mochame.sync.internal.fixtures.serialization.FakeFeatureCodec
import com.mochame.sync.internal.fixtures.serialization.FeatureCodecV1
import com.mochame.sync.internal.fixtures.serialization.FeatureEntity
import com.mochame.sync.spi.BufferProvider
import com.mochame.sync.utils.toBitmask
import com.mochame.utils.fixtures.FakeTimeUtils
import com.mochame.utils.fixtures.TestNodeId
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single

@Module(
    includes = [
        SyncInfraModule::class,
        SyncInternalFixturesModule::class,
        DefaultKeyedLockerModule::class,
        CodecTestModule::class,
        FixturesPlatformModule::class,
        StaggeredDbRetryPolicyModule::class,
        TestLoggerModule::class
    ]
)
@ComponentScan("com.mochame.sync.di.infrastructure")
internal class LocalFirstRepoTestModule {

    @Single(binds = [CrdtReconciler::class, DefaultCrdtReconciler::class])
    fun provideCrdtReconciler(logger: Logger): DefaultCrdtReconciler = DefaultCrdtReconciler(logger)

    @Single(binds = [LocalFirstEngine::class, DefaultLocalFirstEngine::class])
    fun provideDefaultLocalFirstEngine(
        hlcFactory: SpyHlcFactory,
        transactor: FakeTransactionProvider,
        blobStore: SpyBlobStore,
        intentStore: FakeSyncIntentStore,
        workerHook: SpySyncWorkerHook,
        executor: StaggeredDbRetryPolicy,
        locker: DefaultKeyedLocker,
        logger: Logger,
        nodeManager: FakeNodeContextManager,
        bootProvider: SpyBootStatusManager,
    ): DefaultLocalFirstEngine = DefaultLocalFirstEngine(
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
    )

    @Single
    fun provideSyncReceiverRegistry(): SyncReceiverRegistry = SyncReceiverRegistry()

    @Single(binds = [SyncAdaptorFactory::class])
    fun provideSyncAdaptorFactory(
        engine: LocalFirstEngine,
        registry: SyncReceiverRegistry,
        resolver: CrdtReconciler,
        logger: Logger,
    ): SyncAdaptorFactory = DefaultSyncAdaptorFactory(
        engine = engine,
        registry = registry,
        resolver = resolver,
        logger = logger
    )

    @OptIn(InternalTestApi::class)
    @Single
    fun provideFeatureRepository(
        featureContext: FeatureContext = FeatureContext.TEST_STUB_A,
        engine: LocalFirstEngine,
        codecRouter: CodecResolverFixture,
        reconciler: CrdtReconciler,
        logger: Logger,
    ): FeatureRepositoryFixture = FeatureRepositoryFixture(
        featureContext = featureContext,
        engine = engine,
        codec = codecRouter,
        reconciler = reconciler,
        logger = logger
    )
}

@Factory
@ExperimentalKermitApi
internal class LocalFirstEngineTestEnv(
    val repo: FeatureRepositoryFixture,
    val engine: DefaultLocalFirstEngine,
    val reconciler: CrdtReconciler,
    val hlcFactory: SpyHlcFactory,
    val intentStore: FakeSyncIntentStore,
    val workerHook: SpySyncWorkerHook,
    val nodeManager: FakeNodeContextManager,
    val bootProvider: SpyBootStatusManager,
    val integratedCodec: FeatureCodecV1,
    val blobStore: SpyBlobStore,
    val transactor: FakeTransactionProvider,
    val fakeClock: FakeTimeUtils,
    val locker: DefaultKeyedLocker,
    val fakeBufferProvider: BufferProvider,
    val logger: Logger,
    val writer: TestLogWriter,
    val executor: ExecutionPolicy,
    val adaptorFactory: SyncAdaptorFactory,
    val receiverRegistry: SyncReceiverRegistry,
) {
    val adaptor: SyncAdaptor<FeatureEntity>
        get() = repo.bridge

    @OptIn(InternalTestApi::class)
    fun createCodecIntegratedRepo(
        featureContext: FeatureContext = FeatureContext.TEST_STUB_A
    ): FeatureRepositoryFixture = FeatureRepositoryFixture(
        featureContext = featureContext,
        engine = engine,
        codec = IntegratedResolver(integratedCodec, logger),
        reconciler = reconciler,
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
        ),
        codec = CodecResolverFixture(
            integratedCodec,
            FakeFeatureCodec(fakeBufferProvider),
            logger
        ),
        reconciler = reconciler,
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
            primaryKey = new.id,
            hlc = hlc,
            op = op,
            featureSchemaVersion = 1,
            changedMask = changedTags.toBitmask()
        )

        return context to payload
    }
}