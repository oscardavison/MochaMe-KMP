@file:OptIn(ExperimentalKermitApi::class)

package com.mochame.sync.di.domain

import co.touchlab.kermit.ExperimentalKermitApi
import co.touchlab.kermit.Logger
import co.touchlab.kermit.TestLogWriter
import com.mochame.logger.test.TestLoggerModule
import com.mochame.sync.di.fixtures.SyncInternalFixturesModule
import com.mochame.sync.domain.TEST_PRUNE_DAYS
import com.mochame.sync.domain.policy.StaggeredDbRetryPolicy
import com.mochame.sync.domain.policy.TestStaggerConfig
import com.mochame.sync.domain.usecase.PruneIntentsUseCase
import com.mochame.sync.internal.fixtures.infrastructure.FakeSyncIntentStore
import com.mochame.utils.fixtures.FakeTimeUtils
import com.mochame.utils.fixtures.di.FakeTimeProviderModule
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single

@Module(
    includes = [
        SyncInternalFixturesModule::class,
        TestLogWriter::class,
        FakeTimeProviderModule::class
    ]
)
@ComponentScan("com.mochame.sync.di.domain")
internal class SyncPruneIntentsTestModule {
    @Single
    fun provideUseCase(
        intentStore: FakeSyncIntentStore,
        dateTimeUtils: FakeTimeUtils,
        logger: Logger
    ): PruneIntentsUseCase =
        PruneIntentsUseCase(intentStore, dateTimeUtils, TEST_PRUNE_DAYS, 2, logger)
}

@Factory
internal data class PruneIntentsTestEnv(
    val useCase: PruneIntentsUseCase,
    val fakeStore: FakeSyncIntentStore,
    val fakeClock: FakeTimeUtils,
    val logWriter: TestLogWriter,
)

@Module(includes = [TestLoggerModule::class])
@ComponentScan("com.mochame.sync.di.domain")
class StaggeredDbPolicyTestModule {
    @Single
    fun provideTestStaggeredDbRetryPolicy(logger: Logger): StaggeredDbRetryPolicy =
        StaggeredDbRetryPolicy(
            logger,
            TestStaggerConfig.MAX_ATTEMPTS,
            TestStaggerConfig.INITIAL_DELAY
        )
}

@Factory
class StaggeredDbPolicyTestEnv(
    val executor: StaggeredDbRetryPolicy,
    val writer: TestLogWriter,
    val logger: Logger,
    val failureBoundary: Int = TestStaggerConfig.MAX_ATTEMPTS - 1
)