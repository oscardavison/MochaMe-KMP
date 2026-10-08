package com.mochame.bio.di

import com.mochame.bio.data.BioMicroSchema
import com.mochame.bio.data.DailyContextDao
import com.mochame.bio.data.DefaultDailyContextRepository
import com.mochame.bio.data.toDomain
import com.mochame.bio.data.toEntity
import com.mochame.platform.fixtures.di.FixturesPlatformModule
import com.mochame.support.TestSupportModule
import com.mochame.sync.api.SyncAdaptor
import com.mochame.sync.fixtures.FakeSyncAdaptor
import com.mochame.utils.fixtures.MochaFakeTimeUtils
import com.mochame.utils.fixtures.di.FakeMochaTimeProviderModule
import org.koin.core.annotation.ComponentScan
import org.koin.core.annotation.Factory
import org.koin.core.annotation.Module
import org.koin.core.annotation.Single

@Module(
    includes = [
        BioDataModule::class,
        FakeMochaTimeProviderModule::class,
        TestSupportModule::class
    ]
)
@ComponentScan("com.mochame.bio.di")
internal class DailyContextDataTestModule {

    @Single
    fun provideDailyContextDao(db: BioMicroSchema): DailyContextDao = db.bioDao()

    @Single(binds = [SyncAdaptor::class])
    fun provideFakeAdaptor(dao: DailyContextDao) = FakeSyncAdaptor(
        fetchById = { dao.getContextById(it)?.toDomain() },
        save = { dao.upsert(it.toEntity()) }
    )
}

@Factory
internal class BioTestEnv(
    val contextRepo: DefaultDailyContextRepository,
    val contextDao: DailyContextDao,
    val fakeClock: MochaFakeTimeUtils,
)