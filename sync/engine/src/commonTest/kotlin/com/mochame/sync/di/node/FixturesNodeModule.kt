package com.mochame.sync.di.node

import com.mochame.sync.api.boot.BootStatusProvider
import com.mochame.sync.api.boot.BootStatusUpdater
import com.mochame.sync.domain.policy.ExecutionPolicy
import com.mochame.sync.internal.fixtures.node.SpyBootStatusManager
import com.mochame.sync.internal.fixtures.node.FakeExecutionPolicy
import com.mochame.sync.internal.fixtures.node.FakeNodeContextManager
import com.mochame.sync.domain.infrastructure.NodeContextManager
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single
import kotlin.time.Duration.Companion.seconds

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
