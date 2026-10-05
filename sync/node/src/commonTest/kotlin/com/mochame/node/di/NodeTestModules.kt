@file:OptIn(ExperimentalKermitApi::class)

package com.mochame.node.di

import co.touchlab.kermit.ExperimentalKermitApi
import co.touchlab.kermit.Logger
import co.touchlab.kermit.TestLogWriter
import com.mochame.logger.test.TestLoggerModule
import com.mochame.node.data.NodeContextDao
import com.mochame.node.data.NodeContextMicroSchema
import com.mochame.node.policies.StaggeredDbRetryPolicy
import com.mochame.node.policies.TestStaggerConfig
import com.mochame.support.TestSupportModule
import com.mochame.sync.spi.node.IdGenerator
import com.mochame.sync.spi.node.NodeContextManager
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single


// -----------------------------------------------------------
// Modules
// -----------------------------------------------------------

@ComponentScan("com.mochame.node.di")
@Module(
    includes = [
        NodeProductionModule::class,
        NodeTestPersistenceModule::class,
        TestSupportModule::class,
    ]
)
class NodeContextIntTestModule

@Module
class NodeTestPersistenceModule {
    @Single
    fun provideNodeContextDao(db: NodeContextMicroSchema): NodeContextDao =
        db.nodeContextDao()

}

@Module(includes = [TestLoggerModule::class])
@ComponentScan("com.mochame.node.di")
class StaggeredDbPolicyTestModule {

    @Single
    fun provideTestStaggeredDbRetryPolicy(logger: Logger): StaggeredDbRetryPolicy =
        StaggeredDbRetryPolicy(
            logger,
            TestStaggerConfig.MAX_ATTEMPTS,
            TestStaggerConfig.INITIAL_DELAY
        )
}

// -----------------------------------------------------------
// Environments
// -----------------------------------------------------------

@Factory
data class NodeContextIntTestEnv(
    val db: NodeContextMicroSchema,
    val dao: NodeContextDao,
    val idGen: IdGenerator,
    val manager: NodeContextManager,
    val logger: Logger,
    val writer: TestLogWriter
//    @NodeManagerMutex val managerMutex: Mutex
)

@Factory
class StaggeredDbPolicyTestEnv(
    val executor: StaggeredDbRetryPolicy,
    val writer: TestLogWriter,
    val logger: Logger,
    val failureBoundary: Int = TestStaggerConfig.MAX_ATTEMPTS - 1
)
