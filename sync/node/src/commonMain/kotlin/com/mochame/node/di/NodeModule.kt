package com.mochame.node.di

import co.touchlab.kermit.Logger
import com.mochame.logger.LoggerModule
import com.mochame.node.policies.StaggeredDbRetryPolicy
import com.mochame.sync.spi.policy.ExecutionPolicy
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single

@Module(includes = [StaggeredDbRetryPolicyModule::class, ])
@ComponentScan("com.mochame.node")
class NodeProductionModule

@Module(includes = [LoggerModule::class])
class StaggeredDbRetryPolicyModule {
    @Single(binds = [ExecutionPolicy::class])
    fun provideStaggeredDbPolicyModule(logger: Logger): ExecutionPolicy =
        StaggeredDbRetryPolicy(logger = logger)
}

/*
    Having an issue with declaring the same type return based on Qualifier
    usage between different modules. MochaAssemblyModule fails if this
    loads after SyncConcurrencyModule, and passes that module. Or fails that
    module and passes this module if this is listed as the first resolved
    module. Not using this for now. If testing needs it, revisit.
 */
//@Module
//class NodeConcurrencyModule {
//    @Single
//    @NodeManagerMutex
//    fun provideNodeManagerMutex(): Mutex = Mutex()
//}