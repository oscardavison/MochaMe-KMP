package com.mochame.sync.di.infrastructure

import co.touchlab.kermit.ExperimentalKermitApi
import co.touchlab.kermit.Logger
import co.touchlab.kermit.TestLogWriter
import com.mochame.logger.test.TestLoggerModule
import com.mochame.sync.data.NodeContextDao
import com.mochame.sync.data.SyncMicroSchema
import com.mochame.sync.di.SyncInfraModule
import com.mochame.sync.di.data.SyncPersistenceTestModule
import com.mochame.sync.infrastructure.node.DefaultNodeContextManager
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Module


@Module(
    includes = [
        SyncPersistenceTestModule::class,
        SyncInfraModule::class,
        TestLoggerModule::class,
    ]
)
@ComponentScan("com.mochame.sync.di.infrastructure")
internal class NodeContextTestModule

@ExperimentalKermitApi
@Factory
internal class NodeContextTestEnv(
    val db: SyncMicroSchema,
    val dao: NodeContextDao,
    val manager: DefaultNodeContextManager,
    val logger: Logger,
    val writer: TestLogWriter
)