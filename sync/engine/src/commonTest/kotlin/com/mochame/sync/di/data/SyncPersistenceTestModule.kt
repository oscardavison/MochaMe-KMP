package com.mochame.sync.di.data

import com.mochame.support.TestTargetsProviderModule
import com.mochame.sync.data.NodeContextDao
import com.mochame.sync.data.QuarantinedPayloadDao
import com.mochame.sync.data.SyncIntentDao
import com.mochame.sync.data.SyncMicroSchema
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single

@Module(includes = [TestTargetsProviderModule::class])
@ComponentScan("com.mochame.sync.di.data")
internal class SyncPersistenceTestModule {
    @Single
    fun provideIntentDao(db: SyncMicroSchema): SyncIntentDao = db.syncIntentDao()

    @Single
    fun provideQuarantinedPayloadDao(db: SyncMicroSchema): QuarantinedPayloadDao = db.quarantinedPayloadDao()

    @Single
    fun provideNodeContextDao(db: SyncMicroSchema): NodeContextDao = db.nodeContextDao()
}

