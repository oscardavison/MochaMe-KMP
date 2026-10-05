package com.mochame.node.fixtures.di

import com.mochame.node.fixtures.SpyBootStatusManager
import com.mochame.node.fixtures.FakeExecutionPolicy
import com.mochame.node.fixtures.FakeIdGenerator
import com.mochame.node.fixtures.FakeNodeContextManager
import com.mochame.sync.api.boot.BootStatusProvider
import com.mochame.sync.api.boot.BootStatusUpdater
import com.mochame.sync.spi.node.IdGenerator
import com.mochame.sync.spi.node.NodeContextManager
import com.mochame.sync.spi.policy.ExecutionPolicy
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single
import kotlin.time.Duration.Companion.seconds


@Module(includes = [FakeIdGeneratorModule::class])
class FixturesNodeModule {
    @Single(binds = [NodeContextManager::class, FakeNodeContextManager::class])
    fun provideFakeNodeManager(): FakeNodeContextManager = FakeNodeContextManager()

    @Single(binds = [BootStatusProvider::class, BootStatusUpdater::class, SpyBootStatusManager::class])
    fun provideSpyBootStatusManager(): SpyBootStatusManager =
        SpyBootStatusManager(timeout = FixturesNodeConfig.BOOT_TIMEOUT)

    @Single(binds = [ExecutionPolicy::class, FakeExecutionPolicy::class])
    fun provideFakeExecutionPolicy(): FakeExecutionPolicy = FakeExecutionPolicy()
}

@Module
class FakeIdGeneratorModule {
    @Single(binds = [IdGenerator::class, FakeIdGenerator::class])
    fun provideFakeIdGenerator(): IdGenerator = FakeIdGenerator()
}

object FixturesNodeConfig {
    val BOOT_TIMEOUT = 5.seconds
}